package com.ifuto.replay.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ScreenTexts;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;

/**
 * メッセージを1つ出して「OK」で戻るだけの画面。
 *
 * <p>再生を始められなかったときなど、世界が無い状態で理由を出すのに使う。
 */
public class NoticeScreen extends Screen {
	private static final int MAX_TEXT_WIDTH = 320;

	private final Screen parent;
	private final Text message;
	private final ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this);

	public NoticeScreen(Screen parent, Text title, Text message) {
		super(title);
		this.parent = parent;
		this.message = message;
	}

	@Override
	protected void init() {
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical().spacing(8));
		TextWidget text = new TextWidget(this.message, this.textRenderer);
		text.setMaxWidth(Math.min(MAX_TEXT_WIDTH, this.width - 40));
		body.add(text);

		this.layout.addFooter(ButtonWidget.builder(ScreenTexts.OK, button -> this.close())
				.width(200)
				.build());

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
	}

	@Override
	protected void refreshWidgetPositions() {
		this.layout.refreshPositions();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public boolean shouldPause() {
		return true;
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}
}
