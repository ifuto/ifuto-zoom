package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * アイコンの並べ方。
 */
@Environment(EnvType.CLIENT)
public enum HudLayout {
	VERTICAL("vertical"),
	HORIZONTAL("horizontal");

	private final String name;

	HudLayout(String name) {
		this.name = name;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.layout." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
