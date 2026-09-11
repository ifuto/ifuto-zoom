package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * 装備していないスロットの扱い。
 */
@Environment(EnvType.CLIENT)
public enum EmptySlotMode {
	/** 枠ごとそのまま出す */
	KEEP("keep", 1.0F),
	/** 枠（またはゴーストアイコン）を半透明で出す */
	FADED("faded", 0.4F),
	/** スロットごと消す（パネルも詰まる） */
	HIDE("hide", 0.0F);

	private final String name;
	private final float alpha;

	EmptySlotMode(String name, float alpha) {
		this.name = name;
		this.alpha = alpha;
	}

	public float alpha() {
		return this.alpha;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.empty." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
