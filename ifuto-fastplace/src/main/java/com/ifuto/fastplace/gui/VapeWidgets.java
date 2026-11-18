package com.ifuto.fastplace.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * The Client. の見た目（暗いパネル・細いスライダー・小さなピル）を描く部品。
 * 設定画面どうしで色と形を揃えるためだけのクラス。
 */
public final class VapeWidgets {
	public static final int WINDOW = 0xFF181819;
	public static final int BORDER = 0xFF242426;
	public static final int HOVER = 0xFF1F1F21;
	public static final int SECTION = 0xFF151516;
	public static final int ACCENT = 0xFF16A37F;
	public static final int TEXT = 0xFFD4D4D8;
	public static final int MUTED = 0xFF9E9EA4;
	public static final int DIM = 0xFF5E5E62;
	public static final int TRACK = 0xFF27272A;
	public static final int TRACK_OFF = 0xFF2A2A2E;
	public static final int CHIP = 0xFF232326;
	public static final int CHIP_BORDER = 0xFF2D2D30;
	public static final int SHADOW = 0x44000000;

	private VapeWidgets() {
	}

	/** 角を落とした長方形（アンチエイリアス無しの角丸風） */
	public static void chamfer(DrawContext context, int x, int y, int w, int h, int radius, int color) {
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (r == 0) {
			context.fill(x, y, x + w, y + h, color);
			return;
		}
		context.fill(x + r, y, x + w - r, y + h, color);
		context.fill(x, y + r, x + r, y + h - r, color);
		context.fill(x + w - r, y + r, x + w, y + h - r, color);
	}

	/** 影つきのパネル */
	public static void panel(DrawContext context, int x, int y, int w, int h) {
		chamfer(context, x, y + 4, w, h, 3, SHADOW);
		chamfer(context, x, y, w, h, 3, BORDER);
		chamfer(context, x + 1, y + 1, w - 2, h - 2, 2, WINDOW);
	}

	public static void text(DrawContext context, TextRenderer renderer, String value,
			int x, int y, int color, float scale) {
		if (scale == 1.0f) {
			context.drawText(renderer, value, x, y, color, false);
			return;
		}
		var matrices = context.getMatrices();
		matrices.push();
		matrices.translate(x, y, 0.0f);
		matrices.scale(scale, scale, 1.0f);
		context.drawText(renderer, value, 0, 0, color, false);
		matrices.pop();
	}

	public static void pill(DrawContext context, int x, int y, int w, int h, boolean on) {
		chamfer(context, x, y, w, h, h / 2, on ? ACCENT : TRACK_OFF);
		int knob = h - 4;
		int kx = on ? x + w - knob - 2 : x + 2;
		chamfer(context, kx, y + 2, knob, knob, knob / 2, 0xFFFFFFFF);
	}

	/** スライダーのトラック（0..1 の値） */
	public static void slider(DrawContext context, int x, int y, int w, double t, boolean hover) {
		int trackY = y;
		context.fill(x, trackY, x + w, trackY + 4, TRACK);
		int filled = (int) Math.round(w * Math.max(0.0, Math.min(1.0, t)));
		if (filled > 0) {
			context.fill(x, trackY, x + filled, trackY + 4, ACCENT);
		}
		int knobX = x + filled - 5;
		int knobSize = hover ? 12 : 10;
		chamfer(context, knobX, trackY + 2 - knobSize / 2, knobSize, knobSize, knobSize / 2, 0xFFFFFFFF);
		chamfer(context, knobX + 2, trackY + 4 - knobSize / 2, knobSize - 4, knobSize - 4,
			(knobSize - 4) / 2, ACCENT);
	}

	public static String format(double value, String unit) {
		String number = Math.abs(value - Math.rint(value)) < 1.0e-6
			? Long.toString(Math.round(value))
			: String.format(java.util.Locale.ROOT, "%.2f", value);
		return unit.isEmpty() ? number : number + unit;
	}
}
