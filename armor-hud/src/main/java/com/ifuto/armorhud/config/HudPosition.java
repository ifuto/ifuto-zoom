package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * HUD をホットバーの左右どちらに置くか。
 */
@Environment(EnvType.CLIENT)
public enum HudPosition {
	HOTBAR_LEFT("hotbar_left"),
	HOTBAR_RIGHT("hotbar_right");

	private final String name;

	HudPosition(String name) {
		this.name = name;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.position." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
