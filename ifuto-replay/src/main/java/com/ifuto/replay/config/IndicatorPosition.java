package com.ifuto.replay.config;

import net.minecraft.text.Text;

/** 録画インジケータを出す画面の隅。 */
public enum IndicatorPosition {
	TOP_LEFT("top_left"),
	TOP_RIGHT("top_right"),
	BOTTOM_LEFT("bottom_left"),
	BOTTOM_RIGHT("bottom_right");

	private final String id;

	IndicatorPosition(String id) {
		this.id = id;
	}

	public Text getText() {
		return Text.translatable("ifuto-replay.config.indicator_position." + this.id);
	}
}
