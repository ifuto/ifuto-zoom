package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.audio.AudioTracks;
import com.ifuto.replay.config.ReplayConfig;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 保存済みクリップの切り出し（高速出力）。
 *
 * <p>再レンダはしない。パケットを複写するだけなので、速いし劣化もない。
 * 2回走査する（1回目: しおりと総時間の下見、2回目: 複写）。
 * どちらも1フレームずつ流して読むので、メモリは増えない。
 *
 * <p>先頭を落とすときは、直前の「写しがある場所（{@code __snap__}）」から
 * 前書きとして付ける。そこから再生できる状態にするため。
 * 再生側は {@code __start__} の位置へ自動で飛ぶので、前書きは見えない。
 * 範囲外のしおりは捨て、範囲内は時刻を付け直す。
 * {@code __snap__} は残すので、切った物をまた切れる。
 */
public final class ClipRemux {
	/** 残す範囲（ミリ秒。startMs 以上 endMs 未満） */
	public record Range(long startMs, long endMs) {
	}

	/** 進捗と中断（別スレッドから叩く。pass: 0=下見、1=複写、2=音声） */
	public interface Progress {
		void onProgress(int pass, long done, long total);

		default boolean isCancelled() {
			return false;
		}
	}

	/** 結果 */
	public record Result(Path output, long durationMs, long bytes, boolean audioOk, boolean truncated,
			boolean audioFailed) {
	}

	/** 進捗の通知（別スレッドから叩く。pass: 0=下見、1=複写、2=音声） */
	public static final int PASS_SCAN = 0;
	public static final int PASS_COPY = 1;
	public static final int PASS_AUDIO = 2;

	/** 中身1つの上限（壊れた数値を読んでも巨大配列を作らないため） */
	private static final int MAX_FRAME = 256 << 20;

	/** 目印を打つ間隔（再生側の既定と同じ5秒） */
	private static final long INDEX_INTERVAL_MS = 5000L;

	/** 音声の切り出しに待つ上限 */
	private static final long AUDIO_TIMEOUT_SECONDS = 300L;

	private ClipRemux() {
	}

	// --- 範囲の計算 ---

	/** 範囲を「0〜総時間に収める・重なりをまとめる・順番に並べる」 */
	public static List<Range> normalize(List<Range> ranges, long durationMs) {
		List<Range> clamped = new ArrayList<>();

		for (Range range : ranges) {
			long start = Math.max(0L, Math.min(range.startMs(), durationMs));
			long end = Math.max(0L, Math.min(range.endMs(), durationMs));

			if (end > start) {
				clamped.add(new Range(start, end));
			}
		}

		clamped.sort(Comparator.comparingLong(Range::startMs));

		List<Range> merged = new ArrayList<>();

		for (Range range : clamped) {
			if (!merged.isEmpty() && range.startMs() <= merged.get(merged.size() - 1).endMs()) {
				Range last = merged.remove(merged.size() - 1);
				merged.add(new Range(last.startMs(), Math.max(last.endMs(), range.endMs())));
			} else {
				merged.add(range);
			}
		}

		return merged;
	}

	/** 残す範囲から1つを引く（「カット」の計算）。重なった所が割れる */
	public static List<Range> subtract(List<Range> ranges, Range cut) {
		List<Range> result = new ArrayList<>();
		long cutStart = Math.min(cut.startMs(), cut.endMs());
		long cutEnd = Math.max(cut.startMs(), cut.endMs());

		for (Range range : ranges) {
			if (cutEnd <= range.startMs() || cutStart >= range.endMs()) {
				result.add(range);
				continue;
			}

			if (cutStart > range.startMs()) {
				result.add(new Range(range.startMs(), cutStart));
			}

			if (cutEnd < range.endMs()) {
				result.add(new Range(cutEnd, range.endMs()));
			}
		}

		return result;
	}

	// --- 本体 ---

	/**
	 * 切り出す。{@code source} は変えない（{@code output} が別ファイル）。
	 *
	 * @throws IOException 壊れていた・中断したなど（途中の出力は消す）
	 */
	public static Result remux(Path source, Path output, List<Range> ranges, Progress progress) throws IOException {
		if (source.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
			throw new IOException("入力と出力が同じです");
		}

		Scan scan = scan(source, progress);

		if (progress.isCancelled()) {
			throw new IOException("中断しました");
		}

		List<Range> kept = normalize(ranges, scan.durationMs);

		if (kept.isEmpty()) {
			throw new IOException("残す範囲がありません");
		}

		long firstStart = kept.get(0).startMs();
		long prefixStart = 0L;

		if (firstStart > 0L) {
			// 先頭を落とすときは、直前の写しから前書きとして付ける
			for (Marker marker : scan.markers) {
				if (ReplayFormat.SNAP_MARKER.equals(marker.name) && marker.timeMs <= firstStart) {
					prefixStart = Math.max(prefixStart, marker.timeMs);
				}
			}
		}

		Plan plan = new Plan(kept, firstStart, prefixStart);
		Copy copy = copy(source, output, scan, plan, progress);
		boolean audioOk = cutAudio(source, output, scan.durationMs, plan, progress);

		if (progress.isCancelled()) {
			deleteQuietly(output);
			AudioTracks.discard(output);
			throw new IOException("中断しました");
		}

		// 元に音声があったのに付けられなかったときだけ知らせる（元から無ければ正常）
		boolean audioFailed = AudioTracks.hasAny(source) && !audioOk;

		IfutoReplayClient.LOGGER.info("[ifuto-replay] 切り出しました: {} ({} ms, {} バイト, 音声 {})",
				output.getFileName(), copy.durationMs, copy.bytes, audioOk ? "あり" : "なし");
		return new Result(output, copy.durationMs, copy.bytes, audioOk, copy.truncated, audioFailed);
	}

	/**
	 * 「ここから見せる」位置を探す（書き出し画面の開始位置の既定に使う）。
	 *
	 * <p>見つかるのは前書きの中なので、少し読めば済む。予算を超えたら諦める。
	 *
	 * @return 位置（ミリ秒）。無ければ -1
	 */
	public static long findTrimStartMs(Path file, long budgetBytes) {
		try (Cursor cursor = new Cursor(file, budgetBytes, null, PASS_SCAN)) {
			while (true) {
				int tag = cursor.readTag();

				if (tag < 0) {
					return -1L;
				}

				try {
					switch (tag) {
						case ReplayFormat.TAG_PACKET -> cursor.skipPacketFrame();
						case ReplayFormat.TAG_BLOCK -> cursor.skipBlockFrame();
						case ReplayFormat.TAG_INPUT -> cursor.skipInputFrame();
						case ReplayFormat.TAG_LOCAL -> cursor.skipLocalFrame();
						case ReplayFormat.TAG_PACKET_TYPE -> cursor.skipTypeFrame();
						case ReplayFormat.TAG_REGISTRIES -> cursor.skipRegistriesFrame();
						case ReplayFormat.TAG_MARKER -> {
							cursor.timeMs += cursor.readVarInt();
							String name = cursor.readString();

							if (ReplayFormat.TRIM_MARKER.equals(name)) {
								return cursor.timeMs;
							}
						}
						// 目次まで来たら終わり（印は中身の中にしかない）
						case ReplayFormat.TAG_INDEX, ReplayFormat.TAG_END -> {
							return -1L;
						}
						default -> {
							return -1L;
						}
					}
				} catch (EOFException e) {
					return -1L;
				}
			}
		} catch (IOException | RuntimeException e) {
			// 画面を開くときに呼ぶので、絶対に落とさない
			return -1L;
		}
	}

	// --- 下見（1回目） ---

	private static final class Marker {
		final long timeMs;
		final String name;

		Marker(long timeMs, String name) {
			this.timeMs = timeMs;
			this.name = name;
		}
	}

	private static final class Scan {
		String mcVersion = "";
		String serverName = "";
		String playerName = "";
		long startedAt;
		int version = ReplayFormat.VERSION;
		int flags;
		boolean blocked;
		boolean shared;
		long durationMs;
		boolean hasRegistries;
		final List<Marker> markers = new ArrayList<>();
	}

	private static Scan scan(Path source, Progress progress) throws IOException {
		Scan scan = new Scan();

		try (Cursor cursor = new Cursor(source, Long.MAX_VALUE, progress, PASS_SCAN)) {
			scan.mcVersion = cursor.mcVersion;
			scan.serverName = cursor.serverName;
			scan.playerName = cursor.playerName;
			scan.startedAt = cursor.startedAt;
			scan.version = cursor.version;
			scan.flags = cursor.flags;
			scan.blocked = cursor.blocked;
			scan.shared = cursor.shared;

			try {
				while (true) {
					if (progress.isCancelled()) {
						throw new IOException("中断しました");
					}

					int tag = cursor.readTag();

					if (tag < 0) {
						break;
					}

					switch (tag) {
						case ReplayFormat.TAG_PACKET -> cursor.skipPacketFrame();
						case ReplayFormat.TAG_BLOCK -> cursor.skipBlockFrame();
						case ReplayFormat.TAG_INPUT -> cursor.skipInputFrame();
						case ReplayFormat.TAG_LOCAL -> cursor.skipLocalFrame();
						case ReplayFormat.TAG_PACKET_TYPE -> cursor.skipTypeFrame();
						case ReplayFormat.TAG_REGISTRIES -> {
							scan.hasRegistries = true;
							cursor.skipRegistriesFrame();
						}
						case ReplayFormat.TAG_MARKER -> {
							cursor.timeMs += cursor.readVarInt();
							scan.markers.add(new Marker(cursor.timeMs, cursor.readString()));
						}
						case ReplayFormat.TAG_INDEX -> {
							int count = cursor.readVarInt();

							if (count < 0 || count > (1 << 24)) {
								throw new TruncatedException();
							}

							for (int i = 0; i < count; i++) {
								cursor.readVarInt();
								cursor.readVarLong();
							}

							scan.durationMs = Math.max(scan.durationMs, cursor.readVarLong());
						}
						case ReplayFormat.TAG_END -> {
							scan.durationMs = Math.max(scan.durationMs, cursor.timeMs);
							return scan;
						}
						default -> throw new TruncatedException();
					}
				}
			} catch (EOFException | TruncatedException e) {
				// 途中で終わっている（末尾情報なし等）。読めた所までで続ける
			}

			scan.durationMs = Math.max(scan.durationMs, cursor.timeMs);
			return scan;
		}
	}

	// --- 複写（2回目） ---

	/** 出力の時間割。前書き → 範囲1 → 範囲2 … */
	private static final class Plan {
		final List<Range> kept;
		final long[] bases;
		final long firstStart;
		final long prefixStart;
		final long prefixLen;
		private int hint;

		Plan(List<Range> kept, long firstStart, long prefixStart) {
			this.kept = kept;
			this.firstStart = firstStart;
			this.prefixStart = prefixStart;
			this.prefixLen = firstStart - prefixStart;
			this.bases = new long[kept.size()];

			long base = this.prefixLen;

			for (int i = 0; i < kept.size(); i++) {
				this.bases[i] = base;
				base += kept.get(i).endMs() - kept.get(i).startMs();
			}
		}

		/**
		 * 元の時刻 → 出力の時刻。範囲外なら -1。
		 *
		 * <p>フレームは時刻順に来るので、前回の場所から探す（範囲が多くても遅くならない）。
		 */
		long map(long sourceMs) {
			if (sourceMs < this.prefixStart) {
				return -1L;
			}

			if (sourceMs < this.firstStart) {
				return sourceMs - this.prefixStart;
			}

			while (this.hint < this.kept.size() && sourceMs >= this.kept.get(this.hint).endMs()) {
				this.hint++;
			}

			if (this.hint < this.kept.size()) {
				Range range = this.kept.get(this.hint);

				if (sourceMs >= range.startMs()) {
					return this.bases[this.hint] + (sourceMs - range.startMs());
				}
			}

			return -1L;
		}
	}

	private static final class Copy {
		long durationMs;
		long bytes;
		boolean truncated;
	}

	private static Copy copy(Path source, Path output, Scan scan, Plan plan, Progress progress) throws IOException {
		Copy copy = new Copy();
		int level = ReplayConfig.get().compression.deflateLevel();
		Inflater shared = scan.shared ? new Inflater() : null;

		try (Cursor cursor = new Cursor(source, Long.MAX_VALUE, progress, PASS_COPY);
				// 新規のみ（ある物を壊さない。名前の重なりは呼び手が避ける）
				OutputStream fileOut = new BufferedOutputStream(
						Files.newOutputStream(output, StandardOpenOption.CREATE_NEW), 1 << 16)) {
			ReplayDataOutput out = new ReplayDataOutput(fileOut);
			boolean recordsC2S = (scan.flags & ReplayFormat.FLAG_HAS_C2S) != 0;

			ReplayFileWriter.writeHeader(out, scan.mcVersion, scan.serverName, scan.playerName,
					scan.startedAt + plan.prefixStart, recordsC2S, scan.blocked);

			Emitter emitter = new Emitter(out, plan, level);

			try {
				while (true) {
					if (progress.isCancelled()) {
						throw new IOException("中断しました");
					}

					int tag = cursor.readTag();

					if (tag < 0) {
						break;
					}

					switch (tag) {
						case ReplayFormat.TAG_PACKET_TYPE -> copyTypeFrame(cursor, out);
						case ReplayFormat.TAG_PACKET -> copyPacket(cursor, emitter, shared, level);
						case ReplayFormat.TAG_BLOCK -> copyBlock(cursor, emitter, plan);
						case ReplayFormat.TAG_INPUT -> copyInput(cursor, emitter, plan, scan.version);
						case ReplayFormat.TAG_LOCAL -> copyLocal(cursor, emitter, plan);
						case ReplayFormat.TAG_MARKER -> copyMarker(cursor, emitter, plan);
						case ReplayFormat.TAG_REGISTRIES -> copyRegistries(cursor, out);
						case ReplayFormat.TAG_INDEX -> skipIndex(cursor);
						case ReplayFormat.TAG_END -> {
							emitter.finish();
							out.flush();
							copy.durationMs = emitter.lastOut;
							copy.bytes = out.position();
							return copy;
						}
						default -> throw new TruncatedException();
					}
				}
			} catch (EOFException | TruncatedException e) {
				copy.truncated = true;
			} finally {
				if (shared != null) {
					shared.end();
				}
			}

			emitter.finish();
			out.flush();
			copy.durationMs = emitter.lastOut;
			copy.bytes = out.position();
			return copy;
		} catch (Throwable t) {
			deleteQuietly(output);
			throw t;
		}
	}

	private static void copyTypeFrame(Cursor cursor, ReplayDataOutput out) throws IOException {
		int typeIndex = cursor.readVarInt();
		int direction = cursor.readByte();
		String name = cursor.readString();

		out.writeByte(ReplayFormat.TAG_PACKET_TYPE);
		out.writeVarInt(typeIndex);
		out.writeByte(direction);
		out.writeString(name);
	}

	private static void copyPacket(Cursor cursor, Emitter emitter, @Nullable Inflater shared, int level)
			throws IOException {
		PacketFrame frame = cursor.readPacketFrame(shared);
		long outT = emitter.plan.map(cursor.timeMs);

		if (outT < 0L) {
			return;
		}

		if (frame.method == ReplayFormat.METHOD_DEFLATE && frame.standalonePacked) {
			// 単独で完結した圧縮はそのまま写す（解きもしない）
			emitter.emitPacket(cursor.timeMs, outT, frame.typeIndex, frame.length,
					ReplayFormat.METHOD_DEFLATE, frame.rawLength, frame.payload);
		} else if (frame.method == ReplayFormat.METHOD_DEFLATE) {
			// 共有窓（古い形式）は展開済み。単独で完結するように圧縮し直す
			byte[] packed = deflate(frame.payload, level);

			if (packed != null) {
				emitter.emitPacket(cursor.timeMs, outT, frame.typeIndex, packed.length,
						ReplayFormat.METHOD_DEFLATE, frame.payload.length, packed);
			} else {
				emitter.emitPacket(cursor.timeMs, outT, frame.typeIndex, frame.payload.length,
						ReplayFormat.METHOD_RAW, frame.payload.length, frame.payload);
			}
		} else {
			emitter.emitPacket(cursor.timeMs, outT, frame.typeIndex, frame.length,
					ReplayFormat.METHOD_RAW, frame.length, frame.payload);
		}
	}

	private static void copyBlock(Cursor cursor, Emitter emitter, Plan plan) throws IOException {
		long beforeT = cursor.timeMs;
		BlockFrame block = cursor.readBlockFrame();
		List<Long> times;

		try {
			// まずは時刻だけ見る（中身の複写は作り直すときだけ。速い）
			times = walkInner(block.inner);
		} catch (IOException e) {
			throw new TruncatedException();
		}

		if (times.isEmpty()) {
			return;
		}

		long firstT = beforeT + times.get(0);
		long lastT = beforeT + times.get(times.size() - 1);
		cursor.timeMs = lastT;

		boolean allKept = true;
		boolean anyKept = false;
		long firstOut = -1L;
		long lastOut = -1L;

		for (long relativeMs : times) {
			long outT = plan.map(beforeT + relativeMs);

			if (outT < 0L) {
				allKept = false;
			} else {
				anyKept = true;

				if (firstOut < 0L) {
					firstOut = outT;
				}

				lastOut = outT;
			}
		}

		if (!anyKept) {
			return;
		}

		if (allKept && emitter.isContiguous(beforeT, firstT, firstOut)) {
			// 全部残って時刻もつながっている → バイト列ごと写す（速い）
			emitter.emitBlockVerbatim(block, firstT, firstOut, lastT, lastOut);
			return;
		}

		// 境界にかかった → 残す物だけ集めて作り直す
		List<BlockEntry> entries;

		try {
			entries = parseInner(block.inner);
		} catch (IOException e) {
			throw new TruncatedException();
		}

		emitter.emitBlockRebuilt(entries, beforeT, plan);
	}

	private static void copyInput(Cursor cursor, Emitter emitter, Plan plan, int version) throws IOException {
		cursor.timeMs += cursor.readVarInt();

		int subtype;
		byte[] data;

		if (version < 5) {
			// v4 以前は種類が中身の先頭にしかない。v5 の形に直して出す
			data = cursor.readBytes(cursor.readVarInt());
			subtype = data.length > 0 ? data[0] & 0xFF : 0;
		} else {
			subtype = cursor.readByte();
			data = cursor.readBytes(cursor.readVarInt());
		}

		long outT = plan.map(cursor.timeMs);

		if (outT < 0L) {
			return;
		}

		emitter.emitInput(cursor.timeMs, outT, subtype, data);
	}

	private static void copyLocal(Cursor cursor, Emitter emitter, Plan plan) throws IOException {
		cursor.timeMs += cursor.readVarInt();
		int subtype = cursor.readVarInt();
		byte[] data = cursor.readBytes(cursor.readVarInt());
		long outT = plan.map(cursor.timeMs);

		if (outT < 0L) {
			return;
		}

		emitter.emitLocal(cursor.timeMs, outT, subtype, data);
	}

	private static void copyMarker(Cursor cursor, Emitter emitter, Plan plan) throws IOException {
		cursor.timeMs += cursor.readVarInt();
		String name = cursor.readString();

		// 古い「ここから見せる」は捨てる（新しい物を付け直すので）
		if (ReplayFormat.TRIM_MARKER.equals(name)) {
			return;
		}

		long outT = plan.map(cursor.timeMs);

		if (outT < 0L) {
			return;
		}

		emitter.emitMarker(cursor.timeMs, outT, name);
	}

	private static void copyRegistries(Cursor cursor, ReplayDataOutput out) throws IOException {
		int packedLength = cursor.readVarInt();
		checkLength(packedLength);
		int rawLength = cursor.readVarInt();
		checkLength(rawLength);
		byte[] packed = cursor.readBytes(packedLength);

		out.writeByte(ReplayFormat.TAG_REGISTRIES);
		out.writeVarInt(packedLength);
		out.writeVarInt(rawLength);
		out.writeBytes(packed);
	}

	private static void skipIndex(Cursor cursor) throws IOException {
		int count = cursor.readVarInt();

		if (count < 0 || count > (1 << 24)) {
			throw new TruncatedException();
		}

		for (int i = 0; i < count; i++) {
			cursor.readVarInt();
			cursor.readVarLong();
		}

		cursor.readVarLong();
	}

	// --- 書き出し側 ---

	private static final class Emitter {
		private final ReplayDataOutput out;
		private final Plan plan;
		private final int level;
		private long lastOut;
		private long lastKeptT;
		private boolean hasLast;
		private boolean trimEmitted;
		private long keptFrames;
		private final List<long[]> indexEntries = new ArrayList<>();
		private long nextIndexTimeMs;

		Emitter(ReplayDataOutput out, Plan plan, int level) {
			this.out = out;
			this.plan = plan;
			this.level = level;
		}

		/**
		 * そのまま写してよいか（落とした物が1つも挟まっていないか）。
		 *
		 * <p>かたまりの中の先頭の刻みは「元の続き」になっているので、
		 * 1つでも落としていたら作り直さないと時刻がずれる。
		 */
		boolean isContiguous(long beforeT, long firstT, long firstOut) {
			return this.hasLast && beforeT == this.lastKeptT
					&& firstT - this.lastKeptT == firstOut - this.lastOut;
		}

		void emitPacket(long timeMs, long outT, int typeIndex, int length, int method, int rawLength,
				byte[] payload) throws IOException {
			this.maybeEmitTrim(outT);
			this.maybeIndex(outT);

			this.out.writeByte(ReplayFormat.TAG_PACKET);
			this.out.writeVarInt(clampDelta(outT - this.lastOut));
			this.out.writeVarInt(typeIndex);
			this.out.writeVarInt(length);
			this.out.writeByte(method);

			if (method == ReplayFormat.METHOD_DEFLATE) {
				this.out.writeVarInt(rawLength);
			}

			this.out.writeBytes(payload, length);
			this.afterKept(timeMs, outT);
		}

		void emitBlockVerbatim(BlockFrame block, long firstT, long firstOut, long lastT, long lastOut)
				throws IOException {
			this.maybeEmitTrim(firstOut);
			this.maybeIndex(firstOut);

			this.out.writeByte(ReplayFormat.TAG_BLOCK);
			this.out.writeVarInt(block.rawLength);
			this.out.writeByte(block.method);

			if (block.method == ReplayFormat.METHOD_DEFLATE) {
				this.out.writeVarInt(block.packedLength);
			}

			this.out.writeBytes(block.stored, block.stored.length);
			this.afterKept(lastT, lastOut);
		}

		void emitBlockRebuilt(List<BlockEntry> entries, long beforeT, Plan plan) throws IOException {
			ByteArrayOutputStream inner = new ByteArrayOutputStream(1 << 16);
			long firstT = -1L;
			long firstOut = -1L;
			long lastT = -1L;
			long lastOut = -1L;

			for (BlockEntry entry : entries) {
				long timeMs = beforeT + entry.relativeMs;
				long outT = plan.map(timeMs);

				if (outT < 0L) {
					continue;
				}

				if (firstOut < 0L) {
					firstT = timeMs;
					firstOut = outT;
					this.maybeEmitTrim(outT);
					this.maybeIndex(outT);
					writeVarIntTo(inner, clampDelta(outT - this.lastOut));
				} else {
					writeVarIntTo(inner, clampDelta(outT - lastOut));
				}

				writeVarIntTo(inner, entry.typeIndex);
				writeVarIntTo(inner, entry.payload.length);
				inner.write(entry.payload, 0, entry.payload.length);
				lastT = timeMs;
				lastOut = outT;
			}

			if (firstOut < 0L) {
				return;
			}

			byte[] raw = inner.toByteArray();
			byte[] packed = deflate(raw, this.level);

			this.out.writeByte(ReplayFormat.TAG_BLOCK);
			this.out.writeVarInt(raw.length);

			if (packed != null) {
				this.out.writeByte(ReplayFormat.METHOD_DEFLATE);
				this.out.writeVarInt(packed.length);
				this.out.writeBytes(packed);
			} else {
				this.out.writeByte(ReplayFormat.METHOD_RAW);
				this.out.writeBytes(raw);
			}

			this.afterKept(lastT, lastOut);
		}

		void emitInput(long timeMs, long outT, int subtype, byte[] data) throws IOException {
			this.maybeEmitTrim(outT);
			this.maybeIndex(outT);

			this.out.writeByte(ReplayFormat.TAG_INPUT);
			this.out.writeVarInt(clampDelta(outT - this.lastOut));
			this.out.writeByte(subtype);
			this.out.writeVarInt(data.length);
			this.out.writeBytes(data);
			this.afterKept(timeMs, outT);
		}

		void emitLocal(long timeMs, long outT, int subtype, byte[] data) throws IOException {
			this.maybeEmitTrim(outT);
			this.maybeIndex(outT);

			this.out.writeByte(ReplayFormat.TAG_LOCAL);
			this.out.writeVarInt(clampDelta(outT - this.lastOut));
			this.out.writeVarInt(subtype);
			this.out.writeVarInt(data.length);
			this.out.writeBytes(data);
			this.afterKept(timeMs, outT);
		}

		void emitMarker(long timeMs, long outT, String name) throws IOException {
			this.maybeEmitTrim(outT);
			this.maybeIndex(outT);
			this.writeMarker(name, outT - this.lastOut);
			this.afterKept(timeMs, outT);
		}

		/** 前書きを越えたら「ここから見せる」を置く（先頭を落としたときだけ） */
		private void maybeEmitTrim(long outT) throws IOException {
			if (this.trimEmitted || this.plan.prefixLen <= 0L || outT < this.plan.prefixLen) {
				return;
			}

			this.trimEmitted = true;
			this.writeMarker(ReplayFormat.TRIM_MARKER, this.plan.prefixLen - this.lastOut);
			this.lastOut = this.plan.prefixLen;
			this.lastKeptT = this.plan.firstStart;
		}

		private void maybeIndex(long outT) {
			if (!this.indexEntries.isEmpty() && outT < this.nextIndexTimeMs) {
				return;
			}

			this.indexEntries.add(new long[]{outT, this.out.position()});
			this.nextIndexTimeMs = outT + INDEX_INTERVAL_MS;
		}

		private void writeMarker(String name, long deltaMs) throws IOException {
			this.out.writeByte(ReplayFormat.TAG_MARKER);
			this.out.writeVarInt(clampDelta(deltaMs));
			this.out.writeString(name);
		}

		private void afterKept(long timeMs, long outT) {
			this.lastOut = outT;
			this.lastKeptT = timeMs;
			this.hasLast = true;
			this.keptFrames++;
		}

		void finish() throws IOException {
			if (this.keptFrames == 0L) {
				throw new IOException("範囲に内容がありません");
			}

			if (!this.trimEmitted && this.plan.prefixLen > 0L) {
				// 前書きしか無かった。末尾に印を置く
				this.trimEmitted = true;
				this.writeMarker(ReplayFormat.TRIM_MARKER, this.plan.prefixLen - this.lastOut);
				this.lastOut = this.plan.prefixLen;
			}

			long indexOffset = this.out.position();

			this.out.writeByte(ReplayFormat.TAG_INDEX);
			this.out.writeVarInt(this.indexEntries.size());

			for (long[] entry : this.indexEntries) {
				this.out.writeVarInt((int) entry[0]);
				this.out.writeVarLong(entry[1]);
			}

			this.out.writeVarLong(Math.max(0L, this.lastOut));
			this.out.writeByte(ReplayFormat.TAG_END);

			for (byte magic : ReplayFormat.MAGIC) {
				this.out.writeByte(magic);
			}

			this.out.writeFixedLong(indexOffset);
		}
	}

	private static int clampDelta(long delta) {
		return (int) Math.max(0L, Math.min(delta, Integer.MAX_VALUE));
	}

	/**
	 * 圧縮する。縮まなかった・圧縮しない設定なら null（そのまま書く）。
	 */
	private static byte @Nullable [] deflate(byte[] raw, int level) {
		if (level < 0 || raw.length == 0) {
			return null;
		}

		Deflater deflater = new Deflater(level);

		try {
			deflater.setInput(raw);
			deflater.finish();

			ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
			byte[] scratch = new byte[8192];

			while (!deflater.finished()) {
				int written = deflater.deflate(scratch);

				if (written > 0) {
					packed.write(scratch, 0, written);
				}
			}

			byte[] result = packed.toByteArray();
			return result.length < raw.length ? result : null;
		} finally {
			deflater.end();
		}
	}

	private static void writeVarIntTo(OutputStream out, int value) throws IOException {
		int remaining = value;

		while (true) {
			if ((remaining & ~0x7F) == 0) {
				out.write(remaining);
				return;
			}

			out.write((remaining & 0x7F) | 0x80);
			remaining >>>= 7;
		}
	}

	private static void checkLength(int length) throws IOException {
		if (length < 0 || length > MAX_FRAME) {
			throw new IOException("壊れています（不正な長さ: " + length + "）");
		}
	}

	// --- 音声（前書き込みの範囲を切ってつなぐ） ---

	private static boolean cutAudio(Path source, Path output, long durationMs, Plan plan, Progress progress) {
		Path minecraft = AudioTracks.minecraftTrack(source);
		Path voice = AudioTracks.voiceTrack(source);
		Path system = AudioTracks.systemTrack(source);
		boolean hasMinecraft = Files.isRegularFile(minecraft);
		boolean hasVoice = Files.isRegularFile(voice);
		boolean hasSystem = Files.isRegularFile(system);

		if (!hasMinecraft && !hasVoice && !hasSystem) {
			return true;
		}

		// 前書きも含める（映像とずれないように。再生は __start__ へ飛ぶ）
		List<Range> spans = new ArrayList<>();

		if (plan.prefixLen > 0L) {
			spans.add(new Range(plan.prefixStart, plan.firstStart));
		}

		spans.addAll(plan.kept);

		ReplayConfig config = ReplayConfig.get();
		List<Path[]> jobs = new ArrayList<>();

		if (hasMinecraft) {
			jobs.add(new Path[]{minecraft, AudioTracks.minecraftTrack(output)});
		}

		if (hasVoice) {
			jobs.add(new Path[]{voice, AudioTracks.voiceTrack(output)});
		}

		if (hasSystem) {
			jobs.add(new Path[]{system, AudioTracks.systemTrack(output)});
		}

		// 全部残すだけなら複写で済ませる（作り直さない）
		if (spans.size() == 1 && spans.get(0).startMs() <= 0L && spans.get(0).endMs() >= durationMs) {
			boolean copied = true;

			for (Path[] job : jobs) {
				try {
					Files.copy(job[0], job[1]);
				} catch (IOException e) {
					IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声を写せませんでした", e);
					copied = false;
				}
			}

			return copied;
		}

		boolean ok = true;
		int done = 0;

		for (Path[] job : jobs) {
			if (progress.isCancelled()) {
				return false;
			}

			progress.onProgress(PASS_AUDIO, done, jobs.size());

			if (!cutTrack(job[0], job[1], spans, config, progress)) {
				ok = false;
			}

			done++;
		}

		progress.onProgress(PASS_AUDIO, jobs.size(), jobs.size());
		return ok;
	}

	private static boolean cutTrack(Path input, Path output, List<Range> spans, ReplayConfig config,
			Progress progress) {
		List<String> args = new ArrayList<>();
		args.add("-y");
		args.add("-hide_banner");
		args.add("-loglevel");
		args.add("warning");

		for (Range span : spans) {
			args.add("-ss");
			args.add(seconds(span.startMs()));
			args.add("-t");
			args.add(seconds(span.endMs() - span.startMs()));
			args.add("-i");
			args.add(input.toAbsolutePath().toString());
		}

		if (spans.size() > 1) {
			StringBuilder filter = new StringBuilder();

			for (int i = 0; i < spans.size(); i++) {
				filter.append('[').append(i).append(":a]");
			}

			filter.append("concat=n=").append(spans.size()).append(":v=0:a=1");
			args.add("-filter_complex");
			args.add(filter.toString());
		}

		args.add("-c:a");
		args.add("libopus");
		args.add("-b:a");
		args.add(config.audioBitrateKbps + "k");
		args.add(output.toAbsolutePath().toString());

		List<String> command = new ArrayList<>();
		command.add(config.ffmpegPath);
		command.addAll(args);

		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			drain(process);

			// 少しずつ待つ（やめる・時間切れに気付くため）
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AUDIO_TIMEOUT_SECONDS);

			while (process.isAlive()) {
				if (progress.isCancelled() || System.nanoTime() >= deadline) {
					process.destroyForcibly();
					return false;
				}

				try {
					Thread.sleep(200L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					process.destroyForcibly();
					return false;
				}
			}

			return process.exitValue() == 0 && Files.isRegularFile(output);
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声を切れませんでした", e);
			return false;
		}
	}

	private static String seconds(long ms) {
		return String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0);
	}

	/** ffmpeg が詰まらないように、出力を読み捨てる */
	private static void drain(Process process) {
		Thread thread = new Thread(() -> {
			try (InputStream in = process.getInputStream()) {
				byte[] buffer = new byte[4096];

				while (in.read(buffer) > 0) {
					// 読むだけ
				}
			} catch (IOException ignored) {
				// 終わっただけで問題ない
			}
		}, "ifuto-replay-remux-audio-drain");

		thread.setDaemon(true);
		thread.start();
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 消せなくても害はない
		}
	}

	// --- 読み手（1フレームずつ流して読む） ---

	/** 途中で読めなくなった（末尾情報なし・未知の目印）。壊滅ではなく「ここまで」とする */
	private static final class TruncatedException extends IOException {
		private static final long serialVersionUID = 1L;

		TruncatedException() {
			super("truncated");
		}
	}

	/** 予算を超えた（__start__ 探しで使う。見つからなかったことにする） */
	private static final class BudgetExceededException extends IOException {
		private static final long serialVersionUID = 1L;

		BudgetExceededException() {
			super("budget exceeded");
		}
	}

	private static final class PacketFrame {
		int typeIndex;
		int length;
		int method;
		int rawLength;
		byte[] payload = new byte[0];
		boolean standalonePacked;
	}

	private static final class BlockFrame {
		int rawLength;
		int method;
		int packedLength;
		byte[] stored = new byte[0];
		byte[] inner = new byte[0];
	}

	private static final class BlockEntry {
		long relativeMs;
		int typeIndex;
		byte[] payload = new byte[0];
	}

	/** かたまりの中の時刻だけ見る（複写なし。そのまま写せるかの判定用） */
	private static List<Long> walkInner(byte[] inner) throws IOException {
		List<Long> times = new ArrayList<>();
		int pos = 0;
		long time = 0L;

		while (pos < inner.length) {
			int[] delta = readVarIntAt(inner, pos);
			time += delta[0];
			pos = delta[1];
			pos = readVarIntAt(inner, pos)[1];

			int[] length = readVarIntAt(inner, pos);
			pos = length[1];

			if (length[0] < 0 || length[0] > inner.length - pos) {
				throw new IOException("かたまりが途中で終わっています");
			}

			pos += length[0];
			times.add(time);
		}

		return times;
	}

	private static List<BlockEntry> parseInner(byte[] inner) throws IOException {
		List<BlockEntry> entries = new ArrayList<>();
		int pos = 0;
		long time = 0L;

		while (pos < inner.length) {
			int[] delta = readVarIntAt(inner, pos);
			time += delta[0];
			pos = delta[1];

			int[] type = readVarIntAt(inner, pos);
			pos = type[1];

			int[] length = readVarIntAt(inner, pos);
			pos = length[1];

			if (length[0] < 0 || length[0] > inner.length - pos) {
				throw new IOException("かたまりが途中で終わっています");
			}

			byte[] payload = new byte[length[0]];
			System.arraycopy(inner, pos, payload, 0, length[0]);
			pos += length[0];

			BlockEntry entry = new BlockEntry();
			entry.relativeMs = time;
			entry.typeIndex = type[0];
			entry.payload = payload;
			entries.add(entry);
		}

		return entries;
	}

	private static int[] readVarIntAt(byte[] raw, int pos) throws IOException {
		int result = 0;

		for (int shift = 0; shift < 35; shift += 7) {
			if (pos >= raw.length) {
				throw new IOException("かたまりが途中で終わっています");
			}

			int b = raw[pos++] & 0xFF;
			result |= (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				return new int[]{result, pos};
			}
		}

		throw new IOException("可変長intが壊れています");
	}

	private static final class Cursor implements Closeable {
		private final DataInputStream in;
		private final long totalBytes;
		private final long budgetBytes;
		private final @Nullable Progress progress;
		private final int pass;
		private final Inflater inflater = new Inflater();
		private long readBytes;
		private long lastReportBytes;
		private long timeMs;
		private int version = ReplayFormat.VERSION;
		private int flags;
		private String mcVersion = "";
		private long startedAt;
		private String serverName = "";
		private String playerName = "";
		private boolean blocked;
		private boolean shared;

		Cursor(Path file, long budgetBytes, @Nullable Progress progress, int pass) throws IOException {
			this.totalBytes = Files.size(file);
			this.budgetBytes = budgetBytes;
			this.progress = progress;
			this.pass = pass;
			DataInputStream opened = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));

			try {
				this.in = opened;
				this.readHeader();
			} catch (Throwable t) {
				try {
					opened.close();
				} catch (IOException ignored) {
					// 開けなかった物の後片付け
				}

				this.inflater.end();
				throw t;
			}
		}

		private void readHeader() throws IOException {
			byte[] magic = this.readBytes(ReplayFormat.MAGIC.length);

			for (int i = 0; i < magic.length; i++) {
				if (magic[i] != ReplayFormat.MAGIC[i]) {
					throw new IOException("録画ファイルではありません");
				}
			}

			this.version = this.readVarInt();

			if (this.version < 1 || this.version > ReplayFormat.VERSION) {
				throw new IOException("未対応のバージョンです: " + this.version);
			}

			this.flags = this.readVarInt();
			this.mcVersion = this.readString();
			this.startedAt = this.readFixedLong();
			this.serverName = this.readString();
			this.playerName = this.readString();
			this.blocked = (this.flags & ReplayFormat.FLAG_BLOCK_DEFLATE) != 0;
			this.shared = !this.blocked && (this.flags & ReplayFormat.FLAG_SHARED_DEFLATE) != 0;
		}

		/** 次の目印。きれいに終わっていたら -1 */
		int readTag() throws IOException {
			int tag = this.in.read();

			if (tag < 0) {
				return -1;
			}

			this.readBytes++;
			this.checkBudget();

			// 進捗は64KBごとに間引く（毎フレーム呼ぶとそれだけで遅くなる）
			if (this.progress != null && this.readBytes - this.lastReportBytes >= 65536L) {
				this.lastReportBytes = this.readBytes;
				this.progress.onProgress(this.pass, this.readBytes, this.totalBytes);
			}

			return tag;
		}

		PacketFrame readPacketFrame(@Nullable Inflater shared) throws IOException {
			this.timeMs += this.readVarInt();
			PacketFrame frame = new PacketFrame();
			frame.typeIndex = this.readVarInt();
			frame.length = this.readVarInt();
			checkLength(frame.length);
			frame.method = this.readByte();

			if (frame.method == ReplayFormat.METHOD_DEFLATE) {
				frame.rawLength = this.readVarInt();
				checkLength(frame.rawLength);
				byte[] packed = this.readBytes(frame.length);

				if (shared != null) {
					// 共有窓（古い形式）は順番どおりに送り続けないと解けない
					shared.setInput(packed);
					frame.payload = this.inflateInto(shared, frame.rawLength);
					frame.standalonePacked = false;
				} else {
					frame.payload = packed;
					frame.standalonePacked = true;
				}
			} else {
				frame.rawLength = frame.length;
				frame.payload = this.readBytes(frame.length);
				frame.standalonePacked = false;
			}

			return frame;
		}

		BlockFrame readBlockFrame() throws IOException {
			BlockFrame block = new BlockFrame();
			block.rawLength = this.readVarInt();
			checkLength(block.rawLength);
			block.method = this.readByte();

			if (block.method == ReplayFormat.METHOD_DEFLATE) {
				block.packedLength = this.readVarInt();
				checkLength(block.packedLength);
				block.stored = this.readBytes(block.packedLength);
				this.inflater.reset();
				this.inflater.setInput(block.stored);
				block.inner = this.inflateInto(this.inflater, block.rawLength);
			} else {
				block.packedLength = block.rawLength;
				block.stored = this.readBytes(block.rawLength);
				block.inner = block.stored;
			}

			return block;
		}

		void skipPacketFrame() throws IOException {
			this.timeMs += this.readVarInt();
			this.readVarInt();
			int length = this.readVarInt();
			checkLength(length);
			int method = this.readByte();

			if (method == ReplayFormat.METHOD_DEFLATE) {
				checkLength(this.readVarInt());
			}

			this.skipExactly(length);
		}

		void skipBlockFrame() throws IOException {
			int rawLength = this.readVarInt();
			checkLength(rawLength);
			int method = this.readByte();
			byte[] inner;

			if (method == ReplayFormat.METHOD_DEFLATE) {
				int packedLength = this.readVarInt();
				checkLength(packedLength);
				byte[] packed = this.readBytes(packedLength);
				this.inflater.reset();
				this.inflater.setInput(packed);
				inner = this.inflateInto(this.inflater, rawLength);
			} else {
				inner = this.readBytes(rawLength);
			}

			// 時刻だけ進める（中身は要らない）。壊れていたら「ここまで」にする
			try {
				int pos = 0;

				while (pos < inner.length) {
					int[] delta = readVarIntAt(inner, pos);
					this.timeMs += delta[0];
					pos = delta[1];
					pos = readVarIntAt(inner, pos)[1];

					int[] length = readVarIntAt(inner, pos);
					pos = length[1];

					if (length[0] < 0 || length[0] > inner.length - pos) {
						throw new TruncatedException();
					}

					pos += length[0];
				}
			} catch (IOException e) {
				throw new TruncatedException();
			}
		}

		void skipInputFrame() throws IOException {
			this.timeMs += this.readVarInt();

			if (this.version < 5) {
				this.skipExactly(this.readVarInt());
			} else {
				this.readByte();
				this.skipExactly(this.readVarInt());
			}
		}

		void skipLocalFrame() throws IOException {
			this.timeMs += this.readVarInt();
			this.readVarInt();
			this.skipExactly(this.readVarInt());
		}

		void skipTypeFrame() throws IOException {
			this.readVarInt();
			this.readByte();
			this.readString();
		}

		void skipRegistriesFrame() throws IOException {
			int packedLength = this.readVarInt();
			checkLength(packedLength);
			checkLength(this.readVarInt());
			this.skipExactly(packedLength);
		}

		private byte[] inflateInto(Inflater inflater, int rawLength) throws IOException {
			checkLength(rawLength);

			byte[] result = new byte[rawLength];
			int pos = 0;

			try {
				while (pos < rawLength) {
					int read = inflater.inflate(result, pos, rawLength - pos);

					if (read > 0) {
						pos += read;
						continue;
					}

					if (inflater.finished() || inflater.needsInput()) {
						throw new EOFException();
					}

					throw new IOException("圧縮データが壊れています");
				}
			} catch (DataFormatException e) {
				throw new IOException("圧縮データが壊れています", e);
			}

			return result;
		}

		private int readVarInt() throws IOException {
			int result = 0;

			for (int shift = 0; shift < 35; shift += 7) {
				int b = this.readByte();
				result |= (b & 0x7F) << shift;

				if ((b & 0x80) == 0) {
					return result;
				}
			}

			throw new IOException("可変長intが壊れています");
		}

		private long readVarLong() throws IOException {
			long result = 0L;

			for (int shift = 0; shift < 70; shift += 7) {
				int b = this.readByte();
				result |= (long) (b & 0x7F) << shift;

				if ((b & 0x80) == 0) {
					return result;
				}
			}

			throw new IOException("可変長longが壊れています");
		}

		private long readFixedLong() throws IOException {
			long result = 0L;

			for (int i = 0; i < 8; i++) {
				result = (result << 8) | (this.readByte() & 0xFF);
			}

			return result;
		}

		private String readString() throws IOException {
			int length = this.readVarInt();

			if (length < 0 || length > (1 << 20)) {
				throw new IOException("文字が壊れています");
			}

			byte[] bytes = this.readBytes(length);
			return new String(bytes, StandardCharsets.UTF_8);
		}

		private int readByte() throws IOException {
			int value = this.in.readByte();
			this.readBytes++;
			this.checkBudget();
			return value;
		}

		private byte[] readBytes(int length) throws IOException {
			checkLength(length);

			byte[] bytes = new byte[length];
			this.in.readFully(bytes);
			this.readBytes += length;
			this.checkBudget();
			return bytes;
		}

		private void skipExactly(int count) throws IOException {
			checkLength(count);

			int skipped = 0;

			while (skipped < count) {
				int read = this.in.skipBytes(count - skipped);

				if (read <= 0) {
					throw new EOFException();
				}

				skipped += read;
			}

			this.readBytes += count;
			this.checkBudget();
		}

		private void checkBudget() throws IOException {
			if (this.readBytes > this.budgetBytes) {
				throw new BudgetExceededException();
			}
		}

		@Override
		public void close() throws IOException {
			try {
				this.in.close();
			} finally {
				this.inflater.end();
			}
		}
	}
}
