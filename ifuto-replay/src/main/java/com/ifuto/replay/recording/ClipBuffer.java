package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.audio.AudioTracks;
import com.ifuto.replay.config.ReplayConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import org.jspecify.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * クリップ方式（Medal みたいな「さっきの数秒をあとから保存」）の受け持ち。
 *
 * <p>やっていることは単純で、
 * <ol>
 *     <li>録り始めから **短い区間（{@code clipSeconds} の4分の1）ごとにファイルを分けて** 書き続ける</li>
 *     <li>区間は **古い物から捨てる**（時間と容量の、先に来たほうで切る）</li>
 *     <li>「クリップを保存」が押されたら、**新しいほうから必要な区間だけをつなげて** 1つの .ifreplay にする</li>
 * </ol>
 *
 * <p>転がしておく量は「クリップの長さの1.5倍」。保存される長さは「設定した長さ〜そこに
 * 区間1つぶん足した長さ」になる（開始位置は必ず区間の先頭＝世界の写しのある所になるため）。
 *
 * <p>メモリは溜めない（書き込みスレッドがこまめにファイルへ移す）ので、
 * この方式でも「溜め込み」は起きない。ディスクを使うぶん、長く遊んでも平気。
 *
 * <p>区間の先頭には **その時点の世界の写し** を置く。こうしておくと
 * 区間の途中からでも「そこに世界がある状態」から再生できる（= つなげた結果が必ず再生できる）。
 */
final class ClipBuffer {
	/** 残す区間の数（転がしておく量 = クリップの長さの1.5倍になるように分ける） */
	private static final int KEEP_SEGMENTS = 6;

	/** 区間をいくつに分けるか（= 保存した長さが最大どれだけ伸びるか） */
	private static final int SEGMENT_DIVISOR = 4;

	/** 区間の最短（あまりに細切れになるのを防ぐ） */
	private static final long MIN_SEGMENT_MS = 5000L;

	/** 一時ファイルの下限（これより小さくは切り詰めない） */
	private static final long MIN_CACHE_BYTES = 256L * 1024L * 1024L;

	/** 上限を決めていないときの天井 */
	private static final long MAX_CACHE_BYTES = 16L * 1024L * 1024L * 1024L;

	/** 保存するときに、これだけは余分に空いていてほしい */
	private static final long SAVE_MARGIN_BYTES = 256L * 1024L * 1024L;

	private static final int COPY_BUFFER = 1 << 16;

	private final RecordingSession session;
	private final ReplayConfig config;
	private final Path cacheDir;
	private final String mcVersion;
	private final String serverName;
	private final String playerName;
	private final boolean recordsC2S;
	private final AtomicLong queuedBytes;

	/** 音声の区間（クリップを保存するたびに1つ増える。古い物も捨てない） */
	private final Deque<AudioChunk> audioChunks = new ArrayDeque<>();

	private int audioIndex;

	/** 古い順。末尾が「いま書いている区間」 */
	private final Deque<Segment> segments = new ArrayDeque<>();

	private @Nullable ReplayFileWriter writer;
	private @Nullable NbtCompound registries;
	private int segmentCounter;
	private long lastOpenAttemptMs;
	private long lastMeasureMs;

	/** 消した区間も含めた「これまでに書いた量」（速度の計算に使う） */
	private long writtenBytes;

	/** 実測の速度（バイト/分） */
	private long bytesPerMinute;
	private volatile boolean saving;
	private volatile boolean closed;

	ClipBuffer(RecordingSession session, ReplayConfig config, Path cacheDir, String mcVersion,
			   String serverName, String playerName, boolean recordsC2S, AtomicLong queuedBytes) {
		this.session = session;
		this.config = config;
		this.cacheDir = cacheDir;
		this.mcVersion = mcVersion;
		this.serverName = serverName;
		this.playerName = playerName;
		this.recordsC2S = recordsC2S;
		this.queuedBytes = queuedBytes;
	}

	/** 一時置き場を作って、1つめの区間を書き始める（クライアントスレッドから） */
	void start(MinecraftClient client) {
		try {
			Files.createDirectories(this.cacheDir);
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] クリップの一時置き場 {} を作れませんでした", this.cacheDir, e);
			return;
		}

		// 前回が途中で終わっていた（クラッシュ等）ときの残りを先に片付ける
		clearCache();
		// 置きっぱなしのクリップも、この機会に片付ける
		ClipCleanup.prune(this.config.clipKeepHours);

		// 音声は通しで録り続ける（止めると音が途切れるので）
		Path audio = this.audioBase(this.audioIndex++);

		if (RecordingManager.INSTANCE.startClipAudio(client, audio)) {
			this.audioChunks.addLast(new AudioChunk(audio, System.currentTimeMillis()));
		}
		this.openSegment();
		// 先頭に世界の写しを置く（これがあるので、この区間から単独で再生できる）
		this.session.captureSnapshot(client);
	}

	boolean offer(PacketTask task) {
		// まとめている最中は受け取らない（ほんの少しのあいだだけ）
		ReplayFileWriter current = this.writer;

		if (current == null || this.saving || this.closed) {
			return false;
		}

		return current.offer(task);
	}

	/** 毎ティック。区間の長さを過ぎていたら次へ移る */
	void tick(MinecraftClient client) {
		if (this.saving || this.closed) {
			return;
		}

		// 書き込む速さを測り直して、容量を切り詰める
		this.watch(System.currentTimeMillis());

		if (this.writer == null) {
			// 開けなかったとき（一時的にディスクが一杯など）は、少し待って開き直す
			long now = System.currentTimeMillis();

			if (now - this.lastOpenAttemptMs > 2000L) {
				this.lastOpenAttemptMs = now;
				this.openSegment();
			}

			return;
		}

		Segment current = this.segments.peekLast();

		if (current == null) {
			return;
		}

		if (this.session.elapsedMillis() - current.startMs < this.segmentSpanMs()) {
			return;
		}

		this.rotate(client);
	}

	/**
	 * 一定時間ごとに、書き込む速さを測り直して容量を切り詰める。
	 *
	 * <p>長さで切るだけだと「掘りまくり」のときに一時ファイルが膨らむので、
	 * **いまの速度から必要な量を逆算して** それも超えないようにする。
	 * 書き込み量そのものを抑えることは SSD の寿命にも効く。
	 */
	private void watch(long now) {
		if (now - this.lastMeasureMs < 5000L) {
			return;
		}

		this.lastMeasureMs = now;
		this.measure();
		this.trimBySize();
	}

	private void measure() {
		long minutes = Math.max(1L, this.session.elapsedMillis() / 60_000L);
		long total = this.writtenBytes;
		ReplayFileWriter current = this.writer;

		if (current != null) {
			total += current.bytesWritten();
		}

		this.bytesPerMinute = Math.max(1L, total / minutes);
	}

	/** 一時ファイルの上限（実測の速度から必要なぶんだけ） */
	private long maxCacheBytes() {
		double minutes = this.config.clipSeconds / 60.0;
		long wanted = (long) (this.bytesPerMinute * minutes * 1.5 * 1.25);
		long ceiling = this.config.clipBufferMb > 0
				? (long) this.config.clipBufferMb * 1024L * 1024L
				: MAX_CACHE_BYTES;

		// 空きは常に4分の1以上残す
		long free = freeBytes(ReplayConfig.getSaveDirectory());

		if (free > 0L) {
			ceiling = Math.min(ceiling, free / 4L);
		}

		return Math.max(MIN_CACHE_BYTES, Math.min(ceiling, wanted));
	}

	/** 上限を超えていたら、古い区間から消す（最低2つは残す） */
	private void trimBySize() {
		long limit = this.maxCacheBytes();

		while (this.segments.size() > 2 && this.bytes() > limit) {
			Segment oldest = this.segments.pollFirst();

			if (oldest == null) {
				return;
			}

			deleteAll(List.of(oldest));
		}
	}

	private static long size(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return 0L;
		}
	}

	private static long freeBytes(Path directory) {
		Path target = directory;

		try {
			while (target != null && !Files.exists(target)) {
				target = target.getParent();
			}

			return target == null ? 0L : Files.getFileStore(target).getUsableSpace();
		} catch (IOException e) {
			return 0L;
		}
	}

	/** いま保存できる長さ（ミリ秒） */
	long bufferedMillis() {
		Segment oldest = this.segments.peekFirst();

		if (oldest == null) {
			return 0L;
		}

		return Math.max(0L, this.session.elapsedMillis() - oldest.startMs);
	}

	long bytes() {
		long total = 0L;

		for (Segment segment : this.segments) {
			try {
				total += Files.size(segment.file);
			} catch (IOException ignored) {
				// 消えた直後など。足せなければ無視する
			}
		}

		return total;
	}

	/** つなげた結果に入れるレジストリ（録り始めに1回だけ渡される） */
	void setRegistries(NbtCompound nbt) {
		this.registries = nbt;
	}

	/** まとめ先の空きが足りない（途中で壊すより先に諦めるため） */
	static final class NoSpaceException extends IOException {
		private static final long serialVersionUID = 1L;

		final long neededBytes;

		NoSpaceException(long neededBytes) {
			super("not enough space for " + neededBytes + " bytes");
			this.neededBytes = neededBytes;
		}
	}

	/** 音声の区間（保存するたびに1つ増える。消さずに取っておく） */
	private static final class AudioChunk {
		private final Path base;
		private final long startMs;
		private long endMs;

		AudioChunk(Path base, long startMs) {
			this.base = base;
			this.startMs = startMs;
			this.endMs = startMs;
		}

		double seconds() {
			return Math.max(0.0, (this.endMs - this.startMs) / 1000.0);
		}
	}

	/**
	 * 残っている区間を1つの .ifreplay にまとめる。
	 *
	 * <p>重いので **別スレッドで** やる（ゲームを止めない）。
	 * 終わったらクライアントスレッドに戻して、新しい区間を作り直す。
	 */
	void saveAsync(MinecraftClient client) {
		if (this.saving || this.closed) {
			return;
		}

		this.saving = true;
		// 保存した時点までの音声を確定させる（音そのものは止めない → 次のクリップにも音が付く）
		List<AudioChunk> audio = this.rotateAudio(client);

		Thread thread = new Thread(() -> {
			Path saved = null;
			Throwable failure = null;

			try {
				saved = this.assemble(audio);
			} catch (Throwable t) {
				failure = t;
				IfutoReplayClient.LOGGER.error("[ifuto-replay] クリップを保存できませんでした", t);
			}

			Path result = saved;
			Throwable error = failure;
			client.execute(() -> this.afterSave(client, result, error));
		}, "ifuto-replay-clip-save");

		thread.setDaemon(true);
		thread.start();
	}

	/** 録画を終える。一時ファイルは全部消す */
	void close() {
		this.closed = true;

		for (AudioChunk chunk : this.audioChunks) {
			AudioTracks.discard(chunk.base);
		}

		this.audioChunks.clear();
		ReplayFileWriter current = this.writer;
		this.writer = null;

		if (current != null) {
			current.finish(this.session.elapsedMillis());
		}

		List<Segment> all = new ArrayList<>(this.segments);
		this.segments.clear();
		deleteAll(all);
	}

	// --- 中身 ---

	private long segmentSpanMs() {
		return Math.max(MIN_SEGMENT_MS, (long) this.config.clipSeconds * 1000L / SEGMENT_DIVISOR);
	}

	private void openSegment() {
		this.lastOpenAttemptMs = System.currentTimeMillis();
		Path file = this.cacheDir.resolve("clip-" + (this.segmentCounter++) + ".bin");
		long elapsed = this.session.elapsedMillis();

		try {
			ReplayFileWriter created = new ReplayFileWriter(file, this.config.compression,
					this.config.flushIntervalMs, this.config.queuePackets, this.queuedBytes, elapsed,
					this.session::typeDefinitions);
			created.start();
			this.writer = created;
			this.segments.addLast(new Segment(file, System.currentTimeMillis(), elapsed));
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] クリップの区間 {} を開けませんでした", file, e);
			this.writer = null;
		}
	}

	/** 区間を次へ移す（古い区間は別スレッドで閉じ、残す数を超えた分は消す） */
	private void rotate(MinecraftClient client) {
		ReplayFileWriter previous = this.writer;
		Segment closing = this.segments.peekLast();

		if (closing != null) {
			closing.endMs = this.session.elapsedMillis();
		}

		// 先に次を作る（ここから先に来たパケットは新しい区間へ入る）
		this.openSegment();
		// 新しい区間の先頭に世界の写しを置く
		this.session.captureSnapshot(client);

		if (previous != null) {
			long durationMs = closing == null ? 0L : closing.endMs - closing.startMs;

			// 閉じるのは待つ必要がないので別スレッドへ
			Thread closer = new Thread(() -> {
				previous.finish(durationMs);
				this.writtenBytes += previous.bytesWritten();
			}, "ifuto-replay-clip-close");
			closer.setDaemon(true);
			closer.start();
		}

		while (this.segments.size() > KEEP_SEGMENTS) {
			Segment oldest = this.segments.pollFirst();

			if (oldest != null) {
				deleteAll(List.of(oldest));
			}
		}
	}

	/** いまの区間を閉じて、選んだ区間を順番につなげる */
	private @Nullable Path assemble(List<AudioChunk> audio) throws IOException {
		ReplayFileWriter current = this.writer;
		this.writer = null;

		if (current != null) {
			long elapsed = this.session.elapsedMillis();
			current.finish(elapsed);
		}

		List<Segment> parts = this.window();

		if (parts.isEmpty()) {
			return null;
		}

		Segment last = parts.get(parts.size() - 1);
		last.endMs = Math.max(last.endMs, this.session.elapsedMillis());

		Path directory = ReplayConfig.getSaveDirectory();
		Files.createDirectories(directory);
		Path output = uniqueClip(directory, last.startEpoch);
		long durationMs = Math.max(0L, last.endMs - parts.get(0).startMs);
		long needed = 0L;

		for (Segment part : parts) {
			needed += size(part.file);
		}

		long free = freeBytes(directory);

		// 置き場が無いのに書き始めると、数GB の壊れたファイルが残るので先に諦める
		if (free > 0L && needed + SAVE_MARGIN_BYTES > free) {
			throw new NoSpaceException(needed);
		}

		try {
			try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output), COPY_BUFFER)) {
				ReplayDataOutput out = new ReplayDataOutput(stream);
				ReplayFileWriter.writeHeader(out, this.mcVersion, this.serverName, this.playerName,
						parts.get(0).startEpoch, this.recordsC2S);
				this.writeRegistries(out);

				for (Segment segment : parts) {
					this.copy(segment.file, out);
				}

				ReplayFileWriter.writeFooter(out, durationMs);
			}
		} catch (Throwable t) {
			// 途中まで書いた物は残さない（長いクリップでは数GB になる）
			delete(output);
			AudioTracks.discard(output);
			throw t;
		}

		// 使った区間だけ捨てる（窓の外にある古い区間は、まだ次のクリップには要らないので消してよい）
		this.segments.removeAll(parts);
		this.collectAudio(output, durationMs, audio);
		deleteAll(parts);
		return output;
	}

	/**
	 * 保存する区間を選ぶ（**新しいほうから**「クリップの長さ」ぶんだけ）。
	 *
	 * <p>開始位置は必ず区間の先頭（= 世界の写しがある所）になる。途中から始めると
	 * その時点の世界が再現できないため。
	 */
	private List<Segment> window() {
		long wanted = Math.max(MIN_SEGMENT_MS, (long) this.config.clipSeconds * 1000L);
		ArrayList<Segment> picked = new ArrayList<>(this.segments);
		ArrayList<Segment> result = new ArrayList<>();

		for (int i = picked.size() - 1; i >= 0; i--) {
			Segment segment = picked.get(i);
			result.add(0, segment);

			if (this.session.elapsedMillis() - segment.startMs >= wanted) {
				break;
			}
		}

		return result;
	}

	/**
	 * いまの音声の区間を閉じて、次を始める（**音そのものは止めない**）。
	 *
	 * @return 保存した時点までの音声の区間（古い順）
	 */
	private List<AudioChunk> rotateAudio(MinecraftClient client) {
		long now = System.currentTimeMillis();
		AudioChunk current = this.audioChunks.peekLast();

		if (current != null) {
			current.endMs = now;
		}

		Path next = this.audioBase(this.audioIndex++);
		boolean running = RecordingManager.INSTANCE.rotateClipAudio(client, next);

		if (running) {
			this.audioChunks.addLast(new AudioChunk(next, now));
		} else if (current != null && RecordingManager.INSTANCE.startClipAudio(client, next)) {
			// 落ちていた（ffmpeg が死んだ等）ときは、いちから録り直す
			this.audioChunks.clear();
			this.audioChunks.addLast(new AudioChunk(next, now));
		}

		return List.copyOf(this.audioChunks);
	}

	private Path audioBase(int index) {
		return this.cacheDir.resolve("clip-audio-" + index);
	}

	/**
	 * 音声を「いまのクリップぶん」だけ切り出して、クリップの隣に置く。
	 *
	 * <p>保存するたびに音声のファイルは分かれているので、**新しいほうから必要なぶん** を
	 * 集めてつなぐ。長さが合わないと絵と音がずれるので、いちばん古い区間は後ろだけを使う。
	 *
	 * <p>取れていなければ何もしない（音声なしのクリップとして残る）。
	 */
	private void collectAudio(Path clip, long durationMs, List<AudioChunk> chunks) {
		if (chunks.isEmpty()) {
			return;
		}

		String ffmpeg = ReplayConfig.get().ffmpegPath;
		double needed = Math.max(0.5, durationMs / 1000.0);
		Deque<AudioChunk> picked = new ArrayDeque<>();
		double covered = 0.0;

		for (int i = chunks.size() - 1; i >= 0; i--) {
			AudioChunk chunk = chunks.get(i);
			picked.addFirst(chunk);
			covered += chunk.seconds();

			if (covered >= needed) {
				break;
			}
		}

		// はみ出した分は、いちばん古い区間から捨てる（捨てきれないなら区間ごと外す）
		double excess = covered - needed;
		AudioChunk head = picked.peekFirst();

		while (head != null && picked.size() > 1 && excess >= head.seconds() - 0.05) {
			excess -= head.seconds();
			picked.pollFirst();
			head = picked.peekFirst();
		}

		double headKeep = 0.0;

		if (head != null) {
			headKeep = Math.min(head.seconds(), Math.max(0.1, head.seconds() - excess));
		}

		List<Path> targets = AudioTracks.allTracks(clip);

		for (int track = 0; track < targets.size(); track++) {
			List<Path> sources = new ArrayList<>();

			for (AudioChunk chunk : picked) {
				sources.add(AudioTracks.allTracks(chunk.base).get(track));
			}

			this.buildTrack(targets.get(track), sources, headKeep, ffmpeg);
		}
	}

	/** 1本の音声を作る（複数に分かれていたら、頭を切りそろえてからつなぐ） */
	private void buildTrack(Path target, List<Path> sources, double headKeep, String ffmpeg) {
		if (sources.isEmpty()) {
			return;
		}

		if (sources.size() == 1) {
			this.cut(sources.get(0), target, headKeep, ffmpeg);
			return;
		}

		Path temporary = this.cacheDir.resolve("head-" + target.getFileName());
		this.cut(sources.get(0), temporary, headKeep, ffmpeg);

		if (!Files.isRegularFile(temporary)) {
			// 頭を切りそろえられないと長さが合わずにずれるので、音声は諦める
			return;
		}

		List<Path> parts = new ArrayList<>();
		parts.add(temporary);
		parts.addAll(sources.subList(1, sources.size()));

		if (AudioTracks.concat(parts, target, ffmpeg)) {
			delete(temporary);
		} else {
			delete(temporary);
			delete(target);
		}
	}

	private static void cut(Path input, Path output, double seconds, String ffmpeg) {
		if (!Files.isRegularFile(input)) {
			return;
		}

		if (!AudioTracks.tail(input, output, seconds, ffmpeg)) {
			// 切り出せなくても本体（映像）は残る。音声だけ無かったことにする
			try {
				Files.deleteIfExists(output);
			} catch (IOException ignored) {
				// 消せなくても害はない
			}
		}
	}

	private void afterSave(MinecraftClient client, @Nullable Path saved, @Nullable Throwable failure) {
		this.saving = false;

		if (this.closed) {
			return;
		}

		// 保存したあとも録り続ける（音声は rotateAudio がすでに次を始めている）
		this.openSegment();
		this.session.captureSnapshot(client);
		RecordingManager.INSTANCE.notifyClipSaved(client, saved, failure);

		if (saved != null) {
			ClipCleanup.prune(this.config.clipKeepHours);
		}
	}

	private void writeRegistries(ReplayDataOutput out) throws IOException {
		NbtCompound nbt = this.registries;

		if (nbt == null || nbt.isEmpty()) {
			return;
		}

		ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);

		try (DataOutputStream dataOut = new DataOutputStream(raw)) {
			NbtIo.writeCompound(nbt, dataOut);
		}

		byte[] rawBytes = raw.toByteArray();
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
		byte[] packed;

		try {
			deflater.setInput(rawBytes);
			deflater.finish();
			ByteArrayOutputStream packedOut = new ByteArrayOutputStream(Math.max(64, rawBytes.length / 2));
			byte[] scratch = new byte[8192];

			while (!deflater.finished()) {
				int written = deflater.deflate(scratch);

				if (written > 0) {
					packedOut.write(scratch, 0, written);
				}
			}

			packed = packedOut.toByteArray();
		} finally {
			deflater.end();
		}

		out.writeByte(ReplayFormat.TAG_REGISTRIES);
		out.writeVarInt(packed.length);
		out.writeVarInt(rawBytes.length);
		out.writeBytes(packed);
	}

	/** 区間ファイルをそのまま流し込む（中身は触らないので速い） */
	private void copy(Path file, ReplayDataOutput out) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}

		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[COPY_BUFFER];
			int read;

			while ((read = in.read(buffer)) > 0) {
				out.writeBytes(buffer, read);
			}
		}
	}

	private static void deleteAll(List<Segment> segments) {
		for (Segment segment : segments) {
			delete(segment.file);
			// 区間ごとの音声も一緒に消す
			delete(AudioTracks.minecraftTrack(segment.file));
			delete(AudioTracks.systemTrack(segment.file));
			delete(AudioTracks.voiceTrack(segment.file));
		}
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 消せなくても一時置き場なので放っておく
		}
	}

	/** 前回の残りを消す（消せなくても次の録り直しで上書きされるので気にしない） */
	private void clearCache() {
		try (java.util.stream.Stream<Path> files = Files.list(this.cacheDir)) {
			files.filter(path -> {
						String name = path.getFileName().toString();
						return name.startsWith("clip-") || name.startsWith("clip-audio");
					})
					.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						} catch (IOException ignored) {
							// 消せない物が残っても、上書きされるので害はない
						}
					});
		} catch (IOException ignored) {
			// 一覧できなければ何もしない
		}
	}

	/** 保存先でかぶらない名前を作る */
	private static Path uniqueClip(Path directory, long epochMillis) {
		LocalDateTime time = LocalDateTime.now();
		String base = String.format(Locale.ROOT, "clip_%04d-%02d-%02d_%02d-%02d-%02d",
				time.getYear(), time.getMonthValue(), time.getDayOfMonth(),
				time.getHour(), time.getMinute(), time.getSecond());

		Path file = directory.resolve(base + ReplayFormat.FILE_EXTENSION);
		int suffix = 2;

		while (Files.exists(file) && suffix < 1000) {
			file = directory.resolve(base + "_" + suffix + ReplayFormat.FILE_EXTENSION);
			suffix++;
		}

		return file;
	}

	/** 区間1つ分 */
	private static final class Segment {
		private final Path file;
		private final long startEpoch;
		private final long startMs;
		private long endMs;

		Segment(Path file, long startEpoch, long startMs) {
			this.file = file;
			this.startEpoch = startEpoch;
			this.startMs = startMs;
			this.endMs = startMs;
		}
	}
}
