package com.ifuto.replay.export;

import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.nio.ByteBuffer;

/**
 * 描けた絵を GPU から非同期で取り込む（PBO 2枚交代）。
 *
 * <p>バニラのスクリーンショットは「読むたびに GPU を止める＋毎枚確保＋CPU で反転」で
 * 1080p でも 2〜5ms 捨てる。ここでは読み出しだけ仕掛けてすぐ返し、前回ぶんの
 * 出来上がりを取り出す（1枚遅れのパイプライン）。反転は ffmpeg 側でやる。
 *
 * <p>全部描画スレッドでしか呼ばない（GL の約束）。絵は下から上の並びのまま渡す。
 */
public final class PboCapture implements AutoCloseable {
	/** 1枚目の読み出しを仕掛けただけ（まだ取り出せる絵がない） */
	public static final int PRIMING = 0;
	/** stage に1枚入った */
	public static final int HAS_FRAME = 1;

	private final int width;
	private final int height;
	private final int frameSize;
	private final int pboA;
	private final int pboB;
	/** 次の読み出し先。true なら A に読み、B から取り出す */
	private boolean readIntoA = true;
	/** 1枚仕掛け済み（2回目から取り出せる） */
	private boolean primed;
	private boolean closed;

	public PboCapture(int width, int height) {
		this.width = width;
		this.height = height;
		this.frameSize = width * height * 4;
		this.pboA = GL15.glGenBuffers();
		this.pboB = GL15.glGenBuffers();

		try {
			this.initStorage(this.pboA);
			this.initStorage(this.pboB);
		} catch (Throwable t) {
			this.close();
			throw t;
		}
	}

	private void initStorage(int pbo) {
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);

		try {
			GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, this.frameSize, GL15.GL_STREAM_READ);
		} finally {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		}
	}

	/**
	 * 描き終わった絵を1枚取り込む。
	 *
	 * @return {@link #PRIMING}（仕掛けただけ）か {@link #HAS_FRAME}（stage に1枚）
	 */
	public int capture(Framebuffer framebuffer, byte[] stage) {
		if (this.closed) {
			throw new IllegalStateException("PBO は閉じています");
		}

		if (stage.length < this.frameSize) {
			throw new IllegalArgumentException("置き場が小さいです");
		}

		int readTarget = this.readIntoA ? this.pboA : this.pboB;
		int mapSource = this.readIntoA ? this.pboB : this.pboA;

		framebuffer.beginRead();

		try {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readTarget);

			try {
				// 捨てて作り直し（GPU が前の読み出し中でも止めない）
				GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, this.frameSize, GL15.GL_STREAM_READ);
				GL21.glReadPixels(0, 0, this.width, this.height,
						GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
			} finally {
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			}
		} finally {
			framebuffer.endRead();
		}

		boolean hasPrevious = this.primed;
		this.primed = true;
		this.readIntoA = !this.readIntoA;

		if (!hasPrevious) {
			return PRIMING;
		}

		this.drain(mapSource, stage);
		return HAS_FRAME;
	}

	/**
	 * 最後に仕掛けた1枚を取り出す（もう絵は来ないとき）。
	 *
	 * @return 取り出せたら true（取り出す物がなければ false）
	 */
	public boolean flush(byte[] stage) {
		if (!this.primed || this.closed) {
			return false;
		}

		// 2度取り出し防止（終わり際にしか呼ばない）
		this.primed = false;
		int pending = this.readIntoA ? this.pboB : this.pboA;
		this.drain(pending, stage);
		return true;
	}

	/** できあがりを取り出して stage に入れる（並びは下から上のまま） */
	private void drain(int pbo, byte[] stage) {
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);

		try {
			ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, null);

			if (mapped == null) {
				throw new IllegalStateException("PBO の取り出しに失敗しました");
			}

			mapped.get(stage, 0, this.frameSize);

			// false が返ったら中身が壊れている（GPU が落とした）。黙って流さない
			if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)) {
				throw new IllegalStateException("PBO の中身が壊れています");
			}
		} finally {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		}
	}

	/**
	 * 畳む。描画スレッドで呼ぶこと。失敗しても投げない（終わり際の掃除のため）。
	 */
	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;

		try {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			GL15.glDeleteBuffers(this.pboA);
			GL15.glDeleteBuffers(this.pboB);
		} catch (Throwable ignored) {
			// 終わり際の掃除は黙ってやる
		}
	}
}
