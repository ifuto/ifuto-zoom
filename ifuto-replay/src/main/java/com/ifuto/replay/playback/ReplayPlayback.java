package com.ifuto.replay.playback;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.gui.BlankScreen;
import com.ifuto.replay.mixin.MinecraftClientAccessor;
import com.ifuto.replay.mixin.MouseAccessor;
import com.ifuto.replay.recording.RegistrySnapshot;
import com.ifuto.replay.recording.LocalEvents;
import com.ifuto.replay.recording.ReplayFormat;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.gui.hud.debug.DebugHudProfile;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.ServerLinks;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 録ったパケットをバニラの世界に流し込んで「再生」する。
 *
 * <p>やっていることは単純で、
 * <ol>
 *     <li>保存してあった動的レジストリを組み直す</li>
 *     <li>何も送らない {@link ReplayConnection} を作り、バニラの {@link ClientPlayNetworkHandler} を生やす</li>
 *     <li>録っておいた S2C パケットを時刻順に {@code packet.apply(handler)} するだけ</li>
 * </ol>
 * 世界・エンティティ・ブロック・天気などは全部バニラが組み立てるので、対応表を持たなくていいし、
 * Iris のシェーダーやリソースパックもそのまま効く（普通の世界として描かれるから）。
 *
 * <p>カメラは、録っておいた自分の移動パケット（C2S）から復元する。
 * 受け取った位置の間は線形に補間するので、60fps でも滑らかに動く。
 */
public final class ReplayPlayback implements ReplayStream.Sink {
	/** 倍速の候補 */
	public static final double[] SPEEDS = {0.25, 0.5, 1.0, 2.0, 4.0, 8.0};

	/** 1フレームに使っていい「早送り」の時間（ms）。長い録画でも固まらないように */
	private static final long SEEK_BUDGET_MS = 8L;

	/** これ以上エラーが続いたら再生をあきらめる */
	private static final int MAX_ERRORS = 30;

	/** パケットの識別名のうち「自分の動き」を表すもの */
	private static final String MOVE_POS = "move_player_pos";
	private static final String MOVE_POS_ROT = "move_player_pos_rot";
	private static final String MOVE_ROT = "move_player_rot";
	private static final String MOVE_STATUS = "move_player_status_only";

	/** いま動いている再生（画面を開いていないときは null） */
	private static volatile ReplayPlayback active;

	private final MinecraftClient client;
	private final Path file;
	private final ReplayStream.Metadata meta;
	private final ReplayStream stream;
	private final ClientConnection connection;
	private final ClientPlayNetworkHandler handler;
	private final NetworkState<ClientPlayPacketListener> serverToClient;
	private final NetworkState<ServerPlayPacketListener> clientToServer;
	private final Map<Integer, String> typeNames = new HashMap<>();
	private final Map<Integer, Integer> typeDirections = new HashMap<>();
	private final List<ReplayStream.Marker> markers;
	private final long durationMs;

	/** 読んだけど「まだ時刻が来ていない」パケット */
	private boolean pending;
	private long pendingTimeMs;
	private int pendingTypeIndex;
	private byte @Nullable [] pendingPayload;
	private int pendingOffset;
	private int pendingLength;

	private long timeMs;
	private long previousTickMs;
	private double speed = 1.0;
	private boolean paused;
	private boolean seeking;
	private long seekTargetMs;
	private long lastRealMs;
	private int errors;
	private boolean finished;
	private boolean stopped;
	private boolean hudWasHidden;

	/** 「前に戻る」ときの作り直し（画面側で差し替える） */
	private @Nullable Consumer<Long> restartHandler;

	// --- パケットにならない操作（マウス・キー・画面）の再生 ---
	private double cursorX;
	private double cursorY;
	private boolean cursorValid;
	private int pendingPerspective = -1;
	private @Nullable Boolean pendingDebug;
	private String screenId = "";
	private String chatText = "";

	// --- カメラ ---
	private CamSample camFrom = CamSample.ORIGIN;
	private CamSample camTo = CamSample.ORIGIN;
	private double camX;
	private double camY;
	private double camZ;
	private double camYaw;
	private double camPitch;
	private boolean camOnGround;

	private ReplayPlayback(MinecraftClient client, Path file, ReplayStream.Metadata meta) throws IOException {
		this.client = client;
		this.file = file;
		this.meta = meta;
		this.durationMs = Math.max(0L, meta.durationMs());
		this.markers = List.copyOf(meta.markers());
		this.stream = new ReplayStream(file);

		DynamicRegistryManager.Immutable registries = resolveRegistries(client, this.stream.registries());
		Function<ByteBuf, RegistryByteBuf> binder = RegistryByteBuf.makeFactory(registries);

		this.serverToClient = PlayStateFactories.S2C.bind(binder);
		this.clientToServer = PlayStateFactories.C2S.bind(binder, () -> false);
		this.connection = new ReplayConnection();

		GameProfile profile = client.getGameProfile();
		ClientConnectionState state = new ClientConnectionState(
				new ClientChunkLoadProgress(),
				profile,
				client.getTelemetryManager().createWorldSession(false, null, null),
				registries,
				FeatureFlags.DEFAULT_ENABLED_FEATURES,
				null,
				null,
				null,
				Map.of(),
				null,
				Map.of(),
				ServerLinks.EMPTY,
				Map.of(),
				false
		);

		this.handler = new ClientPlayNetworkHandler(client, this.connection, state);
	}

	/**
	 * 再生を始める。{@code GameJoin} のパケットを流し込んでバニラに世界を作らせたところまで
	 * 進めた状態で返す。
	 */
	public static ReplayPlayback start(MinecraftClient client, Path file) throws IOException {
		ReplayStream.Metadata meta = ReplayStream.preflight(file);
		ReplayPlayback playback = new ReplayPlayback(client, file, meta);
		playback.enterWorld();
		return playback;
	}

	/** いま動いている再生（なければ null） */
	public static @Nullable ReplayPlayback getActive() {
		return active;
	}

	/**
	 * 描画の直前に呼ばれる（{@code GameRenderer#renderWorld} の先頭）。
	 * カメラだけは 1 フレームごとに動かさないとガクガクするので、ここで補間する。
	 */
	public static void onRenderFrame(float tickProgress) {
		ReplayPlayback playback = active;

		if (playback != null && !playback.stopped) {
			playback.updateCamera(playback.previousTickMs
					+ (long) (tickProgress * (double) (playback.timeMs - playback.previousTickMs)));
		}
	}

	// --- 外から見える状態 ---

	public Path file() {
		return this.file;
	}

	public long timeMs() {
		return this.timeMs;
	}

	public long durationMs() {
		return this.durationMs;
	}

	public List<ReplayStream.Marker> markers() {
		return this.markers;
	}

	public ReplayStream.Header header() {
		return this.meta.header();
	}

	public boolean isPaused() {
		return this.paused;
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
		this.lastRealMs = 0L;
	}

	public double getSpeed() {
		return this.speed;
	}

	/** 倍速を次の段階に進める */
	public void cycleSpeed() {
		int index = 0;

		for (int i = 0; i < SPEEDS.length; i++) {
			if (SPEEDS[i] == this.speed) {
				index = i + 1;
				break;
			}
		}

		this.speed = SPEEDS[Math.min(index, SPEEDS.length - 1)];
	}

	public boolean isSeeking() {
		return this.seeking;
	}

	public boolean isFinished() {
		return this.finished;
	}

	public boolean isActive() {
		return !this.stopped;
	}

	/** 世界の作り直しを画面側に任せるための口 */
	public void setRestartHandler(Consumer<Long> handler) {
		this.restartHandler = handler;
	}

	/**
	 * 指定した時刻へ移動する。
	 *
	 * <p>パケットは前向きにしか流せないので、前に戻るときは世界を作り直してから早送りする。
	 * 作り直し自体は画面側（{@link #setRestartHandler}）がやる。
	 */
	public void jumpTo(long targetMs) {
		long target = MathHelper.clamp(targetMs, 0L, this.durationMs);

		if (target < this.timeMs - 100L && this.restartHandler != null) {
			Consumer<Long> handler = this.restartHandler;
			this.restartHandler = null;
			handler.accept(target);
			return;
		}

		this.seekTargetMs = target;
		this.seeking = true;
	}

	/**
	 * 再生をやめて画面を戻す。
	 *
	 * <p>世界の片付けはバニラの切断処理に任せる（ここを自分でやるとあとで壊れやすい）。
	 */
	public void stop(@Nullable Screen returnTo) {
		if (this.stopped) {
			return;
		}

		this.stopped = true;

		if (active == this) {
			active = null;
		}

		this.client.options.hudHidden = this.hudWasHidden;
		this.stream.close();
		this.client.disconnect(returnTo != null ? returnTo : new BlankScreen(), false, true);
	}

	/** 世界だけ片付ける（作り直す前に使う） */
	public void dispose() {
		if (this.stopped) {
			return;
		}

		this.stopped = true;

		if (active == this) {
			active = null;
		}

		this.client.options.hudHidden = this.hudWasHidden;
		this.stream.close();

		// 世界を空にしないと、作り直したときに「もう世界がある」と勘違いされる
		this.client.disconnect(new BlankScreen(), false, true);
	}

	// --- 毎フレームの処理 ---

	/** クライアントの tick の終わりに呼ぶ */
	public void tick() {
		if (this.stopped || this.client.world == null || this.client.player == null) {
			return;
		}

		long now = Util.getMeasuringTimeMs();
		long delta = this.lastRealMs <= 0L ? 50L : Math.min(now - this.lastRealMs, 250L);
		this.lastRealMs = now;
		this.previousTickMs = this.timeMs;

		if (this.seeking) {
			this.stepSeeking();
		} else if (!this.paused) {
			this.timeMs += (long) (delta * this.speed);
			this.pumpDue();
		}

		this.applyInputState();
		this.updateCamera(this.timeMs);
	}

	/**
	 * 記録しておいた「パケットにならない操作」をクライアントへ反映する。
	 *
	 * <p>視点（F5）とデバッグ画面（F3）はバニラの設定をそのまま動かすので、
	 * 録っていた本人の見え方になる。カーソルも録っていた位置へ戻す。
	 */
	private void applyInputState() {
		if (this.pendingPerspective >= 0) {
			Perspective[] perspectives = Perspective.values();

			if (this.pendingPerspective < perspectives.length) {
				this.client.options.setPerspective(perspectives[this.pendingPerspective]);
			}

			this.pendingPerspective = -1;
		}

		if (this.pendingDebug != null) {
			boolean on = this.pendingDebug;
			DebugHudProfile profile = ((MinecraftClientAccessor) this.client).ifutoReplay$getDebugHudProfile();

			// すでに同じ状態なら書き込まない（設定ファイルの書き直しを避ける）
			if (profile != null && this.client.getDebugHud().shouldShowDebugHud() != on) {
				profile.setF3Enabled(on);
			}

			this.pendingDebug = null;
		}

		if (this.cursorValid && this.client.mouse != null) {
			MouseAccessor mouse = (MouseAccessor) this.client.mouse;
			mouse.ifutoReplay$setX(this.cursorX);
			mouse.ifutoReplay$setY(this.cursorY);
		}
	}

	/** 早送り（1フレームの予算の範囲で、目標時刻までパケットを流す） */
	private void stepSeeking() {
		long deadline = Util.getMeasuringTimeMs() + SEEK_BUDGET_MS;

		while (this.timeMs < this.seekTargetMs) {
			this.timeMs = Math.min(this.timeMs + 250L, this.seekTargetMs);
			this.pumpDue();

			if (this.stream.isEnded() || this.finished) {
				break;
			}

			if (Util.getMeasuringTimeMs() >= deadline) {
				return;
			}
		}

		this.seeking = false;
		this.lastRealMs = 0L;
	}

	/** 時刻が来ているパケットを全部流す */
	private void pumpDue() {
		try {
			while (true) {
				if (this.pending) {
					if (this.pendingTimeMs > this.timeMs) {
						return;
					}

					this.applyPending();
					this.pending = false;
				}

				if (this.stream.isEnded() || !this.stream.readNext(this)) {
					this.finished = true;
					return;
				}
			}
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 再生中に読み込みエラー", e);
			this.finished = true;
		}
	}

	// --- パケットの処理 ---

	@Override
	public void packetType(int index, int direction, String name) {
		this.typeNames.put(index, name);
		this.typeDirections.put(index, direction);
	}

	@Override
	public void packet(long timeMs, int typeIndex, byte[] payload, int offset, int length) {
		// 未適用のあいだは読み進めないので、かたまりの中身を指したままで安全
		this.pending = true;
		this.pendingTimeMs = timeMs;
		this.pendingTypeIndex = typeIndex;
		this.pendingPayload = payload;
		this.pendingOffset = offset;
		this.pendingLength = length;
	}

	/**
	 * パケットにならない操作を読み取る。
	 *
	 * <p>ここでは「状態」を覚えるだけ。実際にクライアントへ反映するのは tick() の中
	 * （パケットの適用はクライアントスレッドとは限らないので）。
	 */
	@Override
	public void input(long timeMs, int subtype, byte[] data, int length) {
		if (length <= 1) {
			return;
		}

		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 1, length - 1))) {
			switch (subtype) {
				case ReplayFormat.INPUT_MOUSE_ABS -> {
					this.cursorX = in.readShort();
					this.cursorY = in.readShort();
					this.cursorValid = true;
				}
				case ReplayFormat.INPUT_MOUSE_DELTA -> {
					this.cursorX += in.readShort();
					this.cursorY += in.readShort();
					this.cursorValid = true;
				}
				case ReplayFormat.INPUT_PERSPECTIVE -> this.pendingPerspective = in.readByte();
				case ReplayFormat.INPUT_DEBUG -> this.pendingDebug = in.readByte() != 0;
				case ReplayFormat.INPUT_SCREEN -> this.screenId = in.readUTF();
				case ReplayFormat.INPUT_CHAT_SET -> this.chatText = in.readUTF();
				case ReplayFormat.INPUT_CHAT_APPEND -> {
					int prefix = in.readInt();
					String suffix = in.readUTF();

					if (prefix >= 0 && prefix <= this.chatText.length()) {
						this.chatText = this.chatText.substring(0, prefix) + suffix;
					} else {
						this.chatText = suffix;
					}
				}
				default -> {
				}
			}
		} catch (IOException e) {
			this.errors++;
		}
	}

	/**
	 * クライアントの内側でだけ起きた出来事（パーティクルなど）。
	 *
	 * <p>パケットとして残らないので、ここで同じ物をもう一度起こす。
	 */
	@Override
	public void local(long timeMs, int subtype, byte[] data, int length) {
		if (subtype == LocalEvents.TYPE_PARTICLE) {
			LocalEvents.playParticle(this.client, data);
		} else if (subtype == LocalEvents.TYPE_PARTICLE_BATCH) {
			LocalEvents.playBatch(this.client, data);
		}
	}

	/** いま開いていた画面（空欄 = 開いていない） */
	public String screenId() {
		return this.screenId;
	}

	/** いま入力していた文字（空欄 = 入力していない） */
	public String chatText() {
		return this.chatText;
	}

	@Override
	public void marker(long timeMs, String name) {
		// 再生前に全部拾ってあるので、ここでは何もしない
	}

	private void applyPending() {
		byte[] payload = this.pendingPayload;

		if (payload == null || this.pendingLength <= 0) {
			return;
		}

		int direction = this.typeDirections.getOrDefault(this.pendingTypeIndex, ReplayFormat.DIRECTION_S2C);
		String name = this.typeNames.get(this.pendingTypeIndex);

		if (direction == ReplayFormat.DIRECTION_C2S) {
			// 自分の操作は「カメラの位置」だけ使う。あとは流さない（サーバーがいないので）
			if (name != null && isPlayerMove(name)) {
				this.applyCameraPacket(name, payload, this.pendingOffset, this.pendingLength);
			}

			return;
		}

		// 中身を複写せず、その場で読む（wrappedBuffer は見るだけで所有しない）
		ByteBuf buf = Unpooled.wrappedBuffer(payload, this.pendingOffset, this.pendingLength);

		try {
			Packet<? super ClientPlayPacketListener> packet = this.serverToClient.codec().decode(buf);
			packet.apply(this.handler);
		} catch (Throwable t) {
			this.onError(name, t);
		} finally {
			buf.release();
		}
	}

	private void applyCameraPacket(String name, byte[] payload, int offset, int length) {
		ByteBuf buf = Unpooled.wrappedBuffer(payload, offset, length);

		try {
			Packet<? super ServerPlayPacketListener> packet = this.clientToServer.codec().decode(buf);

			if (!(packet instanceof PlayerMoveC2SPacket move)) {
				return;
			}

			double x = move.getX(this.camX);
			double y = move.getY(this.camY);
			double z = move.getZ(this.camZ);
			float yaw = move.getYaw((float) this.camYaw);
			float pitch = move.getPitch((float) this.camPitch);

			this.camFrom = new CamSample(this.timeMs, this.camX, this.camY, this.camZ, this.camYaw, this.camPitch);
			this.camTo = new CamSample(this.pendingTimeMs, x, y, z, yaw, pitch);
			this.camOnGround = move.isOnGround();
		} catch (Throwable t) {
			this.onError(name, t);
		} finally {
			buf.release();
		}
	}

	private static boolean isPlayerMove(String name) {
		String id = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
		return id.equals(MOVE_POS) || id.equals(MOVE_POS_ROT) || id.equals(MOVE_ROT) || id.equals(MOVE_STATUS);
	}

	private void onError(@Nullable String name, Throwable t) {
		if (++this.errors <= MAX_ERRORS) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] パケットを流し込めませんでした: {}", name, t);
		}

		if (this.errors == MAX_ERRORS) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] エラーが続いているので、これ以上はログに出しません");
		}
	}

	// --- カメラ ---

	private void updateCamera(long atTimeMs) {
		ClientPlayerEntity player = this.client.player;

		if (player == null) {
			return;
		}

		double span = (double) (this.camTo.timeMs() - this.camFrom.timeMs());
		double t = span <= 0.0 ? 1.0 : MathHelper.clamp((atTimeMs - this.camFrom.timeMs()) / span, 0.0, 1.0);

		this.camX = MathHelper.lerp(t, this.camFrom.x(), this.camTo.x());
		this.camY = MathHelper.lerp(t, this.camFrom.y(), this.camTo.y());
		this.camZ = MathHelper.lerp(t, this.camFrom.z(), this.camTo.z());
		this.camYaw = MathHelper.lerp(t, this.camFrom.yaw(), this.camTo.yaw());
		this.camPitch = MathHelper.lerp(t, this.camFrom.pitch(), this.camTo.pitch());

		player.setPosition(this.camX, this.camY, this.camZ);
		player.setYaw((float) this.camYaw);
		player.setPitch((float) this.camPitch);
		player.setBodyYaw((float) this.camYaw);
		player.setVelocity(Vec3d.ZERO);
		player.setOnGround(this.camOnGround);
		player.noClip = true;
	}

	// --- 準備・片付け ---

	/** GameJoin を流し込んで、バニラに世界とプレイヤーを作らせる */
	private void enterWorld() throws IOException {
		long deadline = Util.getMeasuringTimeMs() + 10_000L;

		while (this.client.world == null || this.client.player == null) {
			if (this.stream.isEnded()) {
				break;
			}

			if (Util.getMeasuringTimeMs() > deadline) {
				throw new IOException("ワールドを開始できませんでした（GameJoin パケットが見つかりません）");
			}

			// 時刻を進めずに、世界ができるまで必要なパケットを流す
			if (!this.stream.readNext(this)) {
				break;
			}

			if (this.pending) {
				this.timeMs = this.pendingTimeMs;
				this.applyPending();
				this.pending = false;
			}
		}

		if (this.client.world == null || this.client.player == null) {
			throw new IOException("ワールドを開始できませんでした");
		}

		this.setupPlayer();
		active = this;
	}

	/** 再生中だけの設定（当たり判定なし・無敵・HUD非表示） */
	private void setupPlayer() {
		ClientPlayerEntity player = this.client.player;

		if (player == null) {
			return;
		}

		this.camX = player.getX();
		this.camY = player.getY();
		this.camZ = player.getZ();
		this.camYaw = player.getYaw();
		this.camPitch = player.getPitch();
		this.camFrom = new CamSample(this.timeMs, this.camX, this.camY, this.camZ, this.camYaw, this.camPitch);
		this.camTo = this.camFrom;

		player.getAbilities().invulnerable = true;
		player.noClip = true;
		player.setVelocity(Vec3d.ZERO);

		// プレイヤーが勝手に動いたりダメージを受けたりしないように
		this.hudWasHidden = this.client.options.hudHidden;
		this.client.options.hudHidden = true;
	}

	/**
	 * パケットを復元するためのレジストリ。
	 *
	 * <p>ファイルに入っている物が最優先（録ったときとまったく同じ状態になる）。
	 * 古いファイルで入っていないときは、いまの世界の物を使う。
	 */
	private static DynamicRegistryManager.Immutable resolveRegistries(MinecraftClient client,
																	  net.minecraft.nbt.@Nullable NbtCompound saved) {
		if (saved != null && !saved.isEmpty()) {
			try {
				return RegistrySnapshot.restore(saved, client.getResourceManager());
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 保存されたレジストリを復元できませんでした", t);
			}
		}

		if (client.world != null) {
			return client.world.getRegistryManager().toImmutable();
		}

		ClientPlayNetworkHandler handler = client.getNetworkHandler();

		if (handler != null) {
			return handler.getRegistryManager();
		}

		IfutoReplayClient.LOGGER.warn("[ifuto-replay] レジストリが見つからないので、既定の物で再生します");
		return DynamicRegistryManager.of(Registries.REGISTRIES);
	}

	/** カメラの位置のサンプル（2個あればその間を補間できる） */
	private record CamSample(long timeMs, double x, double y, double z, double yaw, double pitch) {
		static final CamSample ORIGIN = new CamSample(0L, 0.0, 0.0, 0.0, 0.0, 0.0);
	}
}
