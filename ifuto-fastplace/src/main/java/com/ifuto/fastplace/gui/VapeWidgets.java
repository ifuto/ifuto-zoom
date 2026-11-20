package com.ifuto.fastplace.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * 設定画面で共通に使う見た目。
 *
 * <p>以前は 45° の面取り（カクカク）だったが、いまは {@link Ui} の角丸で描く。
 * 文字は等倍・影なしにして、拡大による「ドットが目立つ」見た目を避けている。</p>
 */
public final class VapeWidgets {
	/** パネルの地 */
	public static final int WINDOW = 0xE0151518;
	public static final int BORDER = Ui.BORDER;
	public static final int HOVER = Ui.HOVER;
	public static final int SECTION = 0xD0121215;
	public static final int ACCENT = Ui.ACCENT;
	public static final int TEXT = Ui.TEXT;
	public static final int MUTED = Ui.MUTED;
	public static final int DIM = Ui.DIM;
	public static final int TRACK = 0xFF2B2B32;
	public static final int TRACK_OFF = 0xFF2C2C33;
	public static final int CHIP = 0xE023232A;
	public static final int CHIP_BORDER = Ui.BORDER;
	public static final int SHADOW = Ui.SHADOW;

	private VapeWidgets() {
	}

	/** 角丸の長方形（旧・面取り）。呼び出し側の半径をそのまま使う */
	public static void chamfer(DrawContext context, int x, int y, int w, int h, int radius, int color) {
		Ui.roundRect(context, x, y, w, h, radius <= 2 ? radius + 4 : radius, color);
	}

	/** 影つきパネル */
	public static void panel(DrawContext context, int x, int y, int w, int h) {
		Ui.shadow(context, x, y, w, h, Ui.RADIUS);
		Ui.border(context, x, y, w, h, Ui.RADIUS, BORDER, WINDOW);
	}

	/** 画面タイトル（大文字にしない・等倍） */
	public static void title(DrawContext context, TextRenderer renderer, int x, int y,
			String title, String subtitle) {
		Ui.text(context, renderer, title, x, y, TEXT, 1.0f);
		Ui.roundRect(context, x, y + 11, 18, 2, 1, ACCENT);
		Ui.text(context, renderer, subtitle, x, y + 17, DIM, 0.8f);
	}

	public static void text(DrawContext context, TextRenderer renderer, String value,
			int x, int y, int color, float scale) {
		Ui.text(context, renderer, value, x, y, color, scale);
	}

	public static void pill(DrawContext context, int x, int y, int w, int h, boolean on) {
		float t = Ui.anim("pill:" + x + ":" + y, on);
		Ui.toggle(context, x, y, w, h, t);
	}

	public static void slider(DrawContext context, int x, int y, int w, double t, boolean hover) {
		Ui.slider(context, x, y + 2, w, t, hover);
	}

	public static String format(double value, String unit) {
		String number = Math.abs(value - Math.rint(value)) < 1.0e-6
			? Long.toString(Math.round(value))
			: String.format(java.util.Locale.ROOT, "%.2f", value);
		return unit.isEmpty() ? number : number + unit;
	}
}
