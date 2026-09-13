package com.ifuto.replay.recording;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 書いたバイト数を数えながら書き込む小さな出力ストリーム。
 *
 * <p>数えているのは「論理的なバイト数」なので、BufferedOutputStream のバッファに
 * 溜まっている分も含めてファイル上の位置と一致する。シーク用インデックスはこれで作る。
 */
final class ReplayDataOutput {
	private final OutputStream out;

	/** 書き込み済みのバイト数（別スレッドから読むので volatile） */
	private volatile long position;

	ReplayDataOutput(OutputStream out) {
		this.out = out;
	}

	long position() {
		return this.position;
	}

	void writeByte(int value) throws IOException {
		this.out.write(value);
		this.position += 1L;
	}

	/** Minecraft と同じ可変長int（最大5バイト）。小さい値ほど短くなる */
	void writeVarInt(int value) throws IOException {
		int remaining = value;

		while (true) {
			if ((remaining & ~0x7F) == 0) {
				this.writeByte(remaining);
				return;
			}

			this.writeByte((remaining & 0x7F) | 0x80);
			remaining >>>= 7;
		}
	}

	/** 可変長long（ファイル位置むけ） */
	void writeVarLong(long value) throws IOException {
		long remaining = value;

		while (true) {
			if ((remaining & ~0x7FL) == 0L) {
				this.writeByte((int) remaining);
				return;
			}

			this.writeByte((int) (remaining & 0x7FL) | 0x80);
			remaining >>>= 7;
		}
	}

	/** 固定8バイトのlong（日時むけ） */
	void writeFixedLong(long value) throws IOException {
		for (int shift = 56; shift >= 0; shift -= 8) {
			this.writeByte((int) (value >>> shift) & 0xFF);
		}
	}

	/** 長さ前置きの UTF-8 文字列 */
	void writeString(String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		this.writeVarInt(bytes.length);
		this.out.write(bytes);
		this.position += bytes.length;
	}

	void writeBytes(byte[] bytes) throws IOException {
		this.out.write(bytes);
		this.position += bytes.length;
	}

	/** ByteBuf の中身をそのまま流し込む（コピーなし） */
	void writeBytes(ByteBuf buffer, int length) throws IOException {
		buffer.readBytes(this.out, length);
		this.position += length;
	}

	void flush() throws IOException {
		this.out.flush();
	}

	void close() throws IOException {
		this.out.close();
	}
}
