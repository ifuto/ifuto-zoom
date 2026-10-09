package com.ifuto.kb.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class KbMenuHook {
	private KbMenuHook() {
	}

	public static ButtonWidget makeKbButton(Screen parent, int screenWidth) {
		return ButtonWidget.builder(Text.literal("KB"), button -> {
			MinecraftClient client = MinecraftClient.getInstance();
			client.setScreen(new KbServerListScreen(parent));
		}).dimensions(screenWidth - 48, 8, 40, 20).build();
	}
}
