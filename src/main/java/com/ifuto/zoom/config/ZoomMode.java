package com.ifuto.zoom.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * ズームキーの動作。
 */
@Environment(EnvType.CLIENT)
public enum ZoomMode {
	/** 押している間だけズーム（既定） */
	HOLD("hold"),
	/** 押すたびに 拡大⇔通常 */
	TOGGLE("toggle");

	private final String name;

	ZoomMode(String name) {
		this.name = name;
	}

	public String asString() {
		return this.name;
	}

	public String getTranslationKey() {
		return "ifuto-zoom.config.mode." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
