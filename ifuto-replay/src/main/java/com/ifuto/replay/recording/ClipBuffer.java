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
 *     <li>録り始めから **短い区間（{@code clipSeconds} の半分）ごとにファイルを分けて** 書き続ける</li>
 *     <li>区間は **古い物から捨てる**（直近3つだけ残す）</li>
 *     <li>「クリップを保存」が押されたら、**残っている区間をつなげて1つの .ifreplay にする**</li>
 * </ol>
 *
 * <p>メモリは溜めない（書き込みスレッドがこまめにファイルへ移す）ので、
 * この方式でも「溜め込み」は起きない。ディスクを使うぶん、長く遊んでも平気。
 *
 * <p>区間の先頭には **その時点の世界の写し** を置く。こうしておくと
 * 区間の途中からでも「そこに世界がある状態」から再生できる（= つなげた結果が必ず再生できる）。
 */
final class ClipBuffer {
	/** 残す区間の数（いま + 直前2つ。窓の外側を1つ余分に持っておくため） */
	private static final int KEEP_SEGMENTS = 3;

	/** 区間の最短（あまりに細切れになるのを防ぐ） */
	private static final long MIN_SEGMENT_MS = 5000L;

	private static final int COPY_BUFFER = 1 << 16;

	private final RecordingSession session;
	private final ReplayConfig config;
	private final Path cacheDir;
	private final String mcVersion;
	private final String serverName;
	private final String playerName;
	private final boolean recordsC2S;
	private final AtomicLong queuedBytes;

	/** 音声の録り置き場（クリップ方式では通しで1本だけ録る） */
	private final Path audioBase;

	/** 古い順。末尾が「いま書いている区間」 */
	private final Deque<Segment> segments = new ArrayDeque<>();

	private @Nullable ReplayFileWriter writer;
	private @Nullable NbtCompound registries;
	private int segmentCounter;
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
		this.audioBase = cacheDir.resolve("clip-audio");
	}

	/** 一時置き場を作って、1つめの区間を書き始める（クライアントスレッドから） */
	void start(MinecraftClient client) {
		try {
			Files.createDirectories(this.cacheDir);
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] クリップの一時置き場 {} を作れませんでした", this.cacheDir, e);
			return;
		}

		// 音声は区間と違って通しで1本（止めると音が途切れるので）
		RecordingManager.INSTANCE.startClipAudio(client, this.audioBase);
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
		if (this.saving || this.closed || this.writer == null) {
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
		// いま録っている音声を先に閉じる（最後の区間の分を確定させるため）
		RecordingManager.INSTANCE.stopClipAudio(client);

		Thread thread = new Thread(() -> {
			Path saved = null;
			Throwable failure = null;

			try {
				saved = this.assemble();
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
		delete(AudioTracks.minecraftTrack(this.audioBase));
		delete(AudioTracks.systemTrack(this.audioBase));
		delete(AudioTracks.voiceTrack(this.audioBase));
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
		return Math.max(MIN_SEGMENT_MS, (long) this.config.clipSeconds * 1000L / 2L);
	}

	private void openSegment() {
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
			Thread closer = new Thread(() -> previous.finish(durationMs), "ifuto-replay-clip-close");
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

	/** いまの区間を閉じて、残っている区間を順番につなげる */
	private @Nullable Path assemble() throws IOException {
		ReplayFileWriter current = this.writer;
		this.writer = null;

		if (current != null) {
			long elapsed = this.session.elapsedMillis();
			current.finish(elapsed);
		}

		List<Segment> parts = new ArrayList<>(this.segments);

		if (parts.isEmpty()) {
			return null;
		}

		Segment last = parts.get(parts.size() - 1);
		last.endMs = Math.max(last.endMs, this.session.elapsedMillis());

		Path output = uniqueClip(ReplayConfig.getSaveDirectory(), last.startEpoch);

		try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output), COPY_BUFFER)) {
			ReplayDataOutput out = new ReplayDataOutput(stream);
			ReplayFileWriter.writeHeader(out, this.mcVersion, this.serverName, this.playerName,
					parts.get(0).startEpoch, this.recordsC2S);
			this.writeRegistries(out);

			for (Segment segment : parts) {
				this.copy(segment.file, out);
			}

			long durationMs = last.endMs - parts.get(0).startMs;
		ReplayFileWriter.writeFooter(out, durationMs);
		}

		this.segments.clear();
		this.collectAudio(output, durationMs);
		deleteAll(parts);
		return output;
	}

	/**
	 * 音声を「いちばん後ろのクリップぶん」だけ切り出して、クリップの隣に置く。
	 *
	 * <p>取れていなければ何もしない（音声なしのクリップとして残る）。
	 */
	private void collectAudio(Path clip, long durationMs) {
		String ffmpeg = ReplayConfig.get().ffmpegPath;
		double seconds = Math.max(0.5, durationMs / 1000.0);

		cut(AudioTracks.minecraftTrack(this.audioBase), AudioTracks.minecraftTrack(clip), seconds, ffmpeg);
		cut(AudioTracks.systemTrack(this.audioBase), AudioTracks.systemTrack(clip), seconds, ffmpeg);
		cut(AudioTracks.voiceTrack(this.audioBase), AudioTracks.voiceTrack(clip), seconds, ffmpeg);
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

		// 保存したあとも録り続ける（音声と区間は作り直す）
		RecordingManager.INSTANCE.startClipAudio(client, this.audioBase);
		this.openSegment();
		this.session.captureSnapshot(client);
		RecordingManager.INSTANCE.notifyClipSaved(client, saved, failure);
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
