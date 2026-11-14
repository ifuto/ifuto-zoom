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

/**
 * FastPlace の設定画面。モダンなパネル + スライダー。
 */
public final class FastPlaceConfigScreen extends Screen {
	private static final int PANEL_W = 400;
	private static final int PANEL_H = 240;
	private static final int ACCENT = 0xFFFFB020;
	private static final int PANEL_BG = 0xE0141418;
	private static final int TRACK = 0xFF2C2C36;
	private static final int TRACK_OFF = 0xFF3A3A46;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;

	// スライダー
	private int sliderX;
	private int sliderY;
	private int sliderW;
	private boolean dragging = false;

	public FastPlaceConfigScreen(Screen parent) {
		super(Text.literal("FastPlace 設定"));
		this.parent = parent;
		this.cfg = FastPlaceConfig.get();
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;

		sliderX = panelX + 24;
		sliderW = PANEL_W - 48;

		addDrawableChild(ButtonWidget.builder(Text.literal("完了"), b -> close())
			.dimensions(panelX + PANEL_W - 80, panelY + PANEL_H - 36, 64, 22).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);

		context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
		border(context, panelX, panelY, PANEL_W, PANEL_H, TRACK);

		// ヘッダ
		context.drawTextWithShadow(textRenderer,
			Text.literal("FASTPLACE").formatted(Formatting.WHITE)
				.append(Text.literal("  設定").formatted(Formatting.GRAY)),
			panelX + 20, panelY + 16, 0xFFFFFFFF);
		context.fill(panelX + 20, panelY + 32, panelX + 20 + 44, panelY + 34, ACCENT);

		// 有効トグル
		toggleRow(context, mouseX, mouseY, "有効", "右クリック長押しで連続設置（常時 ON 推奨）",
			cfg.enabled, 56);
		// CPS 表示トグル
		toggleRow(context, mouseX, mouseY, "CPS を表示", "連打中だけ CPS を画面右上に出す",
			cfg.hudCps, 106);

		// 間隔スライダー
		int rowY = panelY + 152;
		context.drawTextWithShadow(textRenderer, Text.literal("設置間隔").formatted(Formatting.WHITE),
			panelX + 24, rowY, 0xFFFFFFFF);
		String value = cfg.intervalMs + " ms  (" + String.format(java.util.Locale.ROOT, "%.1f", 1000.0 / cfg.intervalMs) + " CPS)";
		int vw = textRenderer.getWidth(value);
		context.drawTextWithShadow(textRenderer, Text.literal(value).formatted(Formatting.GRAY),
			panelX + PANEL_W - 24 - vw, rowY, 0xFF9A9AA5);

		sliderY = rowY + 20;
		double t = (cfg.intervalMs - FastPlaceConfig.MIN_INTERVAL_MS)
			/ (double) (FastPlaceConfig.MAX_INTERVAL_MS - FastPlaceConfig.MIN_INTERVAL_MS);
		context.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 6, TRACK);
		int fillW = (int) (sliderW * t);
		context.fill(sliderX, sliderY, sliderX + fillW, sliderY + 6, ACCENT);
		int knobX = sliderX + fillW;
		context.fill(knobX - 5, sliderY - 4, knobX + 5, sliderY + 10, 0xFFFFFFFF);
		context.fill(knobX - 4, sliderY - 3, knobX + 4, sliderY + 9, ACCENT);

		// フッター
		context.drawTextWithShadow(textRenderer,
			Text.literal("間隔には人間らしいブレ・疲労・たまの外れ値が乗ります").formatted(Formatting.GRAY),
			panelX + 20, panelY + PANEL_H - 30, 0xFF6E6E78);

		super.render(context, mouseX, mouseY, delta);
	}

	private void toggleRow(DrawContext context, int mouseX, int mouseY, String name, String desc, boolean on, int yOffset) {
		int x = panelX + 20;
		int w = PANEL_W - 40;
		int y = panelY + yOffset;
		int h = 44;

		context.fill(x, y, x + w, y + h, 0xFF1F1F26);
		border(context, x, y, w, h, TRACK);
		context.fill(x, y, x + 3, y + h, on ? ACCENT : TRACK_OFF);

		context.drawTextWithShadow(textRenderer, Text.literal(name).formatted(Formatting.WHITE),
			x + 14, y + 7, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(desc).formatted(Formatting.GRAY),
			x + 14, y + 24, 0xFF9A9AA5);

		pill(context, x + w - 56, y + (h - 18) / 2, on);
	}

	private void pill(DrawContext context, int x, int y, boolean on) {
		int w = 40;
		int h = 18;
		context.fill(x, y, x + w, y + h, on ? ACCENT : TRACK_OFF);
		int knob = h - 6;
		int kx = on ? x + w - knob - 3 : x + 3;
		context.fill(kx, y + 3, kx + knob, y + 3 + knob, 0xFFFFFFFF);
	}

	private static void border(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}

	private boolean pillHit(double mouseX, double mouseY, int yOffset) {
		int x = panelX + 20 + PANEL_W - 40 - 56;
		int y = panelY + yOffset + (44 - 18) / 2;
		return mouseX >= x && mouseX <= x + 40 && mouseY >= y && mouseY <= y + 18;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			double mx = click.x();
			double my = click.y();
			if (pillHit(mx, my, 56)) {
				cfg.enabled = !cfg.enabled;
				cfg.save();
				return true;
			}
			if (pillHit(mx, my, 106)) {
				cfg.hudCps = !cfg.hudCps;
				cfg.save();
				return true;
			}
			// スライダー
			if (my >= sliderY - 6 && my <= sliderY + 12 && mx >= sliderX - 6 && mx <= sliderX + sliderW + 6) {
				dragging = true;
				updateSlider(mx);
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging && click.button() == 0) {
			updateSlider(click.x());
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (click.button() == 0) {
			dragging = false;
		}
		return super.mouseReleased(click);
	}

	private void updateSlider(double mx) {
		double t = (mx - sliderX) / sliderW;
		t = Math.max(0.0, Math.min(1.0, t));
		int value = FastPlaceConfig.MIN_INTERVAL_MS
			+ (int) Math.round(t * (FastPlaceConfig.MAX_INTERVAL_MS - FastPlaceConfig.MIN_INTERVAL_MS));
		if (value != cfg.intervalMs) {
			cfg.intervalMs = value;
			cfg.save();
		}
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (keyInput.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
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
}
