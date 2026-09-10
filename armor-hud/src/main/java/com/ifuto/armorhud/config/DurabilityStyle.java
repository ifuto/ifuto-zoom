package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * 耐久値の出し方。
 */
@Environment(EnvType.CLIENT)
public enum DurabilityStyle {
	/** 出さない（アイコンだけ） */
	NONE("none", false, false),
	/** バーのみ（既定） */
	BAR("bar", true, false),
	/** バー＋残り数値 */
	BAR_NUMBER("bar_number", true, true),
	/** バー＋％ */
	BAR_PERCENT("bar_percent", true, true),
	/** 数値のみ */
	NUMBER("number", false, true),
	/** ％のみ */
	PERCENT("percent", false, true);

	private final String name;
	private final boolean bar;
	private final boolean text;

	DurabilityStyle(String name, boolean bar, boolean text) {
		this.name = name;
		this.bar = bar;
		this.text = text;
	}

	public boolean showBar() {
		return this.bar;
	}

	public boolean showText() {
		return this.text;
	}

	public boolean isPercent() {
		return this == BAR_PERCENT || this == PERCENT;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.durability." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
