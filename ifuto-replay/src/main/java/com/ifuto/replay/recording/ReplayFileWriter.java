package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.CompressionMode;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
import java.util.function.Supplier;
import java.util.zip.Deflater;
import org.jspecify.annotations.Nullable;

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

	/** かたまりを圧縮しはじめる大きさ（ためすぎても縮まないのでこれくらい） */
	private static final int BLOCK_TARGET_BYTES = 16 * 1024;

	/** かたまりに入れるパケットの上限（小さい物ばかりのときの保険） */
	private static final int BLOCK_MAX_PACKETS = 2048;

	/** かたまりを抱えたままにする時間の上限（落ちたときの被害をこれだけにする） */
	private static final long BLOCK_MAX_HOLD_MS = 1000L;

	private final BlockingQueue<PacketTask> queue;
	private final ReplayDataOutput out;
	private final CompressionMode compression;
	private final AtomicLong queuedBytes;
	private final AtomicLong writtenPackets = new AtomicLong();

	/** シーク用の目印: {経過ms, ファイル位置} */
	private final List<long[]> indexEntries = new ArrayList<>();
	private final int indexIntervalMs;
	private final long maxBytes;
	private final long flushIntervalMs;

	/** 区間ファイルのとき、書き始めに置く「パケットの種類」の定義 */
	private final @Nullable Supplier<List<PacketTask>> preamble;

	private final Thread thread;
	private volatile boolean running = true;
	private volatile boolean limitReached;
	private volatile Throwable failure;

	private long lastTimeMs;
	private long nextIndexTimeMs;
	private long lastFlushMs;
	private long bytesSinceFlush;
	private long requestedDurationMs;

	private Deflater deflater;
	private byte[] deflateScratch = new byte[8192];

	/** パケットをかたまりにまとめて圧縮するか */
	private final boolean blocked;

	/** ためているかたまりの中身 */
	private ByteArrayOutputStream blockBytes = new ByteArrayOutputStream(BLOCK_TARGET_BYTES + 1024);

	/** かたまりに書くための物 */
	private ReplayDataOutput blockOut = new ReplayDataOutput(this.blockBytes);

	/** かたまりに入っているパケットの数 */
	private int blockCount;

	/** かたまりの先頭のパケットの時刻（シーク用の目印に使う） */
	private long blockFirstTimeMs;

	/** かたまりを書き始めた時刻（時間でも区切る） */
	private long blockOpenedAtMs;

	/** 積める数（溜まりすぎの判定に使う） */
	private final int queueCapacity;

	/** ふつうの録画（先頭にヘッダを書く） */
	ReplayFileWriter(Path file, String mcVersion, String serverName, String playerName, long startedAt,
					 boolean recordsC2S, CompressionMode compression, int indexIntervalMs, long maxBytes,
					 long flushIntervalMs,
					 int queueCapacity, AtomicLong queuedBytes) throws IOException {
		this(file, compression, indexIntervalMs, maxBytes, flushIntervalMs, queueCapacity, queuedBytes,
				0L, null);

		writeHeader(this.out, mcVersion, serverName, playerName, startedAt, recordsC2S,
				compression.blocked());
	}

	/**
	 * クリップ用の「区間」ファイル。
	 *
	 * <p>ヘッダは書かない（あとで1個にまとめるときに書く）。かわりに
	 * **書き始めにパケットの種類の定義を全部置く**。こうしておくと
	 * どの区間からでも単独で再生できる（古い区間を消しても壊れない）。
	 */
	ReplayFileWriter(Path file, CompressionMode compression, long flushIntervalMs, int queueCapacity,
					 AtomicLong queuedBytes, long initialTimeMs,
					 Supplier<List<PacketTask>> preamble) throws IOException {
		this(file, compression, 0, 0L, flushIntervalMs, queueCapacity, queuedBytes, initialTimeMs, preamble);
	}

	private ReplayFileWriter(Path file, CompressionMode compression, int indexIntervalMs, long maxBytes,
							 long flushIntervalMs, int queueCapacity, AtomicLong queuedBytes,
							 long initialTimeMs, @Nullable Supplier<List<PacketTask>> preamble)
			throws IOException {
		this.queueCapacity = Math.max(64, queueCapacity);
		this.queue = new ArrayBlockingQueue<>(this.queueCapacity);
		this.compression = compression;
		this.indexIntervalMs = indexIntervalMs;
		this.maxBytes = maxBytes;
		this.flushIntervalMs = Math.max(100L, flushIntervalMs);
		this.queuedBytes = queuedBytes;
		this.preamble = preamble;
		this.blocked = compression.blocked();
		this.lastTimeMs = Math.max(0L, initialTimeMs);

		OutputStream stream = new BufferedOutputStream(Files.newOutputStream(file), BUFFER_SIZE);
		this.out = new ReplayDataOutput(stream);

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
			// 区間ファイルでは「いままでに出てきた種類」を先に書く（本体より必ず前になる）
			if (this.preamble != null) {
				List<PacketTask> preamble = this.preamble.get();

				if (preamble != null) {
					for (PacketTask task : preamble) {
						this.handle(task);
					}
				}
			}

			while (this.running) {
				PacketTask task = this.queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

				if (task != null) {
					this.handle(task);
				}

				// 溜め込まず、こまめにファイルへ移す（メモリに持つのは最低限だけ）
				this.flushIfNeeded();
			}

			// 停止指示が来てからも、溜まっている分は最後まで書く
			PacketTask task;

			while ((task = this.queue.poll()) != null) {
				this.handle(task);
			}

			// 溜まっているかたまりを最後に出す
			this.flushBlock();
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

	/**
	 * 一定時間ごとにファイルへ流す。
	 *
	 * <p>書き込みスレッドの中でやるのでゲーム側は止まらない。
	 * 「メモリ（キューとバッファ）に溜めない」のが目的。
	 */
	private void flushIfNeeded() {
		if (this.bytesSinceFlush <= 0L) {
			return;
		}

		long now = System.currentTimeMillis();

		if (now - this.lastFlushMs < this.flushIntervalMs) {
			return;
		}

		try {
			this.out.flush();
			this.bytesSinceFlush = 0L;
			this.lastFlushMs = now;
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ファイルへ移すときにエラー", e);
		}
	}

	// --- 中身 ---

	/** ファイルの先頭（クリップをまとめるときも同じ物を書く） */
	static void writeHeader(ReplayDataOutput out, String mcVersion, String serverName, String playerName,
							long startedAt, boolean recordsC2S, boolean blocked) throws IOException {
		for (byte magic : ReplayFormat.MAGIC) {
			out.writeByte(magic);
		}

		int flags = (recordsC2S ? ReplayFormat.FLAG_HAS_C2S : 0)
				| (blocked ? ReplayFormat.FLAG_BLOCK_DEFLATE : 0);
		out.writeVarInt(ReplayFormat.VERSION);
		out.writeVarInt(flags);
		out.writeString(mcVersion);
		out.writeFixedLong(startedAt);
		out.writeString(serverName);
		out.writeString(playerName);
	}

	private void handle(PacketTask task) throws IOException {
		try {
			this.handleInner(task);
		} finally {
			// 最後にファイルへ移したあとに書いた量（周期フラッシュの判定に使う）
			this.bytesSinceFlush += 1L + task.size();
		}
	}

	private void handleInner(PacketTask task) throws IOException {
		// パケット以外の記録はかたまりに入らないので、先に溜まっている分を出しておく
		// （順番が前後すると時刻が狂う）
		if (task.kind != PacketTask.KIND_PACKET) {
			this.flushBlock();
		}

		switch (task.kind) {
			case PacketTask.KIND_INPUT -> this.writeInput(task);
			case PacketTask.KIND_TYPE -> {
				this.out.writeByte(ReplayFormat.TAG_PACKET_TYPE);
				this.out.writeVarInt(task.typeIndex);
				this.out.writeByte(task.direction);
				this.out.writeString(task.text);
			}
			case PacketTask.KIND_PACKET -> this.writePacket(task);
			case PacketTask.KIND_LOCAL -> this.writeLocal(task);
			case PacketTask.KIND_REGISTRIES -> this.writeRegistries(task.nbt);
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

		if (this.blocked) {
			this.appendToBlock(task);
			return;
		}

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

	// --- かたまり（パケットをまとめて圧縮する） ---

	/**
	 * パケットをかたまりに積む。
	 *
	 * <p>1個ずつ圧縮しても数十バイトの相手では縮まないので、ある程度たまった所で
	 * ひとまとめにして圧縮する（{@link #flushBlock()}）。
	 * かたまり1個は **それだけで完結した deflate** にするので、読み飛ばしても
	 * 順番が違っても壊れない（クリップの区間をつなぐときに効く）。
	 */
	private void appendToBlock(PacketTask task) throws IOException {
		int length = task.size();

		if (this.blockCount == 0) {
			this.blockFirstTimeMs = task.timeMs;
			this.blockOpenedAtMs = System.currentTimeMillis();
		}

		this.blockOut.writeVarInt(this.takeDelta(task.timeMs));
		this.blockOut.writeVarInt(task.typeIndex);
		this.blockOut.writeVarInt(length);
		this.blockOut.writeBytes(task.payload, length);
		this.blockCount++;
		this.writtenPackets.incrementAndGet();
		this.queuedBytes.addAndGet(-length);

		if (this.blockCount >= BLOCK_MAX_PACKETS
				|| this.blockBytes.size() >= BLOCK_TARGET_BYTES
				|| System.currentTimeMillis() - this.blockOpenedAtMs >= BLOCK_MAX_HOLD_MS) {
			this.flushBlock();
		}
	}

	/** 溜まっているかたまりを圧縮して書き出す */
	private void flushBlock() throws IOException {
		if (this.blockCount <= 0) {
			return;
		}

		byte[] raw = this.blockBytes.toByteArray();

		// 先に空にしておく（途中で失敗しても同じ物を二度書かないように）
		this.blockBytes = new ByteArrayOutputStream(BLOCK_TARGET_BYTES + 1024);
		this.blockOut = new ReplayDataOutput(this.blockBytes);
		this.blockCount = 0;

		// シーク用の目印（かたまりの先頭の時刻と、書き出す位置）
		if (this.indexIntervalMs > 0
				&& (this.indexEntries.isEmpty() || this.blockFirstTimeMs >= this.nextIndexTimeMs)) {
			this.indexEntries.add(new long[]{this.blockFirstTimeMs, this.out.position()});
			this.nextIndexTimeMs = this.blockFirstTimeMs + this.indexIntervalMs;
		}

		byte[] packed = this.deflateBlock(raw);

		this.out.writeByte(ReplayFormat.TAG_BLOCK);
		this.out.writeVarInt(raw.length);

		if (packed.length < raw.length) {
			this.out.writeByte(ReplayFormat.METHOD_DEFLATE);
			this.out.writeVarInt(packed.length);
			this.out.writeBytes(packed);
		} else {
			// 縮まなかった（暗号みたいな中身など）ときはそのまま
			this.out.writeByte(ReplayFormat.METHOD_RAW);
			this.out.writeBytes(raw);
		}

		if (this.maxBytes > 0L && this.out.position() >= this.maxBytes) {
			this.limitReached = true;
		}
	}

	/**
	 * かたまり1個を、**それだけで完結した** deflate にする。
	 *
	 * <p>次のかたまりは reset() してから始めるので、かたまり同士は互いに独立。
	 * そのぶん少しだけ縮み方が悪くなるが（実測で 0.5% ほど）、読み飛ばしや
	 * つなぎ合わせが自由になる。
	 */
	private byte[] deflateBlock(byte[] raw) throws IOException {
		Deflater deflater = this.deflater;

		if (deflater == null) {
			deflater = new Deflater(this.level());
			this.deflater = deflater;
		} else {
			deflater.reset();
			// 溜まりはじめたら軽いレベルに落とす（取りこぼしのほうが困る）
			deflater.setLevel(this.level());
		}

		deflater.setInput(raw);
		deflater.finish();

		ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
		byte[] scratch = this.deflateScratch;

		while (!deflater.finished()) {
			int written = deflater.deflate(scratch);

			if (written > 0) {
				packed.write(scratch, 0, written);
			}
		}

		return packed.toByteArray();
	}

	/** いま使う deflate のレベル（溜まっているときは軽くする） */
	private int level() {
		return this.queue.size() * 4 >= this.queueCapacity
				? this.compression.fallbackLevel()
				: this.compression.deflateLevel();
	}

	/**
	 * クライアントの内側でだけ起きた出来事（パーティクルなど）。
	 *
	 * <p>中身は {@link LocalEvents} が組み立てた物。**パケットとして残らない物** なので、
	 * これを入れておかないと再生したときに何も起きない。
	 */
	private void writeLocal(PacketTask task) throws IOException {
		byte[] data = task.data;

		if (data == null || data.length == 0) {
			return;
		}

		this.out.writeByte(ReplayFormat.TAG_LOCAL);
		this.out.writeVarInt(this.takeDelta(task.timeMs));
		this.out.writeVarInt(task.typeIndex);
		this.out.writeVarInt(data.length);
		this.out.writeBytes(data);
	}

	/** 入力（マウス・キー・画面）の差分。中身は InputTracker が組み立てた物 */
	private void writeInput(PacketTask task) throws IOException {
		byte[] data = task.data;

		if (data == null || data.length == 0) {
			return;
		}

		this.out.writeByte(ReplayFormat.TAG_INPUT);
		this.out.writeVarInt(this.takeDelta(task.timeMs));
		this.out.writeVarInt(data.length);
		this.out.writeBytes(data);
	}

	/**
	 * 動的レジストリの写し（ファイルの先頭のほうに1回だけ）。
	 *
	 * <p>NBT のまま保存する。展開は再生時にしかしないので、録画中は直列化と圧縮だけ。
	 */
	private void writeRegistries(NbtCompound nbt) throws IOException {
		if (nbt == null || nbt.isEmpty()) {
			return;
		}

		ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);

		try (DataOutputStream dataOut = new DataOutputStream(raw)) {
			NbtIo.writeCompound(nbt, dataOut);
		}

		byte[] rawBytes = raw.toByteArray();
		byte[] packed = this.deflate(rawBytes);

		this.out.writeByte(ReplayFormat.TAG_REGISTRIES);
		this.out.writeVarInt(packed.length);
		this.out.writeVarInt(rawBytes.length);
		this.out.writeBytes(packed);
	}

	/** 目印なしの巻末（クリップをまとめるときに使う） */
	static void writeFooter(ReplayDataOutput out, long durationMs) throws IOException {
		long indexOffset = out.position();

		out.writeByte(ReplayFormat.TAG_INDEX);
		out.writeVarInt(0);
		out.writeVarLong(Math.max(0L, durationMs));
		out.writeByte(ReplayFormat.TAG_END);

		for (byte magic : ReplayFormat.MAGIC) {
			out.writeByte(magic);
		}

		out.writeFixedLong(indexOffset);
		out.flush();
	}

	private void writeFooter() throws IOException {
		// 「インデックスが何バイト目にあるか」を巻末に残す。
		// 一覧画面はファイルの末尾12バイトだけ見れば総時間を取れるので、本体を読み直さなくていい。
		long indexOffset = this.out.position();

		this.out.writeByte(ReplayFormat.TAG_INDEX);
		this.out.writeVarInt(this.indexEntries.size());

		for (long[] entry : this.indexEntries) {
			this.out.writeVarInt((int) entry[0]);
			this.out.writeVarLong(entry[1]);
		}

		this.out.writeVarLong(Math.max(this.lastTimeMs, this.requestedDurationMs));
		this.out.writeByte(ReplayFormat.TAG_END);

		for (byte magic : ReplayFormat.MAGIC) {
			this.out.writeByte(magic);
		}

		this.out.writeFixedLong(indexOffset);
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
		if (this.blocked) {
			return this.deflateBlock(input);
		}

		Deflater deflater = this.deflater;

		if (deflater == null) {
			deflater = new Deflater(this.compression.deflateLevel());
			this.deflater = deflater;
		} else if (!this.sharedWindow) {
			deflater.reset();
		}

		deflater.setInput(input);

		if (this.sharedWindow) {
			return this.deflateShared(deflater);
		}

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

	/**
	 * パケットをまたいで **辞書を共有** して圧縮する（いちばん効く部分）。
	 *
	 * <p>パケット1個はだいたい数十バイトなので、1個ずつ圧縮してもほとんど縮まない
	 * （むしろ膨らむこともある）。そこで reset() せずに直前のパケットを辞書として
	 * 使いまわし、**SYNC_FLUSH で区切り** を入れながら書く。
	 */
	private byte[] deflateShared(Deflater deflater) throws IOException {
		ByteArrayOutputStream packed = new ByteArrayOutputStream(64);
		byte[] scratch = this.deflateScratch;

		while (true) {
			int written = deflater.deflate(scratch, 0, scratch.length, Deflater.SYNC_FLUSH);

			if (written <= 0) {
				break;
			}

			packed.write(scratch, 0, written);
		}

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
