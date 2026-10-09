package com.ifuto.kb.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class KbServerListScreen extends Screen {
	private final Screen parent;

	public KbServerListScreen(Screen parent) {
		super(Text.literal("KB Meter — サーバー一覧"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addDrawableChild(ButtonWidget.builder(Text.literal("閉じる"), button -> close())
			.dimensions(width / 2 - 50, height - 30, 100, 20).build());
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
			Text.literal("まだ計測データがありません"), width / 2, 40, 0xA0A0A0);
	}
}
