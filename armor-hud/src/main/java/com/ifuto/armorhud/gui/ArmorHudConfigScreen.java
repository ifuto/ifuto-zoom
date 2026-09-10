package com.ifuto.armorhud.gui;

import com.ifuto.armorhud.config.ArmorHudConfig;
import com.ifuto.armorhud.config.DurabilityStyle;
import com.ifuto.armorhud.config.HudLayout;
import com.ifuto.armorhud.config.HudPosition;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/**
 * Mod Menu から開く設定画面。バニラのオプション画面っぽい見た目にしている。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 158;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 8;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	private final ArmorHudConfig config;

	public ArmorHudConfigScreen(Screen parent) {
		super(Text.translatable("ifuto-armor-hud.config.title"));
		this.parent = parent;
		this.config = ArmorHudConfig.get();
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2;
		int right = this.width / 2 + COLUMN_GAP / 2;
		int y = 40;

		// 1行目: 表示 / 位置
		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.showHud)
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.show_hud"),
						(button, value) -> this.config.showHud = value));

		this.addDrawableChild(CyclingButtonWidget.<HudPosition>builder(HudPosition::getText, this.config.position)
				.values(HudPosition.values())
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.position"),
						(button, value) -> this.config.position = value));

		// 2行目: 並び方 / 耐久表示
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.<HudLayout>builder(HudLayout::getText, this.config.layout)
				.values(HudLayout.values())
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.layout"),
						(button, value) -> this.config.layout = value));

		this.addDrawableChild(CyclingButtonWidget.<DurabilityStyle>builder(DurabilityStyle::getText, this.config.durability)
				.values(DurabilityStyle.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.durability.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.durability"),
						(button, value) -> this.config.durability = value));

		// 3行目: 空きスロット / 横オフセット
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.hideEmptySlots)
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.hide_empty"),
						(button, value) -> this.config.hideEmptySlots = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.offset_x", -100, 100,
				this.config.offsetX,
				value -> this.config.offsetX = value));

		// 4行目: 縦オフセット
		y += ROW_HEIGHT;
		this.addDrawableChild(new OptionSlider(left, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.offset_y", -100, 100,
				this.config.offsetY,
				value -> this.config.offsetY = value));

		// リセット / 完了
		y += ROW_HEIGHT + 8;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("ifuto-armor-hud.config.reset"), button -> {
			this.config.resetToDefaults();
			this.config.save();
			this.clearAndInit();
		}).dimensions(left, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());

		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> this.close())
				.dimensions(right, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("ifuto-armor-hud.config.hint"),
				this.width / 2, 26, 0xFFA0A0A0);
	}

	@Override
	public void close() {
		this.config.save();
		MinecraftClient.getInstance().setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.config.save();
		super.removed();
	}

	// スライダーの 0-1 位置を±の範囲に変換するだけのクラス
	private static class OptionSlider extends SliderWidget {
		private final String labelKey;
		private final int min;
		private final int max;
		private final IntConsumer setter;

		OptionSlider(int x, int y, int width, int height, String labelKey, int min, int max,
					 int initialValue, IntConsumer setter) {
			super(x, y, width, height, Text.empty(), (double) (initialValue - min) / (double) (max - min));
			this.labelKey = labelKey;
			this.min = min;
			this.max = max;
			this.setter = setter;
			this.updateMessage();
		}

		private int intValue() {
			return this.min + (int) Math.round((this.max - this.min) * this.value);
		}

		@Override
		protected void updateMessage() {
			if (this.labelKey == null) {
				return;
			}

			this.setMessage(Text.translatable(this.labelKey, Text.literal(String.format("%+d px", this.intValue()))));
		}

		@Override
		protected void applyValue() {
			if (this.setter != null) {
				this.setter.accept(this.intValue());
			}
		}
	}
}
