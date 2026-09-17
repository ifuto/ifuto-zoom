package com.ifuto.replay.codec;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * IFZ1 と zstd・deflate の比べっこ。
 *
 * <p>本物のパケットに似せた bytes 列（見出し+種類ごとの中身・JSON・
 * 地形のもよう・雑音）を作って、縮みと速さを測る。CI で毎回走る。
 * 展開結果が合わなければ落とす（壊れた圧縮は出さない）。
 *
 * <p>結果は {@code ::notice::} でも出す（Checks から読めるように）。
 */
public final class IfzBench {
	private IfzBench() {
	}

	public static void main(String[] args) throws Exception {
		List<byte[]> blocks = generate(20260917L);
		long rawTotal = 0;

		for (byte[] block : blocks) {
			rawTotal += block.length;
		}

		System.out.println("[ifzbench] blocks=" + blocks.size() + " raw=" + rawTotal + " bytes");

		// 正しさ（間違っていたらここで落とす）
		roundtrip(blocks);
		streamingRoundtrip(blocks);
		System.out.println("[ifzbench] roundtrip OK");

		// 速さ（温め1・本番3）
		bench("ifz1-fast", blocks, 0);
		bench("ifz1-strong", blocks, 1);
		benchZstd(blocks);
		benchDeflate(blocks);
	}

	// --- 計測 ---

	private static void bench(String name, List<byte[]> blocks, int effort) throws Exception {
		List<byte[]> packed = new ArrayList<>(blocks.size());

		for (byte[] block : blocks) {
			packed.add(Ifz1.compressBlock(block, effort));
		}

		// 温め
		for (int i = 0; i < blocks.size(); i++) {
			byte[] back = Ifz1.decompressBlock(packed.get(i), blocks.get(i).length);

			if (!Arrays.equals(back, blocks.get(i))) {
				throw new IllegalStateException(name + " が壊れています");
			}
		}

		long packedTotal = 0;

		for (byte[] p : packed) {
			packedTotal += p.length;
		}

		long rawTotal = 0;

		for (byte[] b : blocks) {
			rawTotal += b.length;
		}

		long encNs = 0;
		long decNs = 0;

		for (int round = 0; round < 3; round++) {
			long start = System.nanoTime();

			for (byte[] block : blocks) {
				Ifz1.compressBlock(block, effort);
			}

			encNs += System.nanoTime() - start;
			start = System.nanoTime();

			for (int i = 0; i < blocks.size(); i++) {
				Ifz1.decompressBlock(packed.get(i), blocks.get(i).length);
			}

			decNs += System.nanoTime() - start;
		}

		report(name, rawTotal, packedTotal, encNs / 3, decNs / 3);
	}

	private static void benchZstd(List<byte[]> blocks) throws Exception {
		ZstdCompressor compressor = new ZstdCompressor();
		ZstdDecompressor decompressor = new ZstdDecompressor();
		List<byte[]> packed = new ArrayList<>(blocks.size());
		long rawTotal = 0;

		for (byte[] block : blocks) {
			rawTotal += block.length;
			byte[] out = new byte[compressor.maxCompressedLength(block.length)];
			int len = compressor.compress(block, 0, block.length, out, 0, out.length);
			packed.add(Arrays.copyOf(out, len));
			byte[] back = new byte[block.length];
			int done = decompressor.decompress(out, 0, len, back, 0, back.length);

			if (done != block.length || !Arrays.equals(back, block)) {
				throw new IllegalStateException("zstd の往復が壊れています");
			}
		}

		long packedTotal = 0;

		for (byte[] p : packed) {
			packedTotal += p.length;
		}

		long encNs = 0;
		long decNs = 0;

		for (int round = 0; round < 3; round++) {
			long start = System.nanoTime();

			for (byte[] block : blocks) {
				byte[] out = new byte[compressor.maxCompressedLength(block.length)];
				compressor.compress(block, 0, block.length, out, 0, out.length);
			}

			encNs += System.nanoTime() - start;
			start = System.nanoTime();

			for (int i = 0; i < blocks.size(); i++) {
				byte[] p = packed.get(i);
				decompressor.decompress(p, 0, p.length, new byte[blocks.get(i).length], 0, blocks.get(i).length);
			}

			decNs += System.nanoTime() - start;
		}

		report("zstd-3", rawTotal, packedTotal, encNs / 3, decNs / 3);
	}

	private static void benchDeflate(List<byte[]> blocks) throws Exception {
		List<byte[]> packed = new ArrayList<>(blocks.size());
		long rawTotal = 0;

		for (byte[] block : blocks) {
			rawTotal += block.length;
			Deflater deflater = new Deflater(6);
			deflater.setInput(block);
			deflater.finish();
			byte[] out = new byte[block.length + 64];
			int len = deflater.deflate(out);
			deflater.end();
			packed.add(Arrays.copyOf(out, len));
		}

		long packedTotal = 0;

		for (byte[] p : packed) {
			packedTotal += p.length;
		}

		long encNs = 0;
		long decNs = 0;

		for (int round = 0; round < 3; round++) {
			long start = System.nanoTime();

			for (byte[] block : blocks) {
				Deflater deflater = new Deflater(6);
				deflater.setInput(block);
				deflater.finish();
				deflater.deflate(new byte[block.length + 64]);
				deflater.end();
			}

			encNs += System.nanoTime() - start;
			start = System.nanoTime();

			for (int i = 0; i < blocks.size(); i++) {
				Inflater inflater = new Inflater();
				inflater.setInput(packed.get(i));
				inflater.inflate(new byte[blocks.get(i).length]);
				inflater.end();
			}

			decNs += System.nanoTime() - start;
		}

		report("deflate-6", rawTotal, packedTotal, encNs / 3, decNs / 3);
	}

	private static void report(String name, long raw, long packed, long encNs, long decNs) {
		double ratio = 100.0 * packed / raw;
		double encMBs = raw / (encNs / 1e9) / 1e6;
		double decMBs = raw / (decNs / 1e9) / 1e6;
		String line = String.format("%s ratio=%.1f%% enc=%.0fMB/s dec=%.0fMB/s",
				name, ratio, encMBs, decMBs);
		System.out.println("[ifzbench] " + line);
		System.out.println("::notice::IFZBENCH " + line);
	}

	// --- 正しさ ---

	private static void roundtrip(List<byte[]> blocks) throws Exception {
		for (int effort = 0; effort <= 1; effort++) {
			for (byte[] block : blocks) {
				byte[] back = Ifz1.decompressBlock(Ifz1.compressBlock(block, effort), block.length);

				if (!Arrays.equals(back, block)) {
					throw new IllegalStateException("IFZ1 の往復が壊れています");
				}
			}
		}

		// 端の形
		byte[][] edges = {new byte[0], new byte[]{1}, new byte[]{1, 2, 3},
				new byte[5], new byte[4096], new byte[65536], new byte[1048576]};

		for (byte[] edge : edges) {
			Arrays.fill(edge, (byte) 0xAB);

			for (int effort = 0; effort <= 1; effort++) {
				byte[] back = Ifz1.decompressBlock(Ifz1.compressBlock(edge, effort), edge.length);

				if (!Arrays.equals(back, edge)) {
					throw new IllegalStateException("IFZ1 の端が壊れています");
				}
			}
		}
	}

	private static void streamingRoundtrip(List<byte[]> blocks) throws Exception {
		Ifz1.Encoder encoder = new Ifz1.Encoder(1);
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		ByteArrayOutputStream plain = new ByteArrayOutputStream();

		for (byte[] block : blocks) {
			// 変な切り方で入れる
			int pos = 0;

			while (pos < block.length) {
				int step = Math.min(block.length - pos, 1 + (pos * 7919) % 5000);
				encoder.write(block, pos, step);
				pos += step;
			}

			wire.write(encoder.flush());
			plain.write(block, 0, block.length);
		}

		wire.write(encoder.finish());

		Ifz1.Decoder decoder = new Ifz1.Decoder();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] bytes = wire.toByteArray();
		int pos = 0;

		while (pos < bytes.length) {
			int step = Math.min(bytes.length - pos, 1 + (pos * 104729) % 7000);
			decoder.feed(bytes, pos, step, out);
			pos += step;
		}

		if (!Arrays.equals(out.toByteArray(), plain.toByteArray())) {
			throw new IllegalStateException("IFZ1 の流し込みが壊れています");
		}
	}

	// --- パケットに似せた bytes 列 ---

	private static List<byte[]> generate(long seed) {
		Random random = new Random(seed);
		List<byte[]> blocks = new ArrayList<>();

		for (int i = 0; i < 40; i++) {
			blocks.add(packetBlock(random, 8192 + random.nextInt(8192)));
		}

		for (int i = 0; i < 12; i++) {
			blocks.add(packetBlock(random, 65536 + random.nextInt(65536)));
		}

		for (int i = 0; i < 4; i++) {
			blocks.add(packetBlock(random, 262144 + random.nextInt(262144)));
		}

		blocks.add(packetBlock(random, 1048576));

		// 形が違う物（1本方式の相手）
		byte[] noise = new byte[65536];
		random.nextBytes(noise);
		blocks.add(noise);
		byte[] zeros = new byte[65536];
		blocks.add(zeros);
		return blocks;
	}

	/** 見出し3数+中身の繰り返し（本物の塊と同じ形） */
	private static byte[] packetBlock(Random random, int target) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(target + 1024);
		String[] names = {"Steve", "Alex", "Ifuto_mitai", "Herobrine", "Notch", "jeb_"};

		while (out.size() < target) {
			int kind = random.nextInt(100);
			int type;
			byte[] payload;

			if (kind < 45) {
				// 位置・向き（小さい・よく出る）
				type = random.nextInt(4);
				payload = new byte[8 + random.nextInt(24)];
				putVarInt(payload, 0, 1000000 + random.nextInt(1000));
				putVarInt(payload, 4, random.nextInt(360));

				for (int i = 8; i < payload.length; i++) {
					payload[i] = (byte) random.nextInt(16);
				}
			} else if (kind < 65) {
				// チャット・表示（JSON）
				type = 4 + random.nextInt(3);
				String text = "{\"text\":\"" + names[random.nextInt(names.length)] + "\",\"color\":\"white\","
						+ "\"extra\":[{\"text\":\"hello " + random.nextInt(10000) + "\"}]}";
				payload = text.getBytes(StandardCharsets.UTF_8);
			} else if (kind < 85) {
				// 地形のもよう（小さい数の並び・0の連なり）
				type = 7 + random.nextInt(3);
				payload = new byte[128 + random.nextInt(2048)];

				for (int i = 0; i < payload.length; i++) {
					int r = random.nextInt(100);
					payload[i] = (byte) (r < 60 ? 0 : r < 90 ? random.nextInt(8) : random.nextInt(256));
				}
			} else if (kind < 95) {
				// 持ち物・状態（短いバイト列）
				type = 10 + random.nextInt(5);
				payload = new byte[4 + random.nextInt(48)];
				random.nextBytes(payload);
				payload[0] = (byte) random.nextInt(4);
			} else {
				// 雑音（縮まない相手）
				type = 15 + random.nextInt(200);
				payload = new byte[16 + random.nextInt(128)];
				random.nextBytes(payload);
			}

			writeVarInt(out, random.nextInt(50));
			writeVarInt(out, type);
			writeVarInt(out, payload.length);
			out.write(payload, 0, payload.length);
		}

		return out.toByteArray();
	}

	private static void putVarInt(byte[] buf, int off, int value) {
		for (int i = 0; i < 4; i++) {
			buf[off + i] = (byte) (value >>> (i * 8));
		}
	}

	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}

		out.write(value);
	}
}
