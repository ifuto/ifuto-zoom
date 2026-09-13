package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
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
	private static final int INITIAL_BUFFER_SIZE = 64;

	private final Path file;
	private final long startedAtEpoch;
	private final long startedAt;
	private final ReplayConfig config;
	private final ReplayFileWriter writer;
	private final long maxQueuedBytes;
	private final long maxDurationMs;

	/** パケットの種類 → 番号と「除外対象か」 */
	private final ConcurrentHashMap<PacketType<?>, TypeInfo> types = new ConcurrentHashMap<>();
	private final AtomicInteger nextTypeIndex = new AtomicInteger();
	private final AtomicLong packetCount = new AtomicLong();
	private final AtomicLong droppedCount = new AtomicLong();
	private final AtomicLong errorCount = new AtomicLong();
	private final AtomicLong queuedBytes = new AtomicLong();
	private final AtomicInteger markerCount = new AtomicInteger();

	/** 種類の登録とエンコーダの作り直しはめったに起きないので、ここだけロックする */
	private final Object registerLock = new Object();

	private volatile ClientPlayNetworkHandler boundHandler;
	private volatile PacketEncoder encoder;
	private volatile boolean stopping;

	RecordingSession(Path file, ReplayConfig config, String mcVersion, String serverName, String playerName,
					 long startedAt) throws IOException {
		this.file = file;
		this.config = config;
		this.startedAt = startedAt;
		this.startedAtEpoch = startedAt;
		this.maxQueuedBytes = (long) config.queuedMegaBytes * 1024L * 1024L;
		this.maxDurationMs = config.maxDurationMinutes > 0 ? config.maxDurationMinutes * 60_000L : 0L;

		long maxBytes = config.maxFileSizeMb > 0 ? (long) config.maxFileSizeMb * 1024L * 1024L : 0L;

		this.writer = new ReplayFileWriter(file, mcVersion, serverName, playerName, startedAt,
				config.recordClientPackets, config.compression, config.indexIntervalMs, maxBytes,
				config.queuePackets, this.queuedBytes);
	}

	/** ファイルを開いて書き込みスレッドを開始する */
	public void start() {
		this.writer.start();
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

		if (outbound && !this.config.recordClientPackets) {
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

		ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer(INITIAL_BUFFER_SIZE);

		try {
			enc.encode(buffer, packet, outbound);
		} catch (Throwable t) {
			buffer.release();
			this.errorCount.incrementAndGet();
			return;
		}

		int size = buffer.readableBytes();
		int direction = outbound ? ReplayFormat.DIRECTION_C2S : ReplayFormat.DIRECTION_S2C;
		PacketTask task = PacketTask.packet(timeMs, info.index(), direction, buffer);

		if (!this.writer.offer(task)) {
			buffer.release();
			this.droppedCount.incrementAndGet();
			return;
		}

		this.queuedBytes.addAndGet(size);
		this.packetCount.incrementAndGet();
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

		if (!this.writer.offer(PacketTask.marker(timeMs, text))) {
			this.droppedCount.incrementAndGet();
		}
	}

	/** 録画を終えてファイルを閉じる（スレッドの終了を待つので、書き込みスレッドからは呼ばない） */
	public Stats finish() {
		this.stopping = true;
		long durationMs = System.currentTimeMillis() - this.startedAt;
		this.writer.finish(durationMs);

		return new Stats(this.file, durationMs, this.writer.bytesWritten(), this.writer.packetsWritten(),
				this.droppedCount.get(), this.errorCount.get());
	}

	/** 時間の上限に達したか */
	public boolean isOverDuration() {
		return this.maxDurationMs > 0L && System.currentTimeMillis() - this.startedAt >= this.maxDurationMs;
	}

	/** サイズの上限に達したか */
	public boolean isLimitReached() {
		return this.writer.isLimitReached();
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

	public long bytesWritten() {
		return this.writer.bytesWritten();
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
			this.types.put(type, new TypeInfo(index, isNoisy(identifier)));

			// 本体より先に定義が書かれるように、同じキューに順番で積む
			if (!this.writer.offer(PacketTask.type(index, direction, identifier))) {
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

	/** 録画の結果 */
	public record Stats(Path file, long durationMs, long bytes, long packets, long dropped, long errors) {
	}

	private record TypeInfo(int index, boolean noisy) {
	}
}
