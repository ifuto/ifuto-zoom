package com.ifuto.zoom.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;
import net.minecraft.util.StringIdentifiable;

/**
 * How the zoom key behaves.
 */
@Environment(EnvType.CLIENT)
public enum ZoomMode implements StringIdentifiable {
	/** Zoom only while the key is held down (default). */
	HOLD("hold"),
	/** Press once to zoom in, press again to zoom out. */
	TOGGLE("toggle");

	private final String name;

	ZoomMode(String name) {
		this.name = name;
	}

	@Override
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
