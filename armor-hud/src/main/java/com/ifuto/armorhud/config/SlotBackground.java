package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * スロットの背景の描き方。
 */
@Environment(EnvType.CLIENT)
public enum SlotBackground {
	/** バニラっぽいアイテム枠を描く */
	FRAME("frame"),
	/** 枠は消して、空きスロットには装備のミニアイコンだけ背景に置く */
	GHOST("ghost");

	private final String name;

	SlotBackground(String name) {
		this.name = name;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.background." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
