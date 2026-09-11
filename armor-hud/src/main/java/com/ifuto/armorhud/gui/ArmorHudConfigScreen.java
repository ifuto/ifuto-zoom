package com.ifuto.armorhud.gui;

import com.ifuto.armorhud.config.ArmorHudConfig;
import com.ifuto.armorhud.config.EmptySlotMode;
import com.ifuto.armorhud.config.HudLayout;
import com.ifuto.armorhud.config.HudPosition;
import com.ifuto.armorhud.config.InfoMode;
import com.ifuto.armorhud.config.OutsideSide;
import com.ifuto.armorhud.config.ShowCondition;
import com.ifuto.armorhud.config.SlotBackground;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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

		// 2行目: 並び方 / 背景
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.<HudLayout>builder(HudLayout::getText, this.config.layout)
				.values(HudLayout.values())
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.layout"),
						(button, value) -> this.config.layout = value));

		this.addDrawableChild(CyclingButtonWidget.<SlotBackground>builder(SlotBackground::getText, this.config.background)
				.values(SlotBackground.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.background.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.background"),
						(button, value) -> this.config.background = value));

		// 3行目: 空きスロット / ホットバーとの距離
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.<EmptySlotMode>builder(EmptySlotMode::getText, this.config.emptyMode)
				.values(EmptySlotMode.values())
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.empty"),
						(button, value) -> this.config.emptyMode = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.hotbar_gap", 0, 64, "%d px",
				this.config.hotbarGap,
				value -> this.config.hotbarGap = value));

		// 4行目: スロット間隔 / 枠の中
		y += ROW_HEIGHT;
		this.addDrawableChild(new OptionSlider(left, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.slot_gap", 0, 16, "%d px",
				this.config.slotGap,
				value -> this.config.slotGap = value));

		this.addDrawableChild(CyclingButtonWidget.<InfoMode>builder(InfoMode::getText, this.config.inside)
				.values(InfoMode.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.inside.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.inside"),
						(button, value) -> this.config.inside = value));

		// 5行目: 枠の外 / 外の表示位置
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.<InfoMode>builder(InfoMode::getText, this.config.outside)
				.values(InfoMode.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.outside.tooltip")))
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.outside"),
						(button, value) -> this.config.outside = value));

		this.addDrawableChild(CyclingButtonWidget.<OutsideSide>builder(OutsideSide::getText, this.config.outsideSide)
				.values(OutsideSide.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.outside_side.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.outside_side"),
						(button, value) -> this.config.outsideSide = value));

		// 6行目: ピンチで点滅 / しきい値
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.warnBlink)
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.warn_blink"),
						(button, value) -> this.config.warnBlink = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.warn_percent", 1, 50, "%d%%",
				this.config.warnPercent,
				value -> this.config.warnPercent = value));

		// 7行目: コントラスト / 横オフセット
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.dynamicContrast)
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.dynamic_contrast.tooltip")))
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.dynamic_contrast"),
						(button, value) -> this.config.dynamicContrast = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.offset_x", -100, 100, "%+d px",
				this.config.offsetX,
				value -> this.config.offsetX = value));

		// 8行目: 縦オフセット / HUD の大きさ
		y += ROW_HEIGHT;
		this.addDrawableChild(new OptionSlider(left, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.offset_y", -100, 100, "%+d px",
				this.config.offsetY,
				value -> this.config.offsetY = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-armor-hud.config.hud_scale", 50, 150, "%d%%",
				this.config.hudScale,
				value -> this.config.hudScale = value));

		// 9行目: 表示条件 / 壊れたら通知
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.<ShowCondition>builder(ShowCondition::getText, this.config.showCondition)
				.values(ShowCondition.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.show_condition.tooltip")))
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.show_condition"),
						(button, value) -> this.config.showCondition = value));

		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.breakAlert)
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.break_alert.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.break_alert"),
						(button, value) -> this.config.breakAlert = value));

		// 10行目: Discord Client ID
		y += ROW_HEIGHT;
		TextFieldWidget clientIdField = new TextFieldWidget(this.textRenderer,
				left, y, WIDGET_WIDTH * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-armor-hud.config.client_id"));
		clientIdField.setMaxLength(32);
		clientIdField.setText(this.config.discordClientId);
		clientIdField.setPlaceholder(Text.translatable("ifuto-armor-hud.config.client_id.placeholder"));
		clientIdField.setTooltip(Tooltip.of(Text.translatable("ifuto-armor-hud.config.client_id.tooltip")));
		clientIdField.setChangedListener(value -> this.config.discordClientId = value.trim());
		this.addDrawableChild(clientIdField);

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
		context.drawTextWithShadow(this.textRenderer, "Made by Ifuto_mitai", 4, this.height - 12, 0xFF808080);
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

	// スライダーの 0-1 位置を指定範囲に変換して刻むだけのクラス
	private static class OptionSlider extends SliderWidget {
		private final String labelKey;
		private final int min;
		private final int max;
		private final String format;
		private final IntConsumer setter;

		OptionSlider(int x, int y, int width, int height, String labelKey, int min, int max, String format,
					 int initialValue, IntConsumer setter) {
			super(x, y, width, height, Text.empty(), (double) (initialValue - min) / (double) (max - min));
			this.labelKey = labelKey;
			this.min = min;
			this.max = max;
			this.format = format;
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

			this.setMessage(Text.translatable(this.labelKey, Text.literal(String.format(this.format, this.intValue()))));
		}

		@Override
		protected void applyValue() {
			if (this.setter != null) {
				this.setter.accept(this.intValue());
			}
		}
	}
}
