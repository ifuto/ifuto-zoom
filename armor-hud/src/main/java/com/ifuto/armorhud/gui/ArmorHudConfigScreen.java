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
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Mod Menu から開く設定画面。バニラのオプション画面っぽい見た目にしている。
 * ウィンドウの縦が短くても下まで届くよう、項目はスクロールするリストに入れている。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 158;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 8;
	private static final int ROW_HEIGHT = 24;
	private static final int HEADER = 40; // タイトル+ヒント分
	private static final int FOOTER = 36; // ボタン分

	private final Screen parent;
	private final ArmorHudConfig config;
	private HudOptionsList list;

	public ArmorHudConfigScreen(Screen parent) {
		super(Text.translatable("ifuto-armor-hud.config.title"));
		this.parent = parent;
		this.config = ArmorHudConfig.get();
	}

	@Override
	protected void init() {
		this.list = this.addDrawableChild(
				new HudOptionsList(this.client, this.width, this.height - FOOTER - HEADER, HEADER, ROW_HEIGHT));

		// 1行目: 表示 / 位置
		this.list.addEntry(HudOptionsList.Row.of(
				CyclingButtonWidget.onOffBuilder(this.config.showHud)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.show_hud.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.show_hud"),
								(button, value) -> this.config.showHud = value),
				CyclingButtonWidget.<HudPosition>builder(HudPosition::getText, this.config.position)
						.values(HudPosition.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.position.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.position"),
								(button, value) -> this.config.position = value)));

		// 2行目: 並び方 / 背景
		this.list.addEntry(HudOptionsList.Row.of(
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

		// 3行目: 空きスロット / ホットバーとの距離
		this.list.addEntry(HudOptionsList.Row.of(
				CyclingButtonWidget.<EmptySlotMode>builder(EmptySlotMode::getText, this.config.emptyMode)
						.values(EmptySlotMode.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.empty.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.empty"),
								(button, value) -> this.config.emptyMode = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.hotbar_gap", 0, 64, "%d px",
						this.config.hotbarGap,
						value -> this.config.hotbarGap = value).tooltip("ifuto-armor-hud.config.hotbar_gap.tooltip")));

		// 4行目: スロット間隔 / 枠の中
		this.list.addEntry(HudOptionsList.Row.of(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.slot_gap", 0, 16, "%d px",
						this.config.slotGap,
						value -> this.config.slotGap = value).tooltip("ifuto-armor-hud.config.slot_gap.tooltip"),
				CyclingButtonWidget.<InfoMode>builder(InfoMode::getText, this.config.inside)
						.values(InfoMode.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.inside.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.inside"),
								(button, value) -> this.config.inside = value)));

		// 5行目: 枠の外 / 外の表示位置
		this.list.addEntry(HudOptionsList.Row.of(
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

		// 6行目: ピンチで点滅 / しきい値
		this.list.addEntry(HudOptionsList.Row.of(
				CyclingButtonWidget.onOffBuilder(this.config.warnBlink)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.warn_blink.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.warn_blink"),
								(button, value) -> this.config.warnBlink = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.warn_percent", 1, 50, "%d%%",
						this.config.warnPercent,
						value -> this.config.warnPercent = value).tooltip("ifuto-armor-hud.config.warn_percent.tooltip")));

		// 7行目: コントラスト / 横オフセット
		this.list.addEntry(HudOptionsList.Row.of(
				CyclingButtonWidget.onOffBuilder(this.config.dynamicContrast)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.dynamic_contrast.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.dynamic_contrast"),
								(button, value) -> this.config.dynamicContrast = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.offset_x", -100, 100, "%+d px",
						this.config.offsetX,
						value -> this.config.offsetX = value).tooltip("ifuto-armor-hud.config.offset_x.tooltip")));

		// 8行目: 縦オフセット / HUD の大きさ
		this.list.addEntry(HudOptionsList.Row.of(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.offset_y", -100, 100, "%+d px",
						this.config.offsetY,
						value -> this.config.offsetY = value).tooltip("ifuto-armor-hud.config.offset_y.tooltip"),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
						"ifuto-armor-hud.config.hud_scale", 50, 150, "%d%%",
						this.config.hudScale,
						value -> this.config.hudScale = value).tooltip("ifuto-armor-hud.config.hud_scale.tooltip")));

		// 9行目: 表示条件 / 壊れたら通知
		this.list.addEntry(HudOptionsList.Row.of(
				CyclingButtonWidget.<ShowCondition>builder(ShowCondition::getText, this.config.showCondition)
						.values(ShowCondition.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.show_condition.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.show_condition"),
								(button, value) -> this.config.showCondition = value),
				CyclingButtonWidget.onOffBuilder(this.config.breakAlert)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-armor-hud.config.break_alert.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-armor-hud.config.break_alert"),
								(button, value) -> this.config.breakAlert = value)));

		// 10行目: Discord Client ID（全幅）
		TextFieldWidget clientIdField = new TextFieldWidget(this.textRenderer,
				0, 0, WIDGET_WIDTH * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-armor-hud.config.client_id"));
		clientIdField.setMaxLength(32);
		clientIdField.setText(this.config.discordClientId);
		clientIdField.setPlaceholder(Text.translatable("ifuto-armor-hud.config.client_id.placeholder"));
		clientIdField.setTooltip(Tooltip.of(Text.translatable("ifuto-armor-hud.config.client_id.tooltip")));
		clientIdField.setChangedListener(value -> this.config.discordClientId = value.trim());
		this.list.addEntry(HudOptionsList.Row.of(clientIdField));

		// 下部は固定（リストの外）
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("ifuto-armor-hud.config.reset"), button -> {
			this.config.resetToDefaults();
			this.config.save();
			this.clearAndInit();
		}).dimensions(this.width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2, this.height - FOOTER + 4,
				WIDGET_WIDTH, WIDGET_HEIGHT).build());

		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> this.close())
				.dimensions(this.width / 2 + COLUMN_GAP / 2, this.height - FOOTER + 4,
						WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFFFF);
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

	// バニラのオプション画面と同じ、スクロールするリスト
	private static class HudOptionsList extends EntryListWidget<HudOptionsList.Row> {

		HudOptionsList(MinecraftClient client, int width, int height, int y, int itemHeight) {
			super(client, width, height, y, itemHeight);
		}

		private static class Row extends EntryListWidget.Entry<Row> {
			private final List<ClickableWidget> widgets = new ArrayList<>();

			private Row(ClickableWidget... widgets) {
				this.widgets.addAll(List.of(widgets));
			}

			static Row of(ClickableWidget... widgets) {
				return new Row(widgets);
			}

			@Override
			public List<? extends Element> children() {
				return this.widgets;
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return this.widgets;
			}

			@Override
			public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
				// 行の中に2列グリッド式に並べる。全幅の部品は1つだけ引き伸ばす
				int widgetWidth = this.widgets.size() == 1 ? WIDGET_WIDTH * 2 + COLUMN_GAP : WIDGET_WIDTH;
				int left = this.getContentMiddleX() - (WIDGET_WIDTH * 2 + COLUMN_GAP) / 2;
				int yy = this.getContentMiddleY() - WIDGET_HEIGHT / 2;
				for (int i = 0; i < this.widgets.size(); i++) {
					ClickableWidget widget = this.widgets.get(i);
					int wx = this.widgets.size() == 1 || i == 0 ? left : left + WIDGET_WIDTH + COLUMN_GAP;
					widget.setWidth(widgetWidth);
					widget.setPosition(wx, yy);
					widget.render(context, mouseX, mouseY, tickDelta);
				}
			}
		}
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
