package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * 縦向きのとき、枠の外の表示を左右どちらに出すか。
 */
@Environment(EnvType.CLIENT)
public enum OutsideSide {
	LEFT("left"),
	RIGHT("right");

	private final String name;

	OutsideSide(String name) {
		this.name = name;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.side." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
