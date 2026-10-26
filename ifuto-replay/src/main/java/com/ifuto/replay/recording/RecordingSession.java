package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 録画1回分。パケットの直列化（ここが唯一の“録画の仕事”）とファイル書き込みの管理をする。
 *
 * <p>直列化はパケットを受け取ったスレッド（マルチなら Netty 側、シングルならクライアント側）で
 * その場でやる。パケットはあとから中身が変わる可能性があるので、後回しにできない。
 */
public final class RecordingSession {
	/**
	 * 種類番号ごとの「前回の大きさ」（入れ物の目安）。
	 *
	 * <p>伸ばすのは登録のときだけ（ロックの中）。読む・書くはただの目安なので
	 * ロックしない（古い配列を見ても、入れ物が合わないだけで壊れない）。
	 */
	private volatile int[] sizeHints = new int[128];

	private final Path file;
	private final long startedAtEpoch;
	private final long startedAt;
	private final ReplayConfig config;
	private final ReplayFileWriter writer;

	/** クリップ方式のとき、区間の管理はこちらが持つ（ふつうの録画では null） */
	private final ClipBuffer clip;
	private final long maxQueuedBytes;
	private final long maxDurationMs;

	/** パケットの種類 → 番号と「除外対象か」 */
	private final ConcurrentHashMap<PacketType<?>, TypeInfo> types = new ConcurrentHashMap<>();
	private final AtomicInteger nextTypeIndex = new AtomicInteger();
	private final AtomicLong packetCount = new AtomicLong();
	private final AtomicLong droppedCount = new AtomicLong();

	/** クライアント内で起きた出来事の上限（1秒あたり） */
	private static final int MAX_LOCAL_PER_SECOND = 160;

	/** まとめて1枠で書くパーティクルの上限（件数と大きさ。超えたらすぐ書く） */
	private static final int LOCAL_BATCH_MAX_ENTRIES = 32;
	private static final int LOCAL_BATCH_MAX_BYTES = 16 * 1024;

	private long localWindowStartMs;
	private int localWindowCount;

	/** ためているパーティクル（まとめて1枠で書く。クライアントスレッドからだけ触る） */
	private final List<byte[]> localBatch = new ArrayList<>();

	/** ため始めた時刻（まとめた枠の時刻にする） */
	private long localBatchFirstMs;

	/** ためている中身の合計バイト数 */
	private int localBatchBytes;
	private final AtomicLong errorCount = new AtomicLong();
	private final AtomicLong queuedBytes = new AtomicLong();
	private final AtomicInteger markerCount = new AtomicInteger();

	/** 種類の登録とエンコーダの作り直しはめったに起きないので、ここだけロックする */
	private final Object registerLock = new Object();

	private volatile ClientPlayNetworkHandler boundHandler;
	private boolean registriesCaptured;
	private volatile PacketEncoder encoder;
	private volatile boolean stopping;

	/**
	 * 写し待ちの待機列とその錠。録り始めの1回だけ使う。
	 *
	 * <p>錠の取り順は registerLock → holdLock で統一する（逆にすると固まる）。
	 */
	private final Object holdLock = new Object();
	private final SnapshotHold hold = new SnapshotHold();
	private boolean holding;
	private boolean holdDone;
	private volatile boolean snapshotCancelled;

	RecordingSession(Path file, ReplayConfig config, String mcVersion, String serverName, String playerName,
					 long startedAt) throws IOException {
		this.file = file;
		this.config = config;
		this.startedAt = startedAt;
		this.startedAtEpoch = startedAt;
		// 0 のときは環境（ヒープの大きさ）から自動で決める
		this.maxQueuedBytes = (long) config.queueMegaBytes() * 1024L * 1024L;
		this.maxDurationMs = config.maxDurationMinutes > 0 ? config.maxDurationMinutes * 60_000L : 0L;

		long maxBytes = config.maxFileSizeMb > 0 ? (long) config.maxFileSizeMb * 1024L * 1024L : 0L;

		if (config.clipMode) {
			// クリップ方式: 本体のファイルは作らず、区間を回し続ける
			Path cache = ReplayConfig.getSaveDirectory().resolve(".clip-cache");
			this.writer = null;
			this.clip = new ClipBuffer(this, config, cache, mcVersion, serverName, playerName,
					config.recordClientPackets, this.queuedBytes);
		} else {
			this.writer = new ReplayFileWriter(file, mcVersion, serverName, playerName, startedAt,
					config.recordClientPackets, config.effectiveCompression(), config.indexIntervalMs, maxBytes,
							config.flushIntervalMs, config.queuePackets, this.queuedBytes);
			this.clip = null;
		}
	}

	/** ファイル（または最初の区間）を開いて書き込みスレッドを開始する */
	public void start() {
		if (this.writer != null) {
			this.writer.start();
		}
	}

	/**
	 * 再生に必要なレジストリの写しを保存する（録り始めに1回だけ）。
	 *
	 * <p>直列化は少し重い（数百ミリ秒）ので、録画の開始時に1回だけ呼ぶ。
	 * 圧縮と書き込みは書き込みスレッドがやるので、ゲーム側は直列化だけで済む。
	 */
	public void captureRegistries(ClientPlayNetworkHandler handler) {
		if (this.registriesCaptured || !this.config.saveRegistries || handler == null) {
			return;
		}

		this.registriesCaptured = true;

		// クリップ方式ではファイルがいくつも分かれるので、最後にまとめるときに1回だけ書く
		if (this.clip != null) {
			try {
				NbtCompound nbt = RegistrySnapshot.capture(handler.getRegistryManager());
				this.clip.setRegistries(nbt);
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] レジストリの写しを保存できませんでした", t);
			}

			return;
		}

		try {
			long startedAt = System.nanoTime();
			NbtCompound nbt = RegistrySnapshot.capture(handler.getRegistryManager());

			if (nbt == null || nbt.isEmpty()) {
				return;
			}

			if (!this.offer(PacketTask.registries(nbt))) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] レジストリを保存できませんでした（キューが一杯）");
				return;
			}

			IfutoReplayClient.LOGGER.info("[ifuto-replay] レジストリの写しを保存しました ({} 種類, {} ms)",
					nbt.getKeys().size(), (System.nanoTime() - startedAt) / 1_000_000L);
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] レジストリの写しを保存できませんでした", t);
		}
	}

	/** 接続（= レジストリ）が変わったらエンコーダを作り直す */
	public void bind(ClientPlayNetworkHandler handler) {
		if (handler == null || handler == this.boundHandler) {
			return;
		}

		synchronized (this.registerLock) {
			if (handler == this.boundHandler) {
				return;
			}

			this.boundHandler = handler;

			try {
				this.encoder = new PacketEncoder(handler.getRegistryManager());
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] パケットの変換器を作れませんでした", t);
				this.encoder = null;
			}
		}
	}

	public boolean hasEncoder() {
		return this.encoder != null;
	}

	/**
	 * パケットを1個記録する。
	 *
	 * @param outbound クライアント → サーバー（自分の操作）なら true
	 */
	void capture(Packet<?> packet, boolean outbound, ClientPlayNetworkHandler handler) {
		if (this.stopping || packet == null) {
			return;
		}

		if (handler != this.boundHandler) {
			this.bind(handler);
		}

		PacketEncoder enc = this.encoder;

		if (enc == null) {
			this.errorCount.incrementAndGet();
			return;
		}

		// 自分の移動だけは設定に関わらず残す。再生時のカメラ（POV）はここからしか
		// 復元できないので、捨てると視点がまったく動かなくなる。他の C2S は再生で
		// 使わないので、設定どおり捨てて容量を節約する。
		if (outbound && !this.config.recordClientPackets && !(packet instanceof PlayerMoveC2SPacket)) {
			return;
		}

		PacketType<?> type = packet.getPacketType();
		TypeInfo info = this.types.get(type);

		if (info == null) {
			info = this.register(type, outbound);

			if (info == null) {
				this.errorCount.incrementAndGet();
				return;
			}
		}

		if (this.config.skipKeepAlive && info.noisy()) {
			return;
		}

		long timeMs = System.currentTimeMillis() - this.startedAt;

		// 書き込み待ちが溜まりすぎていたら捨てる（ゲームを止めるよりマシ）
		if (this.queuedBytes.get() > this.maxQueuedBytes) {
			this.droppedCount.incrementAndGet();
			return;
		}

		// 前回と同じくらいの入れ物を先に取る（育て直しのコピーをなくす）
		ByteBuf buffer = PacketTask.sizedBuffer(this.sizeHints, info.index());

		try {
			enc.encode(buffer, packet, outbound);
		} catch (Throwable t) {
			buffer.release();
			this.errorCount.incrementAndGet();
			return;
		}

		int size = buffer.readableBytes();
		PacketTask.noteSize(this.sizeHints, info.index(), size);
		int direction = outbound ? ReplayFormat.DIRECTION_C2S : ReplayFormat.DIRECTION_S2C;
		PacketTask task = PacketTask.packet(timeMs, info.index(), direction, buffer);

		if (!this.offer(task)) {
			buffer.release();
			this.droppedCount.incrementAndGet();
			return;
		}

		this.queuedBytes.addAndGet(size);
		this.packetCount.incrementAndGet();
	}

	/**
	 * いまの世界の写しを別スレッドで組み立てて、録画の先頭に置く。
	 *
	 * <p>サーバーに取り直しを頼むことはしない（BAN を避けるため通信は一切増やさない）ので、
	 * 手元にある世界から「サーバーが送ってきたのと同じパケット」を組み立てる。
	 * 組み立ては重い（地形が多いと数百ms）のでメインスレッドは止めない。
	 * そのあいだに来たパケットは待機列にためて、写しのあとに順番どおり流す。
	 *
	 * @param done 終わったときに呼ばれる（クライアントスレッド。写しがあれば true）。要らなければ null
	 */
	public void beginAsyncSnapshot(MinecraftClient client, @Nullable java.util.function.Consumer<Boolean> done) {
		synchronized (this.holdLock) {
			if (this.holding) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 写しの組み立てが重なりました（2回目は捨てます）");
				return;
			}

			this.holding = true;
			this.holdDone = false;
			this.snapshotCancelled = false;
		}

		Thread worker = new Thread(() -> {
			WorldSnapshot.Snapshot snapshot = null;

			try {
				snapshot = this.buildSnapshot(client, () -> this.stopping || this.snapshotCancelled);
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 世界の写しを作れませんでした", t);
			}

			boolean ok;

			try {
				ok = this.completeSnapshotHold(snapshot);
			} catch (Throwable t) {
				// ここで落とすと待機列が詰まったままになるので、必ず流してから終える
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 世界の写しを置けませんでした", t);
				this.cancelSnapshotHold();
				ok = false;
			}

			if (done != null) {
				boolean result = ok;

				if (client != null) {
					client.execute(() -> done.accept(result));
				} else {
					done.accept(result);
				}
			}
		}, "ifuto-replay-snapshot");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * 世界の写しを組み立てる（まだファイルには書かない）。
	 *
	 * <p>区間の切り替えでは「組み立て → 新区間を開く → 書く」の順にする。
	 * 開いてから組み立てると、そのあいだのパケットが写しより先に入って欠けるため。
	 *
	 * @return 写し。作れなかったら null
	 */
	public WorldSnapshot.@Nullable Snapshot buildSnapshot(MinecraftClient client) {
		return this.buildSnapshot(client, () -> false);
	}

	/** 止められる版の組み立て。止められたらすぐ null で返す。 */
	WorldSnapshot.@Nullable Snapshot buildSnapshot(MinecraftClient client,
			java.util.function.BooleanSupplier cancelled) {
		if (this.stopping || client == null) {
			return null;
		}

		// 半径 0 でも最低 1 として写しは必ず作る（無いと再生不能になるため。設定の 0 は無視する）
		int radius = Math.max(1, this.config.snapshotRadius);

		try {
			return WorldSnapshot.build(client, radius, cancelled);
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 世界の写しを作れませんでした", t);
			return null;
		}
	}

	/** 写し待ちを始める（ためた分は {@link #completeSnapshotHold} か終了時に流す）。 */
	void beginSnapshotHold() {
		synchronized (this.holdLock) {
			this.holding = true;
			this.holdDone = false;
			this.snapshotCancelled = false;
		}
	}

	/**
	 * 写しを積んで待機を流す（組み立てスレッドから呼ぶ）。
	 *
	 * <p>写しの種類の定義・しおり・本体は待機列の末尾に足してから流す。
	 * こうすると「定義より本体が先」にならず、写しの前後どちらにも割り込まない。
	 *
	 * @return 写しを置けたら true
	 */
	boolean completeSnapshotHold(WorldSnapshot.@Nullable Snapshot snapshot) {
		boolean ok = false;

		if (snapshot != null && !this.stopping && !this.snapshotCancelled) {
			ok = this.offerSnapshot(snapshot);
		}

		synchronized (this.holdLock) {
			if (this.holdDone) {
				return ok;
			}

			this.holdDone = true;
			this.holding = false;
			this.drainHoldLocked();
		}

		return ok;
	}

	/** 写し待ちをやめて、ためた分をそのまま流す。終了時と区間切替の安全弁。 */
	void cancelSnapshotHold() {
		this.snapshotCancelled = true;

		synchronized (this.holdLock) {
			if (this.holdDone || !this.holding) {
				return;
			}

			this.holdDone = true;
			this.holding = false;
			this.drainHoldLocked();
		}
	}

	/**
	 * 待機列を空にして順番どおり流す。呼ぶ側が {@link #holdLock} を取っていること。
	 *
	 * <p>錠の中で流し切る（外すと、そのあいだに来た分が追い越して順番が壊れる）。
	 * 積むだけなので一瞬で終わる。
	 */
	private void drainHoldLocked() {
		for (PacketTask task : this.hold.drain()) {
			int size = task == null ? 0 : task.size();
			boolean routed = task != null
					&& (this.clip != null ? this.clip.offer(task) : this.writer.offer(task));

			if (!routed) {
				if (task != null && task.payload != null) {
					task.payload.release();
				}

				this.queuedBytes.addAndGet(-size);
				this.droppedCount.incrementAndGet();
			}
		}
	}

	/**
	 * 組み立てた写しをファイルに書く。
	 *
	 * <p>写しの直前に「ここに写しがある」のしおり（{@code __snap__}）を置く。
	 * 編集で切り出すときの起点に使う。再生の一覧には出さない。
	 *
	 * @return 書けたら true
	 */
	public boolean offerSnapshot(WorldSnapshot.Snapshot snapshot) {
		if (this.stopping || snapshot == null) {
			return false;
		}

		// 写しの直列化は重い（チャンク169個で数百ms）ので、クライアントスレッドでは
		// 「種類の登録」だけ済ませて、本体は書き込みスレッドに回す。種類の定義を
		// 関門より先に積むので、書き込み側は番号だけ見れば書ける。順番はキューが守る。
		PacketEncoder enc = this.encoder;
		List<Packet<?>> packets = snapshot.packets();

		if (enc == null) {
			this.errorCount.addAndGet(packets.size());
			return false;
		}

		try {
			long startedAt = System.nanoTime();
			this.addMarker(ReplayFormat.SNAP_MARKER);

			int[] indices = new int[packets.size()];
			int dropped = 0;

			for (int i = 0; i < packets.size(); i++) {
				Packet<?> packet = packets.get(i);
				TypeInfo info = packet == null ? null : this.types.get(packet.getPacketType());

				if (info == null && packet != null) {
					info = this.register(packet.getPacketType(), false);
				}

				if (info == null) {
					indices[i] = -1;
					dropped++;
				} else {
					indices[i] = info.index();
				}
			}

			long timeMs = System.currentTimeMillis() - this.startedAt;

			if (!this.offer(PacketTask.snapshot(timeMs, snapshot, enc, indices, this.sizeHints))) {
				this.droppedCount.addAndGet(packets.size());
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 世界の写しを書ききれませんでした"
						+ "（書き出しが追いついていません。地形が一部欠けます）");
				return false;
			}

			if (dropped > 0) {
				this.droppedCount.addAndGet(dropped);
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 世界の写しのうち {} パケットを書ききれませんでした"
						+ "（書き出しが追いついていません。地形が一部欠けます）", dropped);
			}

			this.packetCount.addAndGet(packets.size() - dropped);
			IfutoReplayClient.LOGGER.info("[ifuto-replay] 世界の写しを保存しました (チャンク {}, エンティティ {}, {} パケット, {} ms)",
					snapshot.chunks(), snapshot.entities(), packets.size(),
					(System.nanoTime() - startedAt) / 1_000_000L);
			return true;
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 世界の写しを書けませんでした", t);
			return false;
		}
	}

	/**
	 * パケットにならない操作（マウス・キー・画面）を1件記録する。
	 *
	 * <p>本体のパケットに比べると桁違いに小さいので、容量を気にせず記録できる。
	 * 溜まりすぎていたら捨てる（ゲームを止めるよりマシ）。
	 */
	public void recordInput(byte[] data) {
		if (this.stopping || data == null || data.length == 0) {
			return;
		}

		if (this.queuedBytes.get() > this.maxQueuedBytes) {
			this.droppedCount.incrementAndGet();
			return;
		}

		long timeMs = System.currentTimeMillis() - this.startedAt;

		if (!this.offer(PacketTask.input(timeMs, data))) {
			this.droppedCount.incrementAndGet();
			return;
		}

		this.queuedBytes.addAndGet(data.length);
	}

	/**
	 * パーティクルを記録できる状態か（重い変換の前に見る安いゲート）。
	 *
	 * <p>パーティクルのバイト列化はそれなりに重いので、上限を超えているときは
	 * 変換する前に帰る。ここも {@link #recordLocal} もクライアントスレッドからだけ呼ばれる。
	 */
	public boolean allowsLocal() {
		if (this.stopping) {
			return false;
		}

		long now = System.currentTimeMillis();

		if (now - this.localWindowStartMs >= 1000L) {
			this.localWindowStartMs = now;
			this.localWindowCount = 0;
		}

		return this.localWindowCount < MAX_LOCAL_PER_SECOND;
	}

	/**
	 * クライアントの内側でだけ起きた出来事を記録する（パーティクルなど）。
	 *
	 * <p>パーティクルは Minecraft が一番よく出す物なので、**1秒あたりの上限** を決めて
	 * いる（崩しているブロックの破片などで膨らまないように）。超えた分は捨てるだけで、
	 * 録画そのものには影響しない。
	 *
	 * <p>パーティクルは少しだけためて **まとめて1枠** で書く。1件ずつ書くとそのたびに
	 * パケットのかたまりが切れて、圧縮が効かなくなる＋書き込みが詰まるため。
	 */
	public void recordLocal(int subtype, byte[] data) {
		if (this.stopping || data == null || data.length == 0) {
			return;
		}

		long now = System.currentTimeMillis();

		if (now - this.localWindowStartMs >= 1000L) {
			this.localWindowStartMs = now;
			this.localWindowCount = 0;
		}

		if (this.localWindowCount >= MAX_LOCAL_PER_SECOND) {
			return;
		}

		if (this.queuedBytes.get() > this.maxQueuedBytes) {
			return;
		}

		this.localWindowCount++;

		if (subtype == LocalEvents.TYPE_PARTICLE) {
			if (this.localBatch.isEmpty()) {
				this.localBatchFirstMs = now - this.startedAt;
			}

			this.localBatch.add(data);
			this.localBatchBytes += data.length;

			if (this.localBatch.size() >= LOCAL_BATCH_MAX_ENTRIES
					|| this.localBatchBytes >= LOCAL_BATCH_MAX_BYTES) {
				this.flushLocalBatch();
			}

			return;
		}

		long timeMs = now - this.startedAt;

		if (!this.offer(PacketTask.local(timeMs, subtype, data))) {
			return;
		}

		this.queuedBytes.addAndGet(data.length);
	}

	/**
	 * ためているパーティクルをまとめて1枠で書く（クライアントスレッドから呼ぶ）。
	 *
	 * <p>ためているあいだに後続のパケットが先に書かれることがあるが、ずれは高々
	 * ティック1回ぶん（数十ミリ秒）で、パーティクルは飾りなので見えない。
	 * 時刻の差分も 0 に丸められて自然に追いつく（壊れはしない）。
	 */
	public void flushLocalBatch() {
		if (this.localBatch.isEmpty()) {
			return;
		}

		List<byte[]> entries = new ArrayList<>(this.localBatch);
		this.localBatch.clear();
		this.localBatchBytes = 0;

		byte[] batched = LocalEvents.encodeBatch(entries);

		if (batched == null || batched.length == 0) {
			return;
		}

		if (!this.offer(PacketTask.local(this.localBatchFirstMs, LocalEvents.TYPE_PARTICLE_BATCH, batched))) {
			this.droppedCount.addAndGet(entries.size());
			return;
		}

		this.queuedBytes.addAndGet(batched.length);
	}

	/** しおりを付ける */
	public void addMarker(String name) {
		if (this.stopping) {
			return;
		}

		long timeMs = System.currentTimeMillis() - this.startedAt;
		String text = name == null || name.isBlank()
				? "Marker " + this.markerCount.incrementAndGet()
				: name;

		if (!this.offer(PacketTask.marker(timeMs, text))) {
			this.droppedCount.incrementAndGet();
		}
	}

	/** 録画を終えてファイルを閉じる（スレッドの終了を待つので、書き込みスレッドからは呼ばない） */
	public Stats finish() {
		this.stopping = true;
		// 写し待ちの最中なら、ためた分を先に流す（組立中のは捨てる）
		this.cancelSnapshotHold();
		// ためているパーティクルを先に書き切る（残すと最後の数十ミリ秒ぶんが消える）
		this.flushLocalBatch();
		long durationMs = System.currentTimeMillis() - this.startedAt;

		if (this.clip != null) {
			// クリップ方式は「押したときだけ残る」。録画を止めたら一時ファイルは消す
			this.clip.close();
			return new Stats(this.file, durationMs, this.clip.bytes(), this.packetCount.get(),
					this.droppedCount.get(), this.errorCount.get());
		}

		this.writer.finish(durationMs);

		return new Stats(this.file, durationMs, this.writer.bytesWritten(), this.writer.packetsWritten(),
				this.droppedCount.get(), this.errorCount.get());
	}

	/** いままでに書いた量（バイト） */
	public long bytesWritten() {
		return this.clip != null ? this.clip.bytes() : this.writer.bytesWritten();
	}

	/** 時間の上限に達したか（クリップ方式はずっと回し続けるので上限なし） */
	public boolean isOverDuration() {
		return this.clip == null && this.maxDurationMs > 0L
				&& System.currentTimeMillis() - this.startedAt >= this.maxDurationMs;
	}

	/** サイズの上限に達したか（クリップ方式は古い区間から捨てるので上限なし） */
	public boolean isLimitReached() {
		return this.clip == null && this.writer.isLimitReached();
	}

	// --- クリップ方式 ---

	public boolean isClipMode() {
		return this.clip != null;
	}

	/** 区間を開いて録り始める（クライアントスレッドから1回だけ） */
	public void startClip(MinecraftClient client) {
		if (this.clip != null) {
			this.clip.start(client);
		}
	}

	/** 毎ティックの区間の入れ替え */
	public void tickClip(MinecraftClient client) {
		if (this.clip != null) {
			this.clip.tick(client);
		}
	}

	/** いま保存できる長さ（ミリ秒） */
	/** クリップをまとめている最中か（数分かかることもあるので画面に出す） */
	public boolean isSavingClip() {
		return this.clip != null && this.clip.isSaving();
	}

	public long clipBufferedMillis() {
		return this.clip == null ? 0L : this.clip.bufferedMillis();
	}

	/** 残っている区間を1つにまとめて保存する（別スレッドで） */
	public void saveClip(MinecraftClient client) {
		if (this.clip != null) {
			this.clip.saveAsync(client);
		}
	}

	// --- 画面表示よう ---

	public Path file() {
		return this.file;
	}

	public long startedAtEpoch() {
		return this.startedAtEpoch;
	}

	public long elapsedMillis() {
		return System.currentTimeMillis() - this.startedAt;
	}

	public long packetCount() {
		return this.packetCount.get();
	}

	public long droppedCount() {
		return this.droppedCount.get();
	}

	// --- 内部 ---

	/** 種類を登録して、ファイルにも「この番号はこのパケット」と書き込むよう依頼する */
	private TypeInfo register(PacketType<?> type, boolean outbound) {
		String identifier;

		try {
			Identifier id = type.id();
			identifier = id == null ? String.valueOf(type) : id.toString();
		} catch (Throwable t) {
			return null;
		}

		synchronized (this.registerLock) {
			TypeInfo existing = this.types.get(type);

			if (existing != null) {
				return existing;
			}

			int index = this.nextTypeIndex.getAndIncrement();
			int direction = outbound ? ReplayFormat.DIRECTION_C2S : ReplayFormat.DIRECTION_S2C;
			this.types.put(type, new TypeInfo(index, isNoisy(identifier), direction, identifier));

			// 目安の置き場も番号に追従させる（ここはロックの中なので安全）
			if (index >= this.sizeHints.length) {
				int[] grown = new int[Math.max(index + 1, this.sizeHints.length * 2)];
				System.arraycopy(this.sizeHints, 0, grown, 0, this.sizeHints.length);
				this.sizeHints = grown;
			}

			// 本体より先に定義が書かれるように、同じキューに順番で積む
			if (!this.offer(PacketTask.type(index, direction, identifier))) {
				this.types.remove(type);
				return null;
			}

			return this.types.get(type);
		}
	}

	/** 通信の維持にしか使わないパケット（記録しても再生に影響しない） */
	private static boolean isNoisy(String identifier) {
		String lower = identifier.toLowerCase(Locale.ROOT);
		return lower.contains("keep_alive") || lower.contains("ping") || lower.contains("pong");
	}

	/**
	 * いままでに登録したパケットの種類。
	 *
	 * <p>クリップ方式では区間ごとにファイルが分かれるので、**区間の先頭にこれを全部書き直す**。
	 * こうしておくと、古い区間を消しても残った区間だけで再生できる。
	 */
	List<PacketTask> typeDefinitions() {
		List<TypeInfo> snapshot;

		synchronized (this.registerLock) {
			snapshot = new ArrayList<>(this.types.values());
		}

		snapshot.sort(Comparator.comparingInt(TypeInfo::index));
		List<PacketTask> result = new ArrayList<>(snapshot.size());

		for (TypeInfo info : snapshot) {
			result.add(PacketTask.type(info.index(), info.direction(), info.name()));
		}

		return result;
	}

	/** 積む先（クリップ方式なら区間、そうでなければファイル。写し待ちなら待機列） */
	boolean offer(PacketTask task) {
		if (this.stopping) {
			return false;
		}

		synchronized (this.holdLock) {
			if (this.holding) {
				return this.hold.add(task, this.maxQueuedBytes);
			}
		}

		return this.clip != null ? this.clip.offer(task) : this.writer.offer(task);
	}

	/** 録画の結果 */
	public record Stats(Path file, long durationMs, long bytes, long packets, long dropped, long errors) {
	}

	private record TypeInfo(int index, boolean noisy, int direction, String name) {
	}

	/**
	 * 写し待ちのあいだに来たタスクの待機列。順番どおりためて、あとで流す。
	 *
	 * <p>上限つき（書き込み待ちと同じ）。超えた分はためずに捨てる。
	 * 触るときは必ず {@link #holdLock} を取っていること。
	 */
	static final class SnapshotHold {
		private final List<PacketTask> tasks = new ArrayList<>();
		private long bytes;

		boolean add(PacketTask task, long maxBytes) {
			int size = task == null ? 0 : task.size();

			if (task == null || this.bytes + size > maxBytes) {
				return false;
			}

			this.tasks.add(task);
			this.bytes += size;
			return true;
		}

		List<PacketTask> drain() {
			List<PacketTask> out = new ArrayList<>(this.tasks);
			this.tasks.clear();
			this.bytes = 0;
			return out;
		}

		int count() {
			return this.tasks.size();
		}

		long bytes() {
			return this.bytes;
		}
	}
}
