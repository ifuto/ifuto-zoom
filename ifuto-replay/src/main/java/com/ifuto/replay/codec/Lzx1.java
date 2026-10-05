package com.ifuto.replay.codec;

import java.io.IOException;
import java.util.Arrays;

/**
 * IFZ1 の心臓（速い LZ 系の符号化）。
 *
 * <p>パケットの塊は「同じ形の繰り返し」なので、素直な LZ でよく縮む。
 * 難しいことはしない分、速い。書式は LZ4 系:
 * トークン1バイト（上4ビット=そのままの長さ・下4ビット=一致の長さ-4）、
 * そのまま・2バイトの戻り先、の繰り返し。戻りは最大 64KB 前まで。
 *
 * <p>前の塊の続きとして圧縮するときは、末尾を辞書として渡せる
 * （{@code hist}）。展開側も同じ辞書を渡す。
 */
public final class Lzx1 {
	/** 一致とみなす最小の長さ */
	private static final int MIN_MATCH = 4;
	/** 戻れる幅（64KB） */
	private static final int WINDOW = 65536;
	/** 探す表の大きさ（2 の 16 乗） */
	private static final int HASH_SIZE = 1 << 16;
	/** ハッシュの掛け数 */
	private static final int HASH_PRIME = 0x9E3779B1;

	private Lzx1() {
	}

	/** 圧縮後が最大で何バイトになるか（置き場の確保用） */
	public static int maxPackedLength(int rawLength) {
		return rawLength + rawLength / 255 + 64;
	}

	/**
	 * 圧縮する。
	 *
	 * @param effort 0=速い（欲張り）・1=強い（1手読み）
	 * @return 圧縮後の長さ。置き場に収まらなければ -1
	 */
	public static int compress(byte[] src, int srcOff, int srcLen, byte[] hist, int histLen,
			byte[] dst, int dstOff, int dstCap, int effort) {
		if (srcLen <= 0 || dstCap <= 0) {
			return 0;
		}

		// 辞書と本体をつなげて1本として見る（辞書があるときだけ写す）
		byte[] buf;
		int base;
		int end;

		if (histLen > 0) {
			buf = new byte[histLen + srcLen];
			System.arraycopy(hist, 0, buf, 0, histLen);
			System.arraycopy(src, srcOff, buf, histLen, srcLen);
			base = histLen;
			end = histLen + srcLen;
		} else {
			buf = src;
			base = srcOff;
			end = srcOff + srcLen;
		}

		int[] table = new int[HASH_SIZE];
		Arrays.fill(table, -1);

		int dp = dstOff;
		int limit = dstOff + dstCap;
		int anchor = base;
		int pos = base;
		int matchLimit = end - MIN_MATCH;
		int step = 1;
		int searchNb = (effort <= 0 ? 2 : 1) << 6;

		while (pos <= matchLimit) {
			int found = -1;
			int foundLen = 0;

			// 縮なそうな所はだんだん大股で飛ばす（番地は先に見る）
			for (;;) {
				if (pos > matchLimit) {
					break;
				}

				int hash = hash32(buf, pos);
				int prev = table[hash];
				table[hash] = pos;

				if (prev >= base - histLen && prev < pos && pos - prev < WINDOW
						&& load32(buf, prev) == load32(buf, pos)) {
					found = prev;
					foundLen = extend(buf, prev + MIN_MATCH, pos + MIN_MATCH, end);
					break;
				}

				pos += step;
				step = searchNb++ >>> 6;

				if (step < 1) {
					step = 1;
				}
			}

			if (found < 0) {
				break;
			}

			// 強い方式は1手先も見る（次が長ければ今回は1バイト送り）
			if (effort > 0 && pos + 1 <= matchLimit) {
				int nextHash = hash32(buf, pos + 1);
				int nextPrev = table[nextHash];

				if (nextPrev >= base - histLen && nextPrev < pos + 1 && pos + 1 - nextPrev < WINDOW
						&& load32(buf, nextPrev) == load32(buf, pos + 1)) {
					int nextLen = extend(buf, nextPrev + MIN_MATCH, pos + 1 + MIN_MATCH, end);

					if (nextLen > foundLen) {
						table[nextHash] = pos + 1;
						pos++;
						step = 1;
						continue;
					}
				}
			}

			int litLen = pos - anchor;
			int matchLen = foundLen + MIN_MATCH;

			// トークンを置く場所だけ先に取る
			if (dp + 1 + litLen + (litLen >= 15 ? (litLen / 255 + 1) : 0)
					+ 2 + (matchLen >= 19 ? ((matchLen - 19) / 255 + 1) : 0) > limit) {
				return -1;
			}

			int tokenPos = dp++;

			if (litLen >= 15) {
				dst[tokenPos] = (byte) 0xF0;
				int rest = litLen - 15;

				while (rest >= 255) {
					dst[dp++] = (byte) 0xFF;
					rest -= 255;
				}

				dst[dp++] = (byte) rest;
			} else {
				dst[tokenPos] = (byte) (litLen << 4);
			}

			System.arraycopy(buf, anchor, dst, dp, litLen);
			dp += litLen;

			int offset = pos - found;
			dst[dp++] = (byte) offset;
			dst[dp++] = (byte) (offset >>> 8);

			int extra = matchLen - MIN_MATCH;

			if (extra >= 15) {
				dst[tokenPos] |= 0x0F;
				extra -= 15;

				while (extra >= 255) {
					dst[dp++] = (byte) 0xFF;
					extra -= 255;
				}

				dst[dp++] = (byte) extra;
			} else {
				dst[tokenPos] |= (byte) extra;
			}

			anchor = pos + matchLen;
			pos = anchor;
			step = 1;
			searchNb = (effort <= 0 ? 2 : 1) << 6;

			// 飛び越えた所も表に入れておく（次の探しのため）
			if (pos <= matchLimit) {
				table[hash32(buf, pos - 2)] = pos - 2;
			}
		}

		// 余りはそのまま
		int tail = end - anchor;

		if (dp + 1 + tail + (tail >= 15 ? (tail / 255 + 1) : 0) > limit) {
			return -1;
		}

		int tokenPos = dp++;

		if (tail >= 15) {
			dst[tokenPos] = (byte) 0xF0;
			int rest = tail - 15;

			while (rest >= 255) {
				dst[dp++] = (byte) 0xFF;
				rest -= 255;
			}

			dst[dp++] = (byte) rest;
		} else {
			dst[tokenPos] = (byte) (tail << 4);
		}

		System.arraycopy(buf, anchor, dst, dp, tail);
		dp += tail;
		return dp - dstOff;
	}

	/**
	 * 展開する。
	 *
	 * @return 展開後の長さ（{@code dstLen} と同じはず）
	 * @throws IOException 壊れている
	 */
	public static int decompress(byte[] src, int srcOff, int srcLen, byte[] hist, int histLen,
			byte[] dst, int dstOff, int dstLen) throws IOException {
		if (dstLen < 0 || srcLen < 0) {
			throw new IOException("LZ の長さがおかしいです");
		}

		byte[] out;
		int base;

		if (histLen > 0) {
			out = new byte[histLen + dstLen];
			System.arraycopy(hist, 0, out, 0, histLen);
			base = histLen;
		} else {
			out = dst;
			base = dstOff;
		}

		int sp = srcOff;
		int srcEnd = srcOff + srcLen;
		int op = base;
		int outEnd = base + dstLen;
		int earliest = base - histLen;

		while (sp < srcEnd) {
			int token = src[sp++] & 0xFF;
			int litLen = token >>> 4;

			if (litLen == 15) {
				int extra;

				do {
					if (sp >= srcEnd) {
						throw new IOException("LZ が途中で切れています");
					}

					extra = src[sp++] & 0xFF;
					litLen += extra;
				} while (extra == 255);
			}

			if (sp + litLen > srcEnd || op + litLen > outEnd) {
				throw new IOException("LZ が壊れています");
			}

			System.arraycopy(src, sp, out, op, litLen);
			sp += litLen;
			op += litLen;

			if (sp >= srcEnd) {
				break;
			}

			if (sp + 2 > srcEnd) {
				throw new IOException("LZ が途中で切れています");
			}

			int offset = (src[sp++] & 0xFF) | ((src[sp++] & 0xFF) << 8);

			if (offset <= 0 || offset > op - earliest) {
				throw new IOException("LZ の戻り先がおかしいです");
			}

			int matchLen = (token & 0x0F) + MIN_MATCH;

			if ((token & 0x0F) == 15) {
				int extra;

				do {
					if (sp >= srcEnd) {
						throw new IOException("LZ が途中で切れています");
					}

					extra = src[sp++] & 0xFF;
					matchLen += extra;
				} while (extra == 255);
			}

			if (op + matchLen > outEnd) {
				throw new IOException("LZ が壊れています");
			}

			// 前から1段ずつ写す（重なりは自分が出た分をまた使うのがLZの約束。
			// arraycopy の一時写しではだめ）
			while (matchLen > 0) {
				int chunk = Math.min(offset, matchLen);
				System.arraycopy(out, op - offset, out, op, chunk);
				op += chunk;
				matchLen -= chunk;
			}
		}

		if (op != outEnd) {
			throw new IOException("LZ の長さが合いません");
		}

		if (histLen > 0) {
			System.arraycopy(out, base, dst, dstOff, dstLen);
		}

		return dstLen;
	}

	/** 4バイトを数として読む */
	private static int load32(byte[] buf, int pos) {
		return (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8)
				| ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 3] & 0xFF) << 24);
	}

	/** 表の番地 */
	private static int hash32(byte[] buf, int pos) {
		return (load32(buf, pos) * HASH_PRIME) >>> (32 - 16);
	}

	/** 同じ所がどこまで続くか（4バイト目以降の長さ） */
	private static int extend(byte[] buf, int a, int b, int end) {
		int start = b;

		while (b + 8 <= end && load32(buf, a) == load32(buf, b)
				&& load32(buf, a + 4) == load32(buf, b + 4)) {
			a += 8;
			b += 8;
		}

		while (b < end && buf[a] == buf[b]) {
			a++;
			b++;
		}

		return b - start;
	}
}
