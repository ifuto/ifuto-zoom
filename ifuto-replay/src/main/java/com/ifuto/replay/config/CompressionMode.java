package com.ifuto.replay.config;

import net.minecraft.text.Text;

/**
 * 保存時にかける圧縮。書き込みスレッド側でやるので、録画中のゲーム側の負荷にはほぼ影響しない。
 */
public enum CompressionMode {
	/** 圧縮しない。一番軽い（CPUを使わない）かわりにファイルは大きい */
	OFF("off", -1, Integer.MAX_VALUE),

	/** 速い圧縮（deflate 1）。512バイト以上のパケットだけ。CPU負荷はかなり小さい */
	FAST("fast", 1, 512),

	/** 標準圧縮（deflate 6）。128バイト以上のパケット */
	BALANCED("balanced", 6, 128),

	/** 強（deflate 8）。パケットをかたまりにまとめて圧縮する */
	STRONG("strong", 8, 16),

	/** 最強（deflate 9）。縮み方は一番。重いが複数人で分担するので取りこぼさない */
	MAX("max", 9, 16);

	private final String id;
	private final int deflateLevel;
	private final int minSize;

	CompressionMode(String id, int deflateLevel, int minSize) {
		this.id = id;
		this.deflateLevel = deflateLevel;
		this.minSize = minSize;
	}

	public Text getText() {
		return Text.translatable("ifuto-replay.config.compression." + this.id);
	}

	/** この長さの中身を圧縮するか */
	public boolean shouldCompress(int length) {
		return this.deflateLevel >= 0 && length >= this.minSize;
	}

	/** deflate のレベル（負数なら圧縮しない） */
	public int deflateLevel() {
		return this.deflateLevel;
	}

	/**
	 * パケットを **かたまりにまとめて** 圧縮するか。
	 *
	 * <p>圧縮するときは常にまとめる。数十バイトのパケットでも、いっしょに
	 * たまった他のパケットを辞書として使えるので桁違いに縮む。
	 * かたまり1個はそれだけで完結した deflate になる。
	 */
	public boolean blocked() {
		return this.deflateLevel >= 0;
	}
}
