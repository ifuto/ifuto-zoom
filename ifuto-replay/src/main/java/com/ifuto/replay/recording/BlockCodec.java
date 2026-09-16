package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.CompressionMode;
import io.airlift.compress.MalformedInputException;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * かたまりの圧縮・展開を1か所にまとめた物。
 *
 * <p>「速い」は deflate 1（いちばん安い）、「標準」以上は zstd（pure Java）。
 * zstd は deflate より速くて小さいので、軽さと小ささを両立できる。
 * 縮まなかったときは null を返し、呼び出し側はそのまま（RAW）書く。
 *
 * <p>古い deflate のかたまりも読める（格納方法は1かたまりごとに見分ける）。
 */
public final class BlockCodec {
	private static final ThreadLocal<ZstdCompressor> ZSTD_OUT =
			ThreadLocal.withInitial(ZstdCompressor::new);
	private static final ThreadLocal<ZstdDecompressor> ZSTD_IN =
			ThreadLocal.withInitial(ZstdDecompressor::new);
	private static final ThreadLocal<Deflater> DEFLATE_OUT =
			ThreadLocal.withInitial(Deflater::new);
	private static final ThreadLocal<byte[]> SCRATCH =
			ThreadLocal.withInitial(() -> new byte[32768]);

	private BlockCodec() {
	}

	/** 圧縮されている格納方法か（長さがもう1個付いてくる物） */
	public static boolean isPacked(int method) {
		return method == ReplayFormat.METHOD_DEFLATE || method == ReplayFormat.METHOD_ZSTD;
	}

	/**
	 * かたまり1個を圧縮する。
	 *
	 * @return 縮んだら圧縮後（縮まなかった・圧縮しない設定なら null）
	 */
	public static byte[] compress(byte[] raw, CompressionMode mode) {
		if (mode == null || mode == CompressionMode.OFF || raw.length == 0) {
			return null;
		}

		try {
			byte[] packed = mode.codecMethod() == ReplayFormat.METHOD_ZSTD
					? zstdPack(raw)
					: deflatePack(raw, mode.deflateLevel());

			if (packed == null || packed.length >= raw.length) {
				return null;
			}

			return packed;
		} catch (RuntimeException e) {
			// ここで落とすと録画が全部だめになるので、そのまま書くほうへ逃がす
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] かたまりを圧縮できなかったのでそのまま書きます", e);
			return null;
		}
	}

	/**
	 * かたまり1個を展開する。
	 *
	 * @param method 格納方法（RAW / DEFLATE / ZSTD）
	 */
	public static byte[] decompress(byte[] packed, int rawLength, int method) throws IOException {
		if (method == ReplayFormat.METHOD_RAW) {
			if (packed.length != rawLength) {
				throw new IOException("かたまりの長さが合いません");
			}

			return packed;
		}

		if (method == ReplayFormat.METHOD_ZSTD) {
			return zstdUnpack(packed, rawLength);
		}

		if (method == ReplayFormat.METHOD_DEFLATE) {
			return deflateUnpack(packed, rawLength);
		}

		throw new IOException("未知の圧縮方法です: " + method);
	}

	// --- zstd ---

	private static byte[] zstdPack(byte[] raw) {
		ZstdCompressor compressor = ZSTD_OUT.get();
		byte[] output = new byte[compressor.maxCompressedLength(raw.length)];
		int packedLength = compressor.compress(raw, 0, raw.length, output, 0, output.length);

		if (packedLength <= 0) {
			return null;
		}

		return Arrays.copyOf(output, packedLength);
	}

	private static byte[] zstdUnpack(byte[] packed, int rawLength) throws IOException {
		byte[] raw = new byte[rawLength];

		try {
			int unpacked = ZSTD_IN.get().decompress(packed, 0, packed.length, raw, 0, raw.length);

			if (unpacked != rawLength) {
				throw new IOException("かたまりの長さが合いません");
			}

			return raw;
		} catch (MalformedInputException | IllegalArgumentException e) {
			throw new IOException("圧縮が壊れています", e);
		}
	}

	// --- deflate（「速い」と古いかたまり用） ---

	private static byte[] deflatePack(byte[] raw, int level) {
		Deflater deflater = DEFLATE_OUT.get();
		deflater.reset();
		deflater.setLevel(level);
		deflater.setInput(raw);
		deflater.finish();

		ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
		byte[] scratch = SCRATCH.get();

		while (!deflater.finished()) {
			int written = deflater.deflate(scratch);

			if (written > 0) {
				packed.write(scratch, 0, written);
			}
		}

		return packed.toByteArray();
	}

	private static byte[] deflateUnpack(byte[] packed, int rawLength) throws IOException {
		byte[] raw = new byte[rawLength];
		Inflater inflater = new Inflater();

		try {
			inflater.setInput(packed);
			int read = 0;

			while (read < rawLength) {
				int n = inflater.inflate(raw, read, rawLength - read);

				if (n <= 0) {
					break;
				}

				read += n;
			}

			if (read != rawLength) {
				throw new IOException("圧縮が壊れています");
			}

			return raw;
		} catch (java.util.zip.DataFormatException e) {
			throw new IOException("圧縮が壊れています", e);
		} finally {
			inflater.end();
		}
	}
}
