package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Aim Assist（近接）の設定。
 */
public final class AimAssistSettingsScreen extends Screen {
	private static final int PANEL_W = 440;
	private static final int PANEL_H = 360;
	private static final int ACCENT = 0xFF3D8BFF;
	private static final int PANEL_BG = 0xF0131317;
	private static final int CARD_BG = 0xFF1A1A20;
	private static final int BORDER = 0xFF2A2A33;
	private static final int TRACK = 0xFF2C2C36;
	private static final int TRACK_OFF = 0xFF3A3A46;
	private static final int TEXT_DIM = 0xFF8B8B96;
	private static final int TEXT_FAINT = 0xFF5C5C66;

	private static final int ROW_H = 34;
	private static final int ROW_GAP = 5;
	private static final int ROW_START = 50;

	private final Screen parent;
	private final FastPlaceConfig cfg;
	private final List<Slider> sliders = new ArrayList<>();

	private int panelX;
	private int panelY;
	private Slider dragging;

	public AimAssistSettingsScreen(Screen parent) {
		super(Text.literal("Aim Assist 設定"));
		this.parent = parent;
		this.cfg = FastPlaceConfig.get();
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("完了"), b -> close())
			.dimensions(panelX + PANEL_W - 90, panelY + PANEL_H - 32, 68, 22).build());

		sliders.clear();
		int x = panelX + 22;
		int w = PANEL_W - 44;
		sliders.add(new Slider("アシストの強さ", 0.20, 1.00, 0.05, "%.2fx",
			() -> cfg.aimAssistStrength, v -> cfg.aimAssistStrength = v,
			x, panelY + 232, w));
		sliders.add(new Slider("視野（全角）", 20.0, 170.0, 5.0, "%.0f°",
			() -> cfg.aimAssistFov, v -> cfg.aimAssistFov = v,
			x, panelY + 276, w));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);

		context.fill(panelX + 3, panelY + 4, panelX + PANEL_W + 3, panelY + PANEL_H + 4, 0x55000000);
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
		border(context, panelX, panelY, PANEL_W, PANEL_H, BORDER);
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + 2, ACCENT);

		context.drawTextWithShadow(textRenderer,
			Text.literal("AIM ASSIST").formatted(Formatting.WHITE)
				.append(Text.literal("  Melee").formatted(Formatting.GRAY)),
			panelX + 20, panelY + 13, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer,
			Text.literal("相手に人間の速度で寄るだけ。視点は奪いません"),
			panelX + 20, panelY + 28, TEXT_DIM);

		toggleRow(context, 0, "人間らしさ", "反応時間・振戦・予測を使う", cfg.aimAssistHumanize);
		toggleRow(context, 1, "攻撃中だけ", "左クリックを押している間だけ効かせる", cfg.aimAssistOnlyWhileAttacking);
		toggleRow(context, 2, "剣・斧のみ", "近接武器を持っているときだけ効かせる", cfg.aimAssistWeaponsOnly);
		toggleRow(context, 3, "先読み", "相手の速度ぶん先を狙って遅れを埋める", cfg.aimAssistPredict);

		for (Slider slider : sliders) {
			drawSlider(context, slider);
		}

		context.drawTextWithShadow(textRenderer,
			Text.literal("当たり判定の内側から外れない範囲でだけ回します"),
			panelX + 22, panelY + 300, TEXT_FAINT);
		context.drawTextWithShadow(textRenderer,
			Text.literal("RShift / ESC でモジュール一覧へ"),
			panelX + 22, panelY + 314, TEXT_FAINT);

		super.render(context, mouseX, mouseY, delta);
	}

	private void toggleRow(DrawContext context, int index, String name, String description, boolean on) {
		int x = panelX + 22;
		int w = PANEL_W - 44;
		int y = panelY + ROW_START + index * (ROW_H + ROW_GAP);

		context.fill(x, y, x + w, y + ROW_H, CARD_BG);
		border(context, x, y, w, ROW_H, BORDER);
		context.fill(x, y, x + 3, y + ROW_H, on ? ACCENT : TRACK_OFF);

		context.drawTextWithShadow(textRenderer, Text.literal(name).formatted(Formatting.WHITE),
			x + 14, y + 5, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(description),
			x + 14, y + 18, TEXT_DIM);

		pill(context, x + w - 52, y + (ROW_H - 18) / 2, on);
	}

	private void pill(DrawContext context, int x, int y, boolean on) {
		int w = 40;
		int h = 18;
		context.fill(x, y, x + w, y + h, on ? ACCENT : TRACK_OFF);
		int knob = h - 6;
		int kx = on ? x + w - knob - 3 : x + 3;
		context.fill(kx, y + 3, kx + knob, y + 3 + knob, 0xFFFFFFFF);
	}

	private boolean hitToggle(double mx, double my, int index) {
		int x = panelX + 22 + (PANEL_W - 44) - 52;
		int y = panelY + ROW_START + index * (ROW_H + ROW_GAP) + (ROW_H - 18) / 2;
		return mx >= x && mx <= x + 40 && my >= y && my <= y + 18;
	}

	private void drawSlider(DrawContext context, Slider slider) {
		double value = slider.get.getAsDouble();
		double t = (value - slider.min) / (slider.max - slider.min);
		t = Math.max(0.0, Math.min(1.0, t));

		context.drawTextWithShadow(textRenderer, Text.literal(slider.label).formatted(Formatting.WHITE),
			slider.x, slider.y - 16, 0xFFFFFFFF);
		String text = String.format(Locale.ROOT, slider.format, value);
		context.drawTextWithShadow(textRenderer, Text.literal(text),
			slider.x + slider.w - textRenderer.getWidth(text), slider.y - 16, TEXT_DIM);

		context.fill(slider.x, slider.y, slider.x + slider.w, slider.y + 6, TRACK);
		int fill = (int) Math.round(slider.w * t);
		context.fill(slider.x, slider.y, slider.x + fill, slider.y + 6, ACCENT);
		int knobX = slider.x + fill;
		context.fill(knobX - 5, slider.y - 4, knobX + 5, slider.y + 10, 0xFFFFFFFF);
		context.fill(knobX - 4, slider.y - 3, knobX + 4, slider.y + 9, ACCENT);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			double mx = click.x();
			double my = click.y();
			for (int i = 0; i < 4; i++) {
				if (hitToggle(mx, my, i)) {
					switch (i) {
						case 0 -> cfg.aimAssistHumanize = !cfg.aimAssistHumanize;
						case 1 -> cfg.aimAssistOnlyWhileAttacking = !cfg.aimAssistOnlyWhileAttacking;
						case 2 -> cfg.aimAssistWeaponsOnly = !cfg.aimAssistWeaponsOnly;
						default -> cfg.aimAssistPredict = !cfg.aimAssistPredict;
					}
					cfg.save();
					return true;
				}
			}
			for (Slider slider : sliders) {
				if (slider.hit(mx, my)) {
					dragging = slider;
					slider.apply(mx);
					cfg.save();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging != null && click.button() == 0) {
			dragging.apply(click.x());
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (click.button() == 0 && dragging != null) {
			dragging = null;
			cfg.save();
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(keyInput);
	}

	@Override
	public void close() {
		cfg.save();
		MinecraftClient.getInstance().setScreen(parent);
	}

	private static void border(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}

	/** 1 本のスライダー。値の置き場だけ外から差し替える */
	private static final class Slider {
		final String label;
		final double min;
		final double max;
		final double step;
		final String format;
		final DoubleSupplier get;
		final DoubleConsumer set;
		final int x;
		final int y;
		final int w;

		Slider(String label, double min, double max, double step, String format,
				DoubleSupplier get, DoubleConsumer set, int x, int y, int w) {
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.format = format;
			this.get = get;
			this.set = set;
			this.x = x;
			this.y = y;
			this.w = w;
		}

		boolean hit(double mx, double my) {
			return mx >= x - 6 && mx <= x + w + 6 && my >= y - 10 && my <= y + 14;
		}

		void apply(double mx) {
			double t = (mx - x) / (double) w;
			t = Math.max(0.0, Math.min(1.0, t));
			double value = min + t * (max - min);
			value = Math.round(value / step) * step;
			value = Math.max(min, Math.min(max, value));
			if (value != get.getAsDouble()) {
				set.accept(value);
			}
		}
	}
}
