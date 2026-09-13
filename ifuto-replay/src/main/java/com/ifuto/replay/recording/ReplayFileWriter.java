package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.CompressionMode;
import io.netty.buffer.ByteBufUtil;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * 録画ファイルへの書き込みを専門にするスレッド。
 *
 * <p>軽くするための工夫:
 * <ul>
 *     <li>ゲーム/ネットワーク側は「パケットを直列化してキューに積む」だけ。ファイルIOと圧縮は全部このスレッド</li>
 *     <li>キューは {@code offer()} しかしない（満杯なら捨てる）。絶対にゲーム側を待たせない</li>
 *     <li>パケットの中身は Netty の ByteBuf のまま受け取り、ここで直接ストリームに流す（余計なコピーなし）</li>
 * </ul>
 */
final class ReplayFileWriter implements Runnable {
	private static final int BUFFER_SIZE = 1 << 16;
	private static final int POLL_TIMEOUT_MS = 200;

	private final BlockingQueue<PacketTask> queue;
	private final ReplayDataOutput out;
	private final CompressionMode compression;
	private final AtomicLong queuedBytes;
	private final AtomicLong writtenPackets = new AtomicLong();

	/** シーク用の目印: {経過ms, ファイル位置} */
	private final List<long[]> indexEntries = new ArrayList<>();
	private final int indexIntervalMs;
	private final long maxBytes;

	private final Thread thread;
	private volatile boolean running = true;
	private volatile boolean limitReached;
	private volatile Throwable failure;

	private long lastTimeMs;
	private long nextIndexTimeMs;
	private long requestedDurationMs;

	private Deflater deflater;
	private byte[] deflateScratch = new byte[8192];

	ReplayFileWriter(Path file, String mcVersion, String serverName, String playerName, long startedAt,
					 boolean recordsC2S, CompressionMode compression, int indexIntervalMs, long maxBytes,
					 int queueCapacity, AtomicLong queuedBytes) throws IOException {
		this.queue = new ArrayBlockingQueue<>(Math.max(64, queueCapacity));
		this.compression = compression;
		this.indexIntervalMs = indexIntervalMs;
		this.maxBytes = maxBytes;
		this.queuedBytes = queuedBytes;

		OutputStream stream = new BufferedOutputStream(Files.newOutputStream(file), BUFFER_SIZE);
		this.out = new ReplayDataOutput(stream);
		this.writeHeader(mcVersion, serverName, playerName, startedAt, recordsC2S);

		this.thread = new Thread(this, "ifuto-replay-writer");
		this.thread.setDaemon(true);
	}

	/** ファイルを開いて書き込みスレッドを開始する */
	void start() {
		this.thread.start();
	}

	/** 積む。満杯で積めなかったら false（呼び出し側で discard する） */
	boolean offer(PacketTask task) {
		return this.queue.offer(task);
	}

	/** 停止して、残りを全部書き切る。スレッドの終了を待つ */
	void finish(long durationMs) {
		this.running = false;
		this.requestedDurationMs = durationMs;

		try {
			this.thread.join(TimeUnit.SECONDS.toMillis(15));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (this.thread.isAlive()) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 書き込みスレッドが終わらなかったのでファイルを閉じられません");
			return;
		}

		// フッタ（インデックスと総時間）はスレッド側で書いているのでここでは何もしない
		if (this.failure != null) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 書き込み中にエラーが起きました", this.failure);
		}
	}

	@Override
	public void run() {
		try {
			while (this.running) {
				PacketTask task = this.queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

				if (task != null) {
					this.handle(task);
				}
			}

			// 停止指示が来てからも、溜まっている分は最後まで書く
			PacketTask task;

			while ((task = this.queue.poll()) != null) {
				this.handle(task);
			}

			this.writeFooter();
		} catch (Throwable t) {
			this.failure = t;
		} finally {
			try {
				this.out.flush();
				this.out.close();
			} catch (IOException e) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] ファイルを閉じるときにエラー", e);
			}

			if (this.deflater != null) {
				this.deflater.end();
				this.deflater = null;
			}
		}
	}

	// --- 中身 ---

	private void writeHeader(String mcVersion, String serverName, String playerName, long startedAt,
							 boolean recordsC2S) throws IOException {
		for (byte magic : ReplayFormat.MAGIC) {
			this.out.writeByte(magic);
		}

		this.out.writeVarInt(ReplayFormat.VERSION);
		this.out.writeVarInt(recordsC2S ? ReplayFormat.FLAG_HAS_C2S : 0);
		this.out.writeString(mcVersion);
		this.out.writeFixedLong(startedAt);
		this.out.writeString(serverName);
		this.out.writeString(playerName);
	}

	private void handle(PacketTask task) throws IOException {
		switch (task.kind) {
			case PacketTask.KIND_TYPE -> {
				this.out.writeByte(ReplayFormat.TAG_PACKET_TYPE);
				this.out.writeVarInt(task.typeIndex);
				this.out.writeByte(task.direction);
				this.out.writeString(task.text);
			}
			case PacketTask.KIND_PACKET -> this.writePacket(task);
			case PacketTask.KIND_MARKER -> {
				this.out.writeByte(ReplayFormat.TAG_MARKER);
				this.out.writeVarInt(this.takeDelta(task.timeMs));
				this.out.writeString(task.text);
			}
			default -> {
			}
		}
	}

	private void writePacket(PacketTask task) throws IOException {
		int length = task.size();

		// シーク用の目印（一定時間ごとに「この時間はこのファイル位置」を残す）
		if (this.indexIntervalMs > 0 && (this.indexEntries.isEmpty() || task.timeMs >= this.nextIndexTimeMs)) {
			this.indexEntries.add(new long[]{task.timeMs, this.out.position()});
			this.nextIndexTimeMs = task.timeMs + this.indexIntervalMs;
		}

		this.out.writeByte(ReplayFormat.TAG_PACKET);
		this.out.writeVarInt(this.takeDelta(task.timeMs));
		this.out.writeVarInt(task.typeIndex);

		if (length > 0 && this.compression.shouldCompress(length)) {
			byte[] raw = ByteBufUtil.getBytes(task.payload);
			byte[] packed = this.deflate(raw);

			if (packed.length < raw.length) {
				this.out.writeVarInt(packed.length);
				this.out.writeByte(ReplayFormat.METHOD_DEFLATE);
				this.out.writeVarInt(raw.length);
				this.out.writeBytes(packed);
			} else {
				this.out.writeVarInt(length);
				this.out.writeByte(ReplayFormat.METHOD_RAW);
				this.out.writeBytes(task.payload, length);
			}
		} else {
			this.out.writeVarInt(length);
			this.out.writeByte(ReplayFormat.METHOD_RAW);
			this.out.writeBytes(task.payload, length);
		}

		this.writtenPackets.incrementAndGet();
		this.queuedBytes.addAndGet(-length);

		if (this.maxBytes > 0L && this.out.position() >= this.maxBytes) {
			this.limitReached = true;
		}
	}

	private void writeFooter() throws IOException {
		this.out.writeByte(ReplayFormat.TAG_INDEX);
		this.out.writeVarInt(this.indexEntries.size());

		for (long[] entry : this.indexEntries) {
			this.out.writeVarInt((int) entry[0]);
			this.out.writeVarLong(entry[1]);
		}

		this.out.writeVarLong(Math.max(this.lastTimeMs, this.requestedDurationMs));
		this.out.writeByte(ReplayFormat.TAG_END);
		this.out.flush();
	}

	/** 前のフレームからの経過ミリ秒を取り出す（ついでに「直前の時刻」を進める） */
	private int takeDelta(long timeMs) {
		long delta = timeMs - this.lastTimeMs;
		this.lastTimeMs = timeMs;

		if (delta < 0L) {
			return 0;
		}

		return (int) Math.min(delta, Integer.MAX_VALUE);
	}

	private byte[] deflate(byte[] input) throws IOException {
		Deflater deflater = this.deflater;

		if (deflater == null) {
			deflater = new Deflater(this.compression.deflateLevel());
			this.deflater = deflater;
		} else {
			deflater.reset();
		}

		deflater.setInput(input);
		deflater.finish();

		ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(64, input.length / 2));
		byte[] scratch = this.deflateScratch;

		while (!deflater.finished()) {
			int written = deflater.deflate(scratch);

			if (written > 0) {
				packed.write(scratch, 0, written);
			}
		}

		// 次のためにリセット（中身はもう取り出してある）
		deflater.reset();
		return packed.toByteArray();
	}

	// --- 外から見える状態 ---

	long bytesWritten() {
		return this.out.position();
	}

	long packetsWritten() {
		return this.writtenPackets.get();
	}

	boolean isLimitReached() {
		return this.limitReached;
	}

	boolean hasFailed() {
		return this.failure != null && !this.running;
	}
}
