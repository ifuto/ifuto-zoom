package com.ifuto.fastgenerate.pipeline;

import com.ifuto.fastgenerate.FastGenerateConfig;
import com.ifuto.fastgenerate.FastGenerateMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 先読みの実行役。
 *
 * <p>1 tick ごとに「プレイヤーの前方のリング」を計算し、まだ無いチャンクに
 * ローディングチケットを付けて生成を投げる。できたチャンクはチケットで保持されるので、
 * プレイヤーが到着したときには<b>生成済み</b>（＝読み込むだけ）になっている。</p>
 *
 * <p>設計の根拠:</p>
 * <ul>
 *   <li>先行生成 = デカップルド・アクセス/実行 &amp; ポインタ型プリフェッチ
 *       (Smith 1982; Joseph &amp; Grunwald 1997)</li>
 *   <li>同時実行数 L と 1 個あたりの所要時間 W からスループットは L/W
 *       （Little 1961）。バニラは L が小さいのでコアが遊ぶ</li>
 *   <li>ウィンドウは AIMD で決める（{@link TickController}）</li>
 * </ul>
 *
 * <p>壊れたら 1 回だけログして自分を止める（ゲームを巻き込まない）。</p>
 */
public final class GenerationDriver {
	/** チケットを解放する余裕（行き来の振動を防ぐ） */
	private static final int RELEASE_MARGIN = 2;
	/** これ以上動いたらテレポート扱い（方向予測を捨てる）[ブロック] */
	private static final double TELEPORT_DISTANCE = 64.0;

	private static final Map<ServerWorld, WorldState> STATES = new HashMap<>();
	private static volatile double frameMs = 0.0;
	private static boolean fatal;
	private static long tickStartNanos;

	private GenerationDriver() {
	}

	/** クライアントのフレーム時間を受け取る（専用サーバでは来ない） */
	public static void setFrameTime(double ms) {
		if (ms > 0.0 && ms < 500.0) {
			frameMs = ms;
		}
	}

	public static void beginTick() {
		tickStartNanos = System.nanoTime();
	}

	public static void tick(MinecraftServer server) {
		if (fatal) {
			return;
		}
		FastGenerateConfig config = FastGenerateConfig.get();
		double workMs = tickStartNanos == 0L ? 50.0 : (System.nanoTime() - tickStartNanos) / 1.0e6;
		double frame = frameMs;

		try {
			for (ServerWorld world : server.getWorlds()) {
				tickWorld(world, server, config, workMs, frame);
			}
		} catch (Throwable t) {
			fatal = true;
			FastGenerateMod.LOGGER.error("[fastgenerate] prefetch disabled after an error (the game keeps running)", t);
			releaseAll(server);
		}
	}

	private static void tickWorld(ServerWorld world, MinecraftServer server,
			FastGenerateConfig config, double workMs, double frame) {
		WorldState state = STATES.computeIfAbsent(world, w -> new WorldState(config));
		ServerChunkManager manager = world.getChunkManager();

		if (!config.enabled || config.leadChunks <= 0) {
			if (!state.tickets.isEmpty()) {
				releaseAll(world, state, manager);
			}
			return;
		}

		int viewDistance = server.getPlayerManager().getViewDistance();
		int inner = Math.max(2, viewDistance);
		int outer = Math.min(48, inner + config.leadChunks);

		List<ChunkPlanner.Viewpoint> views = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (ServerPlayerEntity player : world.getPlayers()) {
			double vx = 0.0;
			double vz = 0.0;
			double[] last = state.lastPos.get(player.getUuid());
			double px = player.getX();
			double pz = player.getZ();
			if (last != null) {
				double dx = px - last[0];
				double dz = pz - last[1];
				if (Math.abs(dx) < TELEPORT_DISTANCE && Math.abs(dz) < TELEPORT_DISTANCE) {
					vx = dx;
					vz = dz;
				}
			}
			state.lastPos.put(player.getUuid(), new double[] {px, pz});
			seen.add(player.getUuid());
			views.add(new ChunkPlanner.Viewpoint(player.getBlockX() >> 4, player.getBlockZ() >> 4, vx, vz));
		}
		state.lastPos.keySet().retainAll(seen);

		// 誰もいない世界は手を出さない
		if (views.isEmpty()) {
			if (!state.tickets.isEmpty()) {
				releaseAll(world, state, manager);
			}
			return;
		}

		// 1) 遠ざかったチャンクのチケットを返す（メモリを食い続けない）
		int keepRadius = outer + RELEASE_MARGIN;
		for (Long key : new ArrayList<>(state.tickets)) {
			if (state.inFlight.contains(key)) {
				continue;
			}
			int x = ChunkPlanner.keyX(key);
			int z = ChunkPlanner.keyZ(key);
			if (!ChunkPlanner.withinAny(views, x, z, keepRadius)) {
				state.tickets.remove(key);
				state.released.incrementAndGet();
				try {
					manager.removeTicket(ChunkTicketType.UNKNOWN, new ChunkPos(x, z), 0);
				} catch (Throwable ignored) {
					// チケットが既に無いだけなら何もしなくてよい
				}
			}
		}

		state.controller.sample(workMs, frame);
		int window = state.controller.update(config.targetMspt, config.targetFrameMs);

		// 2) 前方のリングを優先順に生成
		long[] order = ChunkPlanner.order(views, outer, config.directionBias, 0);
		int issued = 0;
		for (long key : order) {
			if (issued >= config.issuePerTick || state.inFlight.size() >= window) {
				break;
			}
			int x = ChunkPlanner.keyX(key);
			int z = ChunkPlanner.keyZ(key);
			if (ChunkPlanner.withinAny(views, x, z, inner)) {
				continue;   // 内側はバニラのプレイヤーチケットが面倒を見る
			}
			if (state.inFlight.contains(key) || state.tickets.contains(key)) {
				continue;
			}
			if (state.tickets.size() >= config.maxTickets) {
				break;      // 念のため
			}
			if (manager.isChunkLoaded(x, z)) {
				continue;   // もう出来ている（誰かのチケットで残っている）
			}
			if (!state.tickets.add(key)) {
				continue;
			}
			state.inFlight.add(key);
			state.issued.incrementAndGet();
			try {
				ChunkPos pos = new ChunkPos(x, z);
				CompletableFuture<?> future = manager.addChunkLoadingTicket(ChunkTicketType.UNKNOWN, pos, 0);
				if (future != null) {
					future.whenComplete((value, error) -> {
						state.inFlight.remove(key);
						if (error != null) {
							state.failed.incrementAndGet();
						} else {
							state.completed.incrementAndGet();
						}
					});
				} else {
					state.inFlight.remove(key);
				}
			} catch (Throwable t) {
				state.inFlight.remove(key);
				state.tickets.remove(key);
				state.failed.incrementAndGet();
				throw t;
			}
			issued++;
		}
	}

	public static void releaseAll(MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			WorldState state = STATES.get(world);
			if (state != null) {
				releaseAll(world, state, world.getChunkManager());
			}
		}
	}

	private static void releaseAll(ServerWorld world, WorldState state, ServerChunkManager manager) {
		for (Long key : new ArrayList<>(state.tickets)) {
			try {
				manager.removeTicket(ChunkTicketType.UNKNOWN,
					new ChunkPos(ChunkPlanner.keyX(key), ChunkPlanner.keyZ(key)), 0);
			} catch (Throwable ignored) {
				// 解放できなくても実害はない（次のワールドで作り直される）
			}
		}
		state.tickets.clear();
		state.inFlight.clear();
		state.lastPos.clear();
	}

	public static void reset() {
		STATES.clear();
		fatal = false;
		tickStartNanos = 0L;
	}

	/** /fastgenerate stats 用の文字列 */
	public static List<String> describe(MinecraftServer server) {
		List<String> lines = new ArrayList<>();
		FastGenerateConfig config = FastGenerateConfig.get();
		lines.add("FastGenerate " + FastGenerateMod.VERSION
			+ (fatal ? " (止まっています)" : config.enabled ? " (ON)" : " (OFF)"));
		int viewDistance = server.getPlayerManager().getViewDistance();
		for (ServerWorld world : server.getWorlds()) {
			WorldState state = STATES.get(world);
			if (state == null) {
				continue;
			}
			lines.add(String.format(java.util.Locale.ROOT,
				"  %s: window=%d inFlight=%d tickets=%d issued=%d done=%d failed=%d released=%d "
					+ "mspt=%.1fms frame=%.1fms view=%d lead=%d",
				world.getRegistryKey().getValue(), state.controller.window(), state.inFlight.size(),
				state.tickets.size(), state.issued.get(), state.completed.get(), state.failed.get(),
				state.released.get(), state.controller.mspt(), state.controller.frameMs(),
				viewDistance, config.leadChunks));
		}
		if (STATES.isEmpty()) {
			lines.add("  まだワールドに入っていません");
		}
		return lines;
	}

	private static final class WorldState {
		final TickController controller;
		final Set<Long> tickets = ConcurrentHashMap.newKeySet();
		final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
		final Map<UUID, double[]> lastPos = new HashMap<>();
		final AtomicInteger issued = new AtomicInteger();
		final AtomicInteger completed = new AtomicInteger();
		final AtomicInteger failed = new AtomicInteger();
		final AtomicLong released = new AtomicLong();

		WorldState(FastGenerateConfig config) {
			this.controller = new TickController(config.minWindow, config.maxWindow, config.minWindow + 2);
		}
	}
}
