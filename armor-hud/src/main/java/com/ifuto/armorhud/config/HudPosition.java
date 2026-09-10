package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * HUD を置く画面の隅。
 */
@Environment(EnvType.CLIENT)
public enum HudPosition {
	TOP_LEFT("top_left"),
	TOP_RIGHT("top_right"),
	BOTTOM_LEFT("bottom_left"),
	BOTTOM_RIGHT("bottom_right");

	private final String name;

	HudPosition(String name) {
		this.name = name;
	}

	public boolean isLeft() {
		return this == TOP_LEFT || this == BOTTOM_LEFT;
	}

	public boolean isTop() {
		return this == TOP_LEFT || this == TOP_RIGHT;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.position." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
