package com.ifuto.replay.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * パケットの塊に特化した圧縮の実験（Ifuto Zip 1。保存には使わない）。
 *
 * <p>実測で zstd に勝てなかったので既定は zstd のまま。ここは計測用に残す。
 * 塊の中身は「小さな見出し + 種類ごとの中身」の繰り返し。種類が混ざったまま
 * 汎用の圧縮にかけると似た物同士が離れてしまうので、先に仕分ける:
 * 見出しだけの列・よく出る種類ごとの列・それ以外の列。この列（レーン）ごとに
 * {@link Lzx1} をかける。見出しはほぼ同じ bytes の繰り返し、種類ごとの中身は
 * 同じ形の繰り返しなので、混ぜるよりよく縮む。
 *
 * <p>置き方:
 * {@code IFZ1(4)・旗(1)} に続いて、1本方式なら LZX1 をそのまま、
 * 仕分け方式ならレーン数を varint・レーン表・レーンの中身。
 * レーン表1件は {@code 種類varint(0=見出し・1=その他・それ以外は番号+2)・
 * 方式(1B: 0=そのまま・1=LZX1)・元の長さvarint・圧縮後の長さvarint}。
 */
public final class Ifz1 {
	private static final byte[] MAGIC = {'I', 'F', 'Z', '1'};
	private static final int FLAG_LANES = 0x01;
	private static final int FLAG_STORED = 0x02;
	private static final int CODEC_RAW = 0;
	private static final int CODEC_LZX1 = 1;
	private static final int KIND_HEADER = 0;
	private static final int KIND_MIXED = 1;
	/** 専用の列を持つ種類の数（見出し・その他は別） */
	private static final int HOT_TYPES = 15;
	/** 種類番号がこの範囲を超えた物はその他行き */
	private static final int TYPE_TABLE = 1024;
	/** 1塊の区切りの上限（超えたら1本方式に逃がす） */
	private static final int MAX_FRAMES = 65536;
	/** 受け渡しの辞書の大きさ（64KB） */
	private static final int HISTORY = 65536;

	private Ifz1() {
	}

	// --- 1塊 ---

	/**
	 * 1塊を圧縮する（縮まなくても例外にはしない。呼び出し側が比べる）。
	 *
	 * @param effort 0=速い・1=強い
	 */
	public static byte[] compressBlock(byte[] raw, int effort) {
		Frames frames = Frames.parse(raw);

		if (frames == null) {
			return singlePack(raw, effort);
		}

		return lanesPack(raw, frames, effort);
	}

	/**
	 * 1塊を展開する。
	 *
	 * @throws IOException 壊れている
	 */
	public static byte[] decompressBlock(byte[] packed, int rawLength) throws IOException {
		if (packed.length < 5 || packed[0] != MAGIC[0] || packed[1] != MAGIC[1]
				|| packed[2] != MAGIC[2] || packed[3] != MAGIC[3]) {
			throw new IOException("IFZ1 の印がありません");
		}

		int flags = packed[4] & 0xFF;

		if ((flags & FLAG_LANES) == 0) {
			if ((flags & FLAG_STORED) != 0) {
				if (packed.length - 5 != rawLength) {
					throw new IOException("IFZ1 の長さが合いません");
				}

				return Arrays.copyOfRange(packed, 5, packed.length);
			}

			byte[] raw = new byte[rawLength];
			Lzx1.decompress(packed, 5, packed.length - 5, null, 0, raw, 0, rawLength);
			return raw;
		}

		return lanesUnpack(packed, rawLength);
	}

	// --- 流し込み（ストリーミング） ---

	/**
	 * 少しずつ入れて、区切りごとに圧縮済みを出す。
	 *
	 * <p>{@link #flush()} のたびに「長さ varint + 1塊」が返る。
	 * 辞書は 64KB 引き継ぐので、細かく区切っても縮みはほぼ落ちない。
	 */
	public static final class Encoder {
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final byte[] history = new byte[HISTORY];
		private int historyLength;
		private final int effort;

		public Encoder(int effort) {
			this.effort = effort;
		}

		public void write(byte[] data, int off, int len) {
			this.buffer.write(data, off, len);
		}

		/** ためた分を1塊にして返す（空なら空） */
		public byte[] flush() {
			byte[] raw = this.buffer.toByteArray();
			this.buffer.reset();

			if (raw.length == 0) {
				return new byte[0];
			}

			byte[] packed = compressChunk(raw, this.history, this.historyLength, this.effort);
			pushHistory(this.history, this.historyLength, raw);
			this.historyLength = Math.min(HISTORY, this.historyLength + raw.length);

			ByteArrayOutputStream framed = new ByteArrayOutputStream(packed.length + 5);
			writeVarInt(framed, packed.length);
			framed.write(packed, 0, packed.length);
			return framed.toByteArray();
		}

		/** 終わり（終わりの印だけ返す） */
		public byte[] finish() {
			byte[] tail = this.flush();
			byte[] end = {(byte) 0};

			if (tail.length == 0) {
				return end;
			}

			byte[] both = Arrays.copyOf(tail, tail.length + 1);
			both[tail.length] = 0;
			return both;
		}
	}

	/**
	 * {@link Encoder} の逆。切れ端が来てもためて待つ。
	 */
	public static final class Decoder {
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final byte[] history = new byte[HISTORY];
		private int historyLength;
		private boolean finished;

		/** 入れた分の展開済みを {@code out} に足す */
		public void feed(byte[] data, int off, int len, ByteArrayOutputStream out) throws IOException {
			if (this.finished) {
				throw new IOException("IFZ1 は終わっています");
			}

			this.buffer.write(data, off, len);
			byte[] buf = this.buffer.toByteArray();
			int pos = 0;

			while (pos < buf.length) {
				int length = 0;
				int shift = 0;
				int start = pos;

				for (;;) {
					if (pos >= buf.length) {
						// 長さがまだ揃わないので待つ
						this.buffer.reset();
						this.buffer.write(buf, start, buf.length - start);
						return;
					}

					int b = buf[pos++] & 0xFF;
					length |= (b & 0x7F) << shift;

					if ((b & 0x80) == 0) {
						break;
					}

					shift += 7;

					if (shift > 28) {
						throw new IOException("IFZ1 の長さがおかしいです");
					}
				}

				if (length == 0) {
					this.finished = true;
					pos = buf.length;
					break;
				}

				if (length < 0) {
					throw new IOException("IFZ1 の長さがおかしいです");
				}

				if (length > buf.length - pos) {
					// 中身がまだ揃わないので待つ
					this.buffer.reset();
					this.buffer.write(buf, start, buf.length - start);
					return;
				}

				byte[] raw = decompressChunk(buf, pos, length, this.history, this.historyLength);
				pos += length;
				pushHistory(this.history, this.historyLength, raw);
				this.historyLength = Math.min(HISTORY, this.historyLength + raw.length);
				out.write(raw, 0, raw.length);
			}

			this.buffer.reset();
		}
	}

	// --- 1本方式 ---

	private static byte[] singlePack(byte[] raw, int effort) {
		byte[] work = new byte[Lzx1.maxPackedLength(raw.length)];
		int packedLength = Lzx1.compress(raw, 0, raw.length, null, 0, work, 0, work.length, effort);

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		if (packedLength < 0 || packedLength >= raw.length) {
			out.write(MAGIC, 0, MAGIC.length);
			out.write(FLAG_STORED);
			out.write(raw, 0, raw.length);
			return out.toByteArray();
		}

		out.write(MAGIC, 0, MAGIC.length);
		out.write(0);
		out.write(work, 0, packedLength);
		return out.toByteArray();
	}

	// --- 仕分け方式 ---

	private static byte[] lanesPack(byte[] raw, Frames frames, int effort) {
		int count = frames.count;
		long[] bytesByType = new long[TYPE_TABLE];
		boolean hasMixed = false;

		for (int i = 0; i < count; i++) {
			int type = frames.type[i];

			if (type >= 0 && type < TYPE_TABLE) {
				bytesByType[type] += frames.payloadLength[i];
			} else {
				hasMixed = true;
			}
		}

		// 量の多い順に専用の列を割り当てる
		int[] hot = new int[HOT_TYPES];
		Arrays.fill(hot, -1);
		int lanes = 0;

		for (int n = 0; n < HOT_TYPES; n++) {
			int best = -1;

			for (int t = 0; t < TYPE_TABLE; t++) {
				if (bytesByType[t] > 0 && (best < 0 || bytesByType[t] > bytesByType[best])) {
					best = t;
				}
			}

			if (best < 0) {
				break;
			}

			hot[n] = best;
			bytesByType[best] = 0;
			lanes++;
		}

		int[] laneOfType = new int[TYPE_TABLE];
		Arrays.fill(laneOfType, -1);

		for (int n = 0; n < lanes; n++) {
			laneOfType[hot[n]] = n + 1;
		}

		boolean useMixed = hasMixed;

		for (int i = 0; i < count && !useMixed; i++) {
			int type = frames.type[i];

			if (type < 0 || type >= TYPE_TABLE || laneOfType[type] < 0) {
				useMixed = true;
			}
		}

		int laneCount = 1 + lanes + (useMixed ? 1 : 0);
		int mixedLane = useMixed ? laneCount - 1 : -1;
		ByteArrayOutputStream[] builders = new ByteArrayOutputStream[laneCount];

		for (int i = 0; i < laneCount; i++) {
			builders[i] = new ByteArrayOutputStream();
		}

		for (int i = 0; i < count; i++) {
			builders[0].write(raw, frames.headerStart[i], frames.headerLength[i]);
			int type = frames.type[i];
			int lane = type >= 0 && type < TYPE_TABLE ? laneOfType[type] : -1;

			if (lane < 0) {
				lane = mixedLane;
			}

			builders[lane].write(raw, frames.payloadStart[i], frames.payloadLength[i]);
		}

		byte[][] laneRaw = new byte[laneCount][];
		byte[][] lanePacked = new byte[laneCount][];
		int[] laneCodec = new int[laneCount];

		for (int i = 0; i < laneCount; i++) {
			byte[] plain = builders[i].toByteArray();
			laneRaw[i] = plain;
			byte[] work = new byte[Lzx1.maxPackedLength(plain.length)];
			int packedLength = plain.length == 0 ? -1
					: Lzx1.compress(plain, 0, plain.length, null, 0, work, 0, work.length, effort);

			if (packedLength < 0 || packedLength >= plain.length) {
				laneCodec[i] = CODEC_RAW;
				lanePacked[i] = plain;
			} else {
				laneCodec[i] = CODEC_LZX1;
				lanePacked[i] = Arrays.copyOf(work, packedLength);
			}
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 64);
		out.write(MAGIC, 0, MAGIC.length);
		out.write(FLAG_LANES);
		writeVarInt(out, laneCount);

		for (int i = 0; i < laneCount; i++) {
			int kind;

			if (i == 0) {
				kind = KIND_HEADER;
			} else if (i == mixedLane) {
				kind = KIND_MIXED;
			} else {
				kind = hot[i - 1] + 2;
			}

			writeVarInt(out, kind);
			out.write(laneCodec[i]);
			writeVarInt(out, laneRaw[i].length);
			writeVarInt(out, lanePacked[i].length);
		}

		for (int i = 0; i < laneCount; i++) {
			out.write(lanePacked[i], 0, lanePacked[i].length);
		}

		return out.toByteArray();
	}

	private static byte[] lanesUnpack(byte[] packed, int rawLength) throws IOException {
		Cursor cursor = new Cursor(packed, 5);
		int laneCount = cursor.readVarInt();

		if (laneCount <= 0 || laneCount > 1 + HOT_TYPES + 1) {
			throw new IOException("IFZ1 の列の数がおかしいです");
		}

		int[] kind = new int[laneCount];
		int[] codec = new int[laneCount];
		int[] plainLength = new int[laneCount];
		int[] packedLength = new int[laneCount];

		for (int i = 0; i < laneCount; i++) {
			kind[i] = cursor.readVarInt();
			codec[i] = cursor.readByte();

			if (codec[i] != CODEC_RAW && codec[i] != CODEC_LZX1) {
				throw new IOException("IFZ1 の列の方式がおかしいです");
			}

			plainLength[i] = cursor.readVarInt();
			packedLength[i] = cursor.readVarInt();

			if (plainLength[i] < 0 || packedLength[i] < 0) {
				throw new IOException("IFZ1 の列の長さがおかしいです");
			}
		}

		byte[][] lanes = new byte[laneCount][];

		for (int i = 0; i < laneCount; i++) {
			byte[] data = cursor.readBytes(packedLength[i]);

			if (codec[i] == CODEC_RAW) {
				if (data.length != plainLength[i]) {
					throw new IOException("IFZ1 の列の長さが合いません");
				}

				lanes[i] = data;
			} else {
				byte[] plain = new byte[plainLength[i]];
				Lzx1.decompress(data, 0, data.length, null, 0, plain, 0, plain.length);
				lanes[i] = plain;
			}
		}

		// 見出しの列は1本きり
		int headerLane = -1;
		int mixedLane = -1;
		int[] laneByKind = new int[laneCount + TYPE_TABLE + 2];
		Arrays.fill(laneByKind, -1);

		for (int i = 0; i < laneCount; i++) {
			if (kind[i] == KIND_HEADER) {
				if (headerLane >= 0) {
					throw new IOException("IFZ1 に見出しが2本あります");
				}

				headerLane = i;
			} else if (kind[i] == KIND_MIXED) {
				if (mixedLane >= 0) {
					throw new IOException("IFZ1 にその他が2本あります");
				}

				mixedLane = i;
			} else if (kind[i] >= 2 && kind[i] - 2 < TYPE_TABLE) {
				if (laneByKind[kind[i]] >= 0) {
					throw new IOException("IFZ1 に同じ列が2本あります");
				}

				laneByKind[kind[i]] = i;
			} else {
				throw new IOException("IFZ1 の列の種類がおかしいです");
			}
		}

		if (headerLane < 0) {
			throw new IOException("IFZ1 に見出しがありません");
		}

		byte[] headers = lanes[headerLane];
		int[] cursorOfLane = new int[laneCount];
		byte[] raw = new byte[rawLength];
		int wp = 0;
		Cursor header = new Cursor(headers, 0);

		while (!header.done()) {
			int headerStart = header.pos;
			header.readVarInt();
			int type = header.readVarInt();
			int payloadLength = header.readVarInt();
			int headerLength = header.pos - headerStart;

			if (payloadLength < 0) {
				throw new IOException("IFZ1 の見出しがおかしいです");
			}

			int lane;

			if (type >= 0 && type < TYPE_TABLE && laneByKind[type + 2] >= 0) {
				lane = laneByKind[type + 2];
			} else {
				lane = mixedLane;
			}

			if (lane < 0) {
				throw new IOException("IFZ1 に無い列を指しています");
			}

			if (wp + headerLength + payloadLength > rawLength
					|| cursorOfLane[lane] + payloadLength > lanes[lane].length) {
				throw new IOException("IFZ1 の長さが合いません");
			}

			System.arraycopy(headers, headerStart, raw, wp, headerLength);
			wp += headerLength;
			System.arraycopy(lanes[lane], cursorOfLane[lane], raw, wp, payloadLength);
			cursorOfLane[lane] += payloadLength;
			wp += payloadLength;
		}

		// 中身の列は使い切るはず
		for (int i = 0; i < laneCount; i++) {
			if (i != headerLane && cursorOfLane[i] != lanes[i].length) {
				throw new IOException("IFZ1 の列が余っています");
			}
		}

		if (wp != rawLength) {
			throw new IOException("IFZ1 の長さが合いません");
		}

		return raw;
	}

	// --- 流し込み用の小塊（辞書付き） ---

	private static byte[] compressChunk(byte[] raw, byte[] history, int historyLength, int effort) {
		Frames frames = Frames.parse(raw);

		if (frames != null) {
			// 見出しは辞書なし・中身は辞書あり（見出しは塊ごとに顔が違うため）
			byte[] headers = headersOf(raw, frames);
			byte[] bodies = bodiesOf(raw, frames);
			byte[] packedHeaders = lzOrNull(headers, null, 0, effort);
			byte[] packedBodies = lzOrNull(bodies, history, historyLength, effort);

			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 32);
			out.write(MAGIC, 0, MAGIC.length);
			out.write(FLAG_LANES);
			writeVarInt(out, 2);
			writeLaneHead(out, KIND_HEADER, headers.length, packedHeaders);
			writeLaneHead(out, KIND_MIXED, bodies.length, packedBodies);
			writeLaneBody(out, headers, packedHeaders);
			writeLaneBody(out, bodies, packedBodies);
			return out.toByteArray();
		}

		byte[] work = new byte[Lzx1.maxPackedLength(raw.length)];
		int packedLength = Lzx1.compress(raw, 0, raw.length, history, historyLength,
				work, 0, work.length, effort);

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		if (packedLength < 0 || packedLength >= raw.length) {
			out.write(MAGIC, 0, MAGIC.length);
			out.write(FLAG_STORED);
			out.write(raw, 0, raw.length);
			return out.toByteArray();
		}

		out.write(MAGIC, 0, MAGIC.length);
		out.write(0);
		writeVarInt(out, raw.length);
		out.write(work, 0, packedLength);
		return out.toByteArray();
	}

	private static byte[] decompressChunk(byte[] packed, int off, int len, byte[] history, int historyLength)
			throws IOException {
		if (len < 5 || packed[off] != MAGIC[0] || packed[off + 1] != MAGIC[1]
				|| packed[off + 2] != MAGIC[2] || packed[off + 3] != MAGIC[3]) {
			throw new IOException("IFZ1 の印がありません");
		}

		int flags = packed[off + 4] & 0xFF;

		if ((flags & FLAG_LANES) == 0) {
			if ((flags & FLAG_STORED) != 0) {
				return Arrays.copyOfRange(packed, off + 5, off + len);
			}

			Cursor single = new Cursor(packed, off + 5);
			int plainLength = single.readVarInt();

			if (plainLength < 0 || plainLength > 64 * 1024 * 1024) {
				throw new IOException("IFZ1 の小塊の長さがおかしいです");
			}

			byte[] raw = new byte[plainLength];
			int lzOff = single.pos;
			Lzx1.decompress(packed, lzOff, off + len - lzOff, history, historyLength, raw, 0, plainLength);
			return raw;
		}

		Cursor cursor = new Cursor(packed, off + 5);
		int laneCount = cursor.readVarInt();

		if (laneCount != 2) {
			throw new IOException("IFZ1 の小塊の形がおかしいです");
		}

		int kind0 = cursor.readVarInt();
		int codec0 = cursor.readByte();
		int plain0 = cursor.readVarInt();
		int packed0 = cursor.readVarInt();
		int kind1 = cursor.readVarInt();
		int codec1 = cursor.readByte();
		int plain1 = cursor.readVarInt();
		int packed1 = cursor.readVarInt();

		if (kind0 != KIND_HEADER || kind1 != KIND_MIXED) {
			throw new IOException("IFZ1 の小塊の形がおかしいです");
		}

		byte[] headers = readLane(cursor, codec0, plain0, packed0, null, 0);
		byte[] bodies = readLane(cursor, codec1, plain1, packed1, history, historyLength);

		ByteArrayOutputStream raw = new ByteArrayOutputStream(plain0 + plain1);
		Cursor header = new Cursor(headers, 0);
		int bodyPos = 0;

		while (!header.done()) {
			int headerStart = header.pos;
			header.readVarInt();
			header.readVarInt();
			int payloadLength = header.readVarInt();
			int headerLength = header.pos - headerStart;

			if (payloadLength < 0 || bodyPos + payloadLength > bodies.length) {
				throw new IOException("IFZ1 の小塊がおかしいです");
			}

			raw.write(headers, headerStart, headerLength);
			raw.write(bodies, bodyPos, payloadLength);
			bodyPos += payloadLength;
		}

		if (bodyPos != bodies.length) {
			throw new IOException("IFZ1 の小塊が余っています");
		}

		return raw.toByteArray();
	}

	private static byte[] readLane(Cursor cursor, int codec, int plainLength, int packedLength,
			byte[] history, int historyLength) throws IOException {
		if (codec != CODEC_RAW && codec != CODEC_LZX1) {
			throw new IOException("IFZ1 の列の方式がおかしいです");
		}

		byte[] data = cursor.readBytes(packedLength);

		if (codec == CODEC_RAW) {
			if (data.length != plainLength) {
				throw new IOException("IFZ1 の列の長さが合いません");
			}

			return data;
		}

		byte[] plain = new byte[plainLength];
		Lzx1.decompress(data, 0, data.length, history, historyLength, plain, 0, plain.length);
		return plain;
	}

	private static byte[] headersOf(byte[] raw, Frames frames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (int i = 0; i < frames.count; i++) {
			out.write(raw, frames.headerStart[i], frames.headerLength[i]);
		}

		return out.toByteArray();
	}

	private static byte[] bodiesOf(byte[] raw, Frames frames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (int i = 0; i < frames.count; i++) {
			out.write(raw, frames.payloadStart[i], frames.payloadLength[i]);
		}

		return out.toByteArray();
	}

	/** 縮んだら圧縮後、縮まなかったら null */
	private static byte[] lzOrNull(byte[] plain, byte[] history, int historyLength, int effort) {
		if (plain.length == 0) {
			return null;
		}

		byte[] work = new byte[Lzx1.maxPackedLength(plain.length)];
		int packedLength = Lzx1.compress(plain, 0, plain.length, history, historyLength,
				work, 0, work.length, effort);

		if (packedLength < 0 || packedLength >= plain.length) {
			return null;
		}

		return Arrays.copyOf(work, packedLength);
	}

	private static void writeLaneHead(ByteArrayOutputStream out, int kind, int plainLength, byte[] packed) {
		writeVarInt(out, kind);
		out.write(packed == null ? CODEC_RAW : CODEC_LZX1);
		writeVarInt(out, plainLength);
		writeVarInt(out, packed == null ? plainLength : packed.length);
	}

	private static void writeLaneBody(ByteArrayOutputStream out, byte[] plain, byte[] packed) {
		byte[] data = packed == null ? plain : packed;
		out.write(data, 0, data.length);
	}

	private static void pushHistory(byte[] history, int historyLength, byte[] raw) {
		if (raw.length >= HISTORY) {
			System.arraycopy(raw, raw.length - HISTORY, history, 0, HISTORY);
			return;
		}

		int keep = Math.min(historyLength, HISTORY - raw.length);
		System.arraycopy(history, historyLength - keep, history, 0, keep);
		System.arraycopy(raw, 0, history, keep, raw.length);
	}

	// --- 区切りの読み取り ---

	/** 塊の中の区切り（見出し3数+中身）の位置表 */
	private static final class Frames {
		final int[] headerStart;
		final int[] headerLength;
		final int[] payloadStart;
		final int[] payloadLength;
		final int[] type;
		final int count;

		private Frames(int[] headerStart, int[] headerLength, int[] payloadStart,
				int[] payloadLength, int[] type, int count) {
			this.headerStart = headerStart;
			this.headerLength = headerLength;
			this.payloadStart = payloadStart;
			this.payloadLength = payloadLength;
			this.type = type;
			this.count = count;
		}

		/** 読めたら位置表、形が違ったら null */
		static Frames parse(byte[] raw) {
			int[] headerStart = new int[256];
			int[] headerLength = new int[256];
			int[] payloadStart = new int[256];
			int[] payloadLength = new int[256];
			int[] type = new int[256];
			int count = 0;
			int pos = 0;
			int[] next = new int[1];

			while (pos < raw.length) {
				if (count >= MAX_FRAMES) {
					return null;
				}

				int start = pos;
				int delta = scanVarInt(raw, pos, next);

				if (delta < 0) {
					return null;
				}

				pos = next[0];
				int typeValue = scanVarInt(raw, pos, next);

				if (typeValue < 0) {
					return null;
				}

				pos = next[0];
				int length = scanVarInt(raw, pos, next);

				if (length < 0) {
					return null;
				}

				pos = next[0];

				if (pos + length > raw.length || pos == start) {
					return null;
				}

				if (count >= headerStart.length) {
					int grown = headerStart.length * 2;
					headerStart = Arrays.copyOf(headerStart, grown);
					headerLength = Arrays.copyOf(headerLength, grown);
					payloadStart = Arrays.copyOf(payloadStart, grown);
					payloadLength = Arrays.copyOf(payloadLength, grown);
					type = Arrays.copyOf(type, grown);
				}

				headerStart[count] = start;
				headerLength[count] = pos - start;
				payloadStart[count] = pos;
				payloadLength[count] = length;
				type[count] = typeValue;
				count++;
				pos += length;
			}

			if (count == 0) {
				return null;
			}

			return new Frames(Arrays.copyOf(headerStart, count), Arrays.copyOf(headerLength, count),
					Arrays.copyOf(payloadStart, count), Arrays.copyOf(payloadLength, count),
					Arrays.copyOf(type, count), count);
		}

		/** varint を1個読む（だめなら -1）。次の位置は {@code next[0]} */
		private static int scanVarInt(byte[] raw, int pos, int[] next) {
			int value = 0;
			int shift = 0;

			for (;;) {
				if (pos >= raw.length) {
					return -1;
				}

				int b = raw[pos++] & 0xFF;
				value |= (b & 0x7F) << shift;

				if ((b & 0x80) == 0) {
					next[0] = pos;
					return value;
				}

				shift += 7;

				if (shift > 28) {
					return -1;
				}
			}
		}
	}

	// --- 小物 ---

	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}

		out.write(value);
	}

	/** 読む位置付きの読み取り */
	private static final class Cursor {
		final byte[] buf;
		int pos;

		Cursor(byte[] buf, int pos) {
			this.buf = buf;
			this.pos = pos;
		}

		boolean done() {
			return this.pos >= this.buf.length;
		}

		int readByte() throws IOException {
			if (this.pos >= this.buf.length) {
				throw new IOException("IFZ1 が途中で切れています");
			}

			return this.buf[this.pos++] & 0xFF;
		}

		int readVarInt() throws IOException {
			int value = 0;
			int shift = 0;

			for (;;) {
				if (this.pos >= this.buf.length) {
					throw new IOException("IFZ1 が途中で切れています");
				}

				int b = this.buf[this.pos++] & 0xFF;
				value |= (b & 0x7F) << shift;

				if ((b & 0x80) == 0) {
					return value;
				}

				shift += 7;

				if (shift > 28) {
					throw new IOException("IFZ1 の数がおかしいです");
				}
			}
		}

		byte[] readBytes(int length) throws IOException {
			if (length < 0 || this.pos + length > this.buf.length) {
				throw new IOException("IFZ1 が途中で切れています");
			}

			byte[] data = Arrays.copyOfRange(this.buf, this.pos, this.pos + length);
			this.pos += length;
			return data;
		}
	}
}
