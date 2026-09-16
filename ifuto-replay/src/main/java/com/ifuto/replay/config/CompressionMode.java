package com.ifuto.replay.config;

import com.ifuto.replay.recording.ReplayFormat;
import net.minecraft.text.Text;

/**
 * 保存時にかける圧縮。書き込みスレッド側でやるので、録画中のゲーム側の負荷にはほぼ影響しない。
 *
 * <p>「標準」以上は zstd を使う。deflate より速くて小さいので、
 * 軽さと小ささを両立できる。かたまりが大きいほど縮む（辞書として使える過去が増える）。
 */
public enum CompressionMode {
	/** 圧縮しない。一番軽い（CPUを使わない）かわりにファイルは大きい */
	OFF("off", -1, Integer.MAX_VALUE, ReplayFormat.METHOD_RAW, 64 * 1024),

	/** 速い圧縮（deflate 1）。一番軽い圧縮。ファイルはやや大きい */
	FAST("fast", 1, 512, ReplayFormat.METHOD_DEFLATE, 64 * 1024),

	/** 標準（zstd・64KBずつ）。deflate 6 より速くて小さい */
	BALANCED("balanced", 6, 128, ReplayFormat.METHOD_ZSTD, 64 * 1024),

	/** 強（zstd・256KBずつ）。おすすめ。標準より少し小さく、速さはほぼ同じ */
	STRONG("strong", 8, 16, ReplayFormat.METHOD_ZSTD, 256 * 1024),

	/** 最強（zstd・1MBずつ）。縮み方は一番。かたまりが大きいぶん少しだけ待つ */
	MAX("max", 9, 16, ReplayFormat.METHOD_ZSTD, 1024 * 1024);

	private final String id;
	private final int deflateLevel;
	private final int minSize;
	private final int codecMethod;
	private final int blockBytes;

	CompressionMode(String id, int deflateLevel, int minSize, int codecMethod, int blockBytes) {
		this.id = id;
		this.deflateLevel = deflateLevel;
		this.minSize = minSize;
		this.codecMethod = codecMethod;
		this.blockBytes = blockBytes;
	}

	public Text getText() {
		return Text.translatable("ifuto-replay.config.compression." + this.id);
	}

	/** この長さの中身を圧縮するか */
	public boolean shouldCompress(int length) {
		return this.deflateLevel >= 0 && length >= this.minSize;
	}

	/** deflate のレベル（負数なら圧縮しない。「速い」と写し用） */
	public int deflateLevel() {
		return this.deflateLevel;
	}

	/** かたまりに使う格納方法（RAW / DEFLATE / ZSTD） */
	public int codecMethod() {
		return this.codecMethod;
	}

	/** かたまり1個の目安の大きさ（これくらいたまったら区切る） */
	public int blockBytes() {
		return this.blockBytes;
	}

	/**
	 * パケットを **かたまりにまとめて** 圧縮するか。
	 *
	 * <p>圧縮するときは常にまとめる。数十バイトのパケットでも、いっしょに
	 * たまった他のパケットを辞書として使えるので桁違いに縮む。
	 * かたまり1個はそれだけで完結した圧縮になる。
	 */
	public boolean blocked() {
		return this.deflateLevel >= 0;
	}
}
