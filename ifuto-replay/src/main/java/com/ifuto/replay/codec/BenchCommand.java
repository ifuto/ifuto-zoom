package com.ifuto.replay.codec;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.recording.BlockCodec;
import com.ifuto.replay.recording.ReplayFormat;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 実機の録画で IFZ1 と zstd を比べる（{@code /replaybench}）。
 *
 * <p>一番新しい録画のかたまりを1個ずつ取り出して、両方で縮めて戻して、
 * 縮みと速さを測る。重いので別スレッドでやる。かたまりは1個ずつしか
 * 持たない（メモリをためない）。
 */
public final class BenchCommand {
	/** 測るかたまりの上限 */
	private static final int MAX_BLOCKS = 256;

	private BenchCommand() {
	}

	public static void run(MinecraftClient client) {
		client.player.sendMessage(Text.translatable("ifuto-replay.command.bench.started"), false);

		Thread worker = new Thread(BenchCommand::work, "replay-bench");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	private static void work() {
		try {
			String result = measure();
			MinecraftClient client = MinecraftClient.getInstance();

			if (client != null) {
				client.execute(() -> {
					if (client.player != null) {
						client.player.sendMessage(Text.literal(result), false);
					}
				});
			}

			IfutoReplayClient.LOGGER.info("[ifuto-replay] 実機計測: {}", result);
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 実機計測に失敗しました", t);
			MinecraftClient client = MinecraftClient.getInstance();

			if (client != null) {
				client.execute(() -> {
					if (client.player != null) {
						client.player.sendMessage(
								Text.translatable("ifuto-replay.command.bench.failed"), false);
					}
				});
			}
		}
	}

	private static String measure() throws IOException {
		Path newest = newestRecording();

		if (newest == null) {
			return Text.translatable("ifuto-replay.command.bench.nofile").getString();
		}

		ZstdCompressor zstdOut = new ZstdCompressor();
		ZstdDecompressor zstdIn = new ZstdDecompressor();
		long rawTotal = 0;
		long ifzTotal = 0;
		long zstdTotal = 0;
		long ifzEncNs = 0;
		long ifzDecNs = 0;
		long zstdEncNs = 0;
		long zstdDecNs = 0;
		int blocks = 0;

		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(Files.newInputStream(newest), 1 << 16))) {
			// --- ヘッダ ---
			byte[] magic = new byte[ReplayFormat.MAGIC.length];
			in.readFully(magic);

			for (int i = 0; i < magic.length; i++) {
				if (magic[i] != ReplayFormat.MAGIC[i]) {
					throw new IOException("録画ではありません");
				}
			}

			int version = readVarInt(in);
			readVarInt(in);
			readString(in);
			in.readLong();
			readString(in);
			readString(in);

			for (;;) {
				int tag;

				try {
					tag = in.read();
				} catch (EOFException e) {
					break;
				}

				if (tag < 0 || tag == ReplayFormat.TAG_END || blocks >= MAX_BLOCKS) {
					break;
				}

				switch (tag) {
					case ReplayFormat.TAG_PACKET_TYPE -> {
						readVarInt(in);
						in.readByte();
						readString(in);
					}
					case ReplayFormat.TAG_PACKET -> {
						readVarInt(in);
						readVarInt(in);
						int length = readVarInt(in);
						int method = in.readByte();

						if (BlockCodec.isPacked(method)) {
							readVarInt(in);
						}

						skipExactly(in, length);
					}
					case ReplayFormat.TAG_MARKER -> {
						readVarInt(in);
						readString(in);
					}
					case ReplayFormat.TAG_INPUT -> {
						readVarInt(in);

						if (version >= 5) {
							in.readByte();
						}

						skipExactly(in, readVarInt(in));
					}
					case ReplayFormat.TAG_LOCAL -> {
						readVarInt(in);
						readVarInt(in);
						skipExactly(in, readVarInt(in));
					}
					case ReplayFormat.TAG_BLOCK -> {
						int rawLength = readVarInt(in);
						int method = in.readByte();
						int stored = BlockCodec.isPacked(method) ? readVarInt(in) : rawLength;
						byte[] packed = new byte[stored];
						in.readFully(packed);
						byte[] raw = BlockCodec.decompress(packed, rawLength, method);

						rawTotal += raw.length;
						blocks++;

						long start = System.nanoTime();
						byte[] ifz = Ifz1.compressBlock(raw, 1);
						ifzEncNs += System.nanoTime() - start;
						ifzTotal += Math.min(ifz.length, raw.length);

						start = System.nanoTime();
						byte[] back = Ifz1.decompressBlock(ifz, raw.length);
						ifzDecNs += System.nanoTime() - start;

						if (!Arrays.equals(back, raw)) {
							throw new IOException("IFZ1 の往復が合いません");
						}

						byte[] zstdWork = new byte[zstdOut.maxCompressedLength(raw.length)];
						start = System.nanoTime();
						int zstdLength = zstdOut.compress(raw, 0, raw.length,
								zstdWork, 0, zstdWork.length);
						zstdEncNs += System.nanoTime() - start;
						zstdTotal += Math.min(zstdLength, raw.length);

						byte[] zstdBack = new byte[raw.length];
						start = System.nanoTime();
						zstdIn.decompress(zstdWork, 0, zstdLength, zstdBack, 0, zstdBack.length);
						zstdDecNs += System.nanoTime() - start;
					}
					case ReplayFormat.TAG_INDEX -> {
						int count = readVarInt(in);

						for (int i = 0; i < count; i++) {
							readVarInt(in);
							readVarLong(in);
						}

						readVarLong(in);
					}
					case ReplayFormat.TAG_REGISTRIES -> {
						int packedLength = readVarInt(in);
						readVarInt(in);
						skipExactly(in, packedLength);
					}
					default -> throw new IOException("不明な札: " + tag);
				}
			}
		}

		if (blocks == 0 || rawTotal == 0) {
			return Text.translatable("ifuto-replay.command.bench.nofile").getString();
		}

		return newest.getFileName() + " (" + blocks + "塊): "
				+ String.format("IFZ1 %.1f%% %d/%dMB/s",
						100.0 * ifzTotal / rawTotal,
						mbs(rawTotal, ifzEncNs), mbs(rawTotal, ifzDecNs))
				+ String.format(" / zstd %.1f%% %d/%dMB/s",
						100.0 * zstdTotal / rawTotal,
						mbs(rawTotal, zstdEncNs), mbs(rawTotal, zstdDecNs));
	}

	private static long mbs(long bytes, long nanos) {
		return nanos <= 0 ? 0 : (long) (bytes / (nanos / 1e9) / 1e6);
	}

	private static Path newestRecording() throws IOException {
		Path directory = ReplayConfig.getSaveDirectory();

		if (!Files.isDirectory(directory)) {
			return null;
		}

		Path newest = null;

		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
				"*" + ReplayFormat.FILE_EXTENSION)) {
			for (Path file : files) {
				if (newest == null || Files.getLastModifiedTime(file)
						.compareTo(Files.getLastModifiedTime(newest)) > 0) {
					newest = file;
				}
			}
		}

		return newest;
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int result = 0;

		for (int shift = 0; shift < 35; shift += 7) {
			int b = in.readByte();
			result |= (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				return result;
			}
		}

		throw new IOException("数がおかしいです");
	}

	private static long readVarLong(DataInputStream in) throws IOException {
		long result = 0;

		for (int shift = 0; shift < 70; shift += 7) {
			int b = in.readByte();
			result |= (long) (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				return result;
			}
		}

		throw new IOException("数がおかしいです");
	}

	private static String readString(DataInputStream in) throws IOException {
		byte[] bytes = new byte[readVarInt(in)];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void skipExactly(DataInputStream in, int count) throws IOException {
		int skipped = 0;

		while (skipped < count) {
			int done = (int) in.skip(count - skipped);

			if (done <= 0) {
				in.readByte();
				done = 1;
			}

			skipped += done;
		}
	}
}
