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
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.LayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/**
 * Mod Menu から開く設定画面。バニラのオプション画面っぽい見た目にしている。
 * バニラのスクロールレイアウトを使っているので、ウィンドウの縦が短くても全部届く。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 158;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 8;
	private static final int ROW_SPACING = 4;
	private static final int SCROLL_MIN_HEIGHT = 120;

	private final Screen parent;
	private final ArmorHudConfig config;
	private ScrollableLayoutWidget scrollable;
	private ThreePartsLayoutWidget layout;

	public ArmorHudConfigScreen(Screen parent) {
		super(Text.translatable("ifuto-armor-hud.config.title"));
		this.parent = parent;
		this.config = ArmorHudConfig.get();
	}

	@Override
	protected void init() {
		this.layout = new ThreePartsLayoutWidget(this);
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical());
		body.add(new TextWidget(Text.translatable("ifuto-armor-hud.config.hint"), this.textRenderer),
				positioner -> positioner.marginBottom(6));

		// 中身は2列グリッド。行ごとの横配置を縦に積む
		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(ROW_SPACING);

		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.showHud)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.show_hud.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.show_hud"),
								(button, value) -> this.config.showHud = value),
				CyclingButtonWidget.<HudPosition>builder(HudPosition::getText, this.config.position)
						.values(HudPosition.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.position.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.position"),
								(button, value) -> this.config.position = value)));

		content.add(row(
				CyclingButtonWidget.<HudLayout>builder(HudLayout::getText, this.config.layout)
						.values(HudLayout.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.layout.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.layout"),
								(button, value) -> this.config.layout = value),
				CyclingButtonWidget.<SlotBackground>builder(SlotBackground::getText, this.config.background)
						.values(SlotBackground.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.background.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.background"),
								(button, value) -> this.config.background = value)));

		content.add(row(
				CyclingButtonWidget.<EmptySlotMode>builder(EmptySlotMode::getText, this.config.emptyMode)
						.values(EmptySlotMode.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.empty.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.empty"),
								(button, value) -> this.config.emptyMode = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.hotbar_gap", 0, 64, "%d px",
						this.config.hotbarGap,
						value -> this.config.hotbarGap = value).tooltip("ifuto-armor-hud.config.hotbar_gap.tooltip")));

		content.add(row(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.slot_gap", 0, 16, "%d px",
						this.config.slotGap,
						value -> this.config.slotGap = value).tooltip("ifuto-armor-hud.config.slot_gap.tooltip"),
				CyclingButtonWidget.<InfoMode>builder(InfoMode::getText, this.config.inside)
						.values(InfoMode.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.inside.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.inside"),
								(button, value) -> this.config.inside = value)));

		content.add(row(
				CyclingButtonWidget.<InfoMode>builder(InfoMode::getText, this.config.outside)
						.values(InfoMode.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.outside.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.outside"),
								(button, value) -> this.config.outside = value),
				CyclingButtonWidget.<OutsideSide>builder(OutsideSide::getText, this.config.outsideSide)
						.values(OutsideSide.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.outside_side.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.outside_side"),
								(button, value) -> this.config.outsideSide = value)));

		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.warnBlink)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.warn_blink.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.warn_blink"),
								(button, value) -> this.config.warnBlink = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.warn_percent", 1, 50, "%d%%",
						this.config.warnPercent,
						value -> this.config.warnPercent = value).tooltip("ifuto-armor-hud.config.warn_percent.tooltip")));

		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.dynamicContrast)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.dynamic_contrast.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.dynamic_contrast"),
								(button, value) -> this.config.dynamicContrast = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.offset_x", -100, 100, "%+d px",
						this.config.offsetX,
						value -> this.config.offsetX = value).tooltip("ifuto-armor-hud.config.offset_x.tooltip")));

		content.add(row(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.offset_y", -100, 100, "%+d px",
						this.config.offsetY,
						value -> this.config.offsetY = value).tooltip("ifuto-armor-hud.config.offset_y.tooltip"),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.hud_scale", 50, 150, "%d%%",
						this.config.hudScale,
						value -> this.config.hudScale = value).tooltip("ifuto-armor-hud.config.hud_scale.tooltip")));

		content.add(row(
				CyclingButtonWidget.<ShowCondition>builder(ShowCondition::getText, this.config.showCondition)
						.values(ShowCondition.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.show_condition.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.show_condition"),
								(button, value) -> this.config.showCondition = value),
				CyclingButtonWidget.onOffBuilder(this.config.breakAlert)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.break_alert.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.break_alert"),
								(button, value) -> this.config.breakAlert = value)));

		// Discord Client ID は全幅
		TextFieldWidget clientIdField = new TextFieldWidget(this.textRenderer,
				0, 0, WIDGET_WIDTH * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-armor-hud.config.client_id"));
		clientIdField.setMaxLength(32);
		clientIdField.setText(this.config.discordClientId);
		clientIdField.setPlaceholder(Text.translatable("ifuto-armor-hud.config.client_id.placeholder"));
		clientIdField.setTooltip(Tooltip.of(Text.translatable("ifuto-armor-hud.config.client_id.tooltip")));
		clientIdField.setChangedListener(value -> this.config.discordClientId = value.trim());
		content.add(row(clientIdField, null));

		this.scrollable = new ScrollableLayoutWidget(this.client, content, SCROLL_MIN_HEIGHT);
		this.scrollable.setWidth(WIDGET_WIDTH * 2 + COLUMN_GAP + 8);
		body.add(this.scrollable);

		DirectionalLayoutWidget footer = this.layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP));
		footer.add(ButtonWidget.builder(Text.translatable("ifuto-armor-hud.config.reset"), button -> {
			this.config.resetToDefaults();
			this.config.save();
			this.clearAndInit();
		}).width(WIDGET_WIDTH).build());
		footer.add(ButtonWidget.builder(Text.translatable("gui.done"), button -> this.close())
				.width(WIDGET_WIDTH).build());

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
	}

	private static LayoutWidget row(ClickableWidget left, ClickableWidget right) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP);
		row.add(left);
		if (right != null) {
			row.add(right);
		}
		return row;
	}

	@Override
	protected void refreshWidgetPositions() {
		this.scrollable.setHeight(SCROLL_MIN_HEIGHT);
		this.layout.refreshPositions();
		// 余った分だけスクロール領域を伸ばす（バニラのワールド生成画面と同じやり方）
		int extra = this.height - this.layout.getFooterHeight() - this.scrollable.getNavigationFocus().getBottom();
		this.scrollable.setHeight(this.scrollable.getHeight() + extra);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
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

		OptionSlider tooltip(String key) {
			this.setTooltip(Tooltip.of(Text.translatable(key)));
			return this;
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
