package com.ifuto.zoom.gui;

import com.ifuto.zoom.ZoomState;
import com.ifuto.zoom.config.EasingType;
import com.ifuto.zoom.config.ZoomConfig;
import com.ifuto.zoom.config.ZoomMode;
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

import java.util.function.DoubleConsumer;

/**
 * Vanilla styled options screen, opened from Mod Menu.
 */
@Environment(EnvType.CLIENT)
public class ZoomConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 158;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 8;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	private final ZoomConfig config;

	public ZoomConfigScreen(Screen parent) {
		super(Text.translatable("ifuto-zoom.config.title"));
		this.parent = parent;
		this.config = ZoomConfig.get();
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2;
		int right = this.width / 2 + COLUMN_GAP / 2;
		int y = 40;

		// Row 1: mode + easing
		this.addDrawableChild(CyclingButtonWidget.<ZoomMode>builder(ZoomMode::getText, this.config.mode)
				.values(ZoomMode.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-zoom.config.mode.tooltip")))
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-zoom.config.mode"),
						(button, value) -> {
							this.config.mode = value;
							ZoomState.setActive(false);
						}));

		this.addDrawableChild(CyclingButtonWidget.<EasingType>builder(EasingType::getText, this.config.easing)
				.values(EasingType.values())
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-zoom.config.easing.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-zoom.config.easing"),
						(button, value) -> this.config.easing = value));

		// Row 2: default zoom + animation duration
		y += ROW_HEIGHT;
		this.addDrawableChild(new OptionSlider(left, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.default_zoom", 1.0D, ZoomConfig.MAX_CONFIGURABLE_ZOOM, 0.1D,
				this.config.defaultZoom,
				value -> Text.literal(String.format("%.1fx", value)),
				value -> {
					this.config.defaultZoom = value;
					ZoomState.reset();
				}));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.ease_duration", 0.0D, 1500.0D, 25.0D,
				this.config.easeDurationMs,
				value -> value <= 0.0D
						? Text.translatable("ifuto-zoom.config.ease_duration.off")
						: Text.literal(String.format("%d ms", (int) value)),
				value -> this.config.easeDurationMs = (int) Math.round(value)));

		// Row 3: minimum zoom + scroll step
		y += ROW_HEIGHT;
		this.addDrawableChild(new OptionSlider(left, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.min_zoom", ZoomConfig.HARD_MIN_ZOOM, 5.0D, 0.05D,
				this.config.minZoom,
				value -> Text.literal(String.format("%.2fx", value)),
				value -> this.config.minZoom = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.scroll_step", 1.01D, 2.0D, 0.01D,
				this.config.scrollStep,
				value -> Text.literal(String.format("%.0f%%", (value - 1.0D) * 100.0D)),
				value -> this.config.scrollStep = value));

		// Row 4: scroll to zoom + keep zoom level
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.scrollToZoom)
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-zoom.config.scroll_to_zoom.tooltip")))
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-zoom.config.scroll_to_zoom"),
						(button, value) -> this.config.scrollToZoom = value));

		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.keepZoomLevel)
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-zoom.config.keep_zoom_level.tooltip")))
				.build(right, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-zoom.config.keep_zoom_level"),
						(button, value) -> this.config.keepZoomLevel = value));

		// Row 5: sensitivity
		y += ROW_HEIGHT;
		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.reduceSensitivity)
				.tooltip(value -> Tooltip.of(Text.translatable("ifuto-zoom.config.reduce_sensitivity.tooltip")))
				.build(left, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-zoom.config.reduce_sensitivity"),
						(button, value) -> this.config.reduceSensitivity = value));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.sensitivity_strength", 0.0D, 1.0D, 0.05D,
				this.config.sensitivityStrength,
				value -> Text.literal(String.format("%.0f%%", value * 100.0D)),
				value -> this.config.sensitivityStrength = value));

		// Row 6: scroll smoothing + curve strength
		y += ROW_HEIGHT;
		this.addDrawableChild(new OptionSlider(left, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.scroll_smooth", 0.0D, 400.0D, 5.0D,
				this.config.scrollSmoothMs,
				value -> value <= 0.0D
						? Text.translatable("ifuto-zoom.config.scroll_smooth.off")
						: Text.literal(String.format("%d ms", (int) value)),
				value -> this.config.scrollSmoothMs = (int) Math.round(value)));

		this.addDrawableChild(new OptionSlider(right, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"ifuto-zoom.config.easing_power", EasingType.MIN_POWER, 5.0D, 0.1D,
				this.config.easingPower,
				value -> Text.literal(String.format("%.1f", value)),
				value -> this.config.easingPower = value));

		// Footer
		y += ROW_HEIGHT + 8;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("ifuto-zoom.config.reset"), button -> {
			this.config.resetToDefaults();
			this.config.save();
			ZoomState.reset();
			this.clearAndInit();
		}).dimensions(left, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());

		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> this.close())
				.dimensions(right, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("ifuto-zoom.config.hint"),
				this.width / 2, 26, 0xFFA0A0A0);
	}

	@Override
	public void close() {
		this.config.save();
		ZoomState.reset();
		MinecraftClient.getInstance().setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.config.save();
		super.removed();
	}

	/**
	 * A slider that maps its {@code [0, 1]} position onto a snapped double range.
	 */
	private static class OptionSlider extends SliderWidget {
		private final String labelKey;
		private final double min;
		private final double max;
		private final double step;
		private final ValueFormatter formatter;
		private final DoubleConsumer setter;

		OptionSlider(int x, int y, int width, int height, String labelKey, double min, double max, double step,
					 double initialValue, ValueFormatter formatter, DoubleConsumer setter) {
			super(x, y, width, height, Text.empty(), (initialValue - min) / (max - min));
			this.labelKey = labelKey;
			this.min = min;
			this.max = max;
			this.step = step;
			this.formatter = formatter;
			this.setter = setter;
			this.updateMessage();
		}

		private double snappedValue() {
			double raw = this.min + (this.max - this.min) * this.value;
			double snapped = Math.round(raw / this.step) * this.step;
			return Math.min(this.max, Math.max(this.min, snapped));
		}

		@Override
		protected void updateMessage() {
			if (this.formatter == null) {
				return;
			}

			this.setMessage(Text.translatable(this.labelKey, this.formatter.format(this.snappedValue())));
		}

		@Override
		protected void applyValue() {
			if (this.setter == null) {
				return;
			}

			this.setter.accept(this.snappedValue());
		}
	}

	@FunctionalInterface
	private interface ValueFormatter {
		Text format(double value);
	}
}
