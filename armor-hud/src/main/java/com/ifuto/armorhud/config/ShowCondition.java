package com.ifuto.armorhud.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * HUD をどういうとき出すか。
 */
@Environment(EnvType.CLIENT)
public enum ShowCondition {
	/** 常に出す */
	ALWAYS("always"),
	/** 耐久が減っている装備が1つでもあるとき */
	WHEN_DAMAGED("when_damaged"),
	/** ピンチ（点滅しきい値以下）の装備があるときだけ */
	WHEN_CRITICAL("when_critical");

	private final String name;

	ShowCondition(String name) {
		this.name = name;
	}

	public String getTranslationKey() {
		return "ifuto-armor-hud.config.show_condition." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
