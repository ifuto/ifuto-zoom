package com.ifuto.replay.config;

import net.minecraft.text.Text;

/**
 * 低スペックPCむけの軽量動作にするか。
 *
 * <p>軽量動作では、圧縮を「速い」に落として、圧縮の係を増やさず、
 * 書き込みまわりをゲームより後回しにする。録れる内容は変わらない
 * （パケットを捨てたりはしない）ので、再生や書き出しの質は同じ。
 */
public enum LowSpecMode {
	/** コアが少なければ自動で軽くする（4コア以下でON） */
	AUTO("auto"),

	/** いつも軽くする */
	ON("on"),

	/** 軽くしない（速いPCむけ） */
	OFF("off");

	private final String id;

	LowSpecMode(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public Text getText() {
		return Text.translatable("ifuto-replay.config.low_spec." + this.id);
	}
}
