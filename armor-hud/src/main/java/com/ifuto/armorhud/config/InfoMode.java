package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * 耐久情報の出し方。枠の中・外どちらにも使う。
 * GAUGE は枠の中なら横バー、枠の外なら縦バー（横並び時の枠外では無効）。
 */
@Environment(EnvType.CLIENT)
public enum InfoMode {
	/** 出さない */
	NONE("none"),
	/** ゲージ */
	GAUGE("gauge"),
	/** 残り耐久を % 表記（%記号付き） */
	PERCENT("percent"),
	/** 残り耐久を数値で */
	NUMBER("number"),
	/** 満タンから減った耐久を数値で */
	LOST("lost"),
	/** 満タンから減った耐久を % 表記（%記号付き） */
	LOST_PERCENT("lost_percent");

	private final String name;

	InfoMode(String name) {
		this.name = name;
	}

	public boolean isText() {
		return this == PERCENT || this == NUMBER || this == LOST || this == LOST_PERCENT;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.info." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
