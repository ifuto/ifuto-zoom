package com.ifuto.replay.gui.theme;

import net.minecraft.client.gui.DrawContext;

/**
 * 画面の見た目をまとめて決める場所（色と「丸い四角」の描き方）。
 *
 * <p>Minecraft のGUIは四角い部品が多いので、**角を丸くする描き方** と
 * 落ち着いた暗い色 + アクセント色（水色）を持ち込んで、今っぽい見た目にしている。
 *
 * <p>色は `0xAARRGGBB`。バニラの `DrawContext#fill` にそのまま渡せる。
 */
public final class ReplayTheme {
	/** 面（いちばん下） */
	public static final int SURFACE = 0xF2101418;

	/** 一段上（ボタンなど） */
	public static final int SURFACE_RAISED = 0xF21A2027;

	/** さらに上（入力欄など） */
	public static final int SURFACE_INPUT = 0xF20D1116;

	/** ふち */
	public static final int BORDER = 0x24FFFFFF;

	/** 上のハイライト（ガラスっぽく見せる1本線） */
	public static final int HIGHLIGHT = 0x14FFFFFF;

	/** アクセント（水色） */
	public static final int ACCENT = 0xFF38BDF8;

	/** アクセント（薄い。塗り用） */
	public static final int ACCENT_SOFT = 0x3338BDF8;

	/** 録画中（赤） */
	public static final int RECORD = 0xFFF43F5E;

	/** 録画中（薄い） */
	public static final int RECORD_SOFT = 0x33F43F5E;

	/** 文字 */
	public static final int TEXT = 0xFFE6EDF3;

	/** 文字（弱い） */
	public static final int TEXT_DIM = 0xFF8B98A5;

	/** 影 */
	public static final int SHADOW = 0x30000000;

	private ReplayTheme() {
	}

	/** 透明度だけ差し替える */
	public static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	/** 2つの色を混ぜる（t は 0〜1） */
	public static int mix(int from, int to, float t) {
		float clamped = Math.max(0F, Math.min(1F, t));
		int a = (int) Math.round(((from >>> 24) & 0xFF) * (1F - clamped) + ((to >>> 24) & 0xFF) * clamped);
		int r = (int) Math.round(((from >>> 16) & 0xFF) * (1F - clamped) + ((to >>> 16) & 0xFF) * clamped);
		int g = (int) Math.round(((from >>> 8) & 0xFF) * (1F - clamped) + ((to >>> 8) & 0xFF) * clamped);
		int b = (int) Math.round((from & 0xFF) * (1F - clamped) + (to & 0xFF) * clamped);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/**
	 * 角の丸い四角を塗る。
	 *
	 * <p>バニラの `fill` は長方形しか書けないので、丸い部分だけ **行ごとに幅を計算** して
	 * 積み上げている（半径ぶんの行数しかしないので軽い）。
	 */
	public static void fillRound(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}

		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));

		if (r == 0) {
			context.fill(x, y, x + width, y + height, color);
			return;
		}

		for (int i = 0; i < r; i++) {
			double distance = (r - i - 0.5D) / r;
			int inset = r - (int) Math.round(r * Math.sqrt(Math.max(0D, 1D - distance * distance)));
			int top = y + i;
			int bottom = y + height - 1 - i;
			context.fill(x + inset, top, x + width - inset, top + 1, color);
			context.fill(x + inset, bottom, x + width - inset, bottom + 1, color);
		}

		context.fill(x, y + r, x + width, y + height - r, color);
	}

	/** 角の丸い「ふちだけ」 */
	public static void strokeRound(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		context.fill(x + radius, y, x + width - radius, y + 1, color);
		context.fill(x + radius, y + height - 1, x + width - radius, y + height, color);
		context.fill(x, y + radius, x + 1, y + height - radius, color);
		context.fill(x + width - 1, y + radius, x + width, y + height - radius, color);

		int r = Math.max(1, Math.min(radius, Math.min(width, height) / 2));

		for (int i = 0; i < r; i++) {
			double distance = (r - i - 0.5D) / r;
			int inset = r - (int) Math.round(r * Math.sqrt(Math.max(0D, 1D - distance * distance)));
			int top = y + i;
			int bottom = y + height - 1 - i;
			context.fill(x + inset, top, x + inset + 1, top + 1, color);
			context.fill(x + width - inset - 1, top, x + width - inset, top + 1, color);
			context.fill(x + inset, bottom, x + inset + 1, bottom + 1, color);
			context.fill(x + width - inset - 1, bottom, x + width - inset, bottom + 1, color);
		}
	}

	/** 浮いて見える面（影 + 塗り + ふち + 上のハイライト） */
	public static void panel(DrawContext context, int x, int y, int width, int height, int radius) {
		fillRound(context, x + 1, y + 2, width, height, radius, SHADOW);
		fillRound(context, x, y, width, height, radius, SURFACE);
		strokeRound(context, x, y, width, height, radius, BORDER);
		context.fill(x + radius, y + 1, x + width - radius, y + 2, HIGHLIGHT);
	}

	/** 一段高い面（ボタンなど） */
	public static void raised(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		fillRound(context, x, y, width, height, radius, color);
		strokeRound(context, x, y, width, height, radius, BORDER);
	}

	/** 横線（区切り） */
	public static void separator(DrawContext context, int x, int y, int width) {
		context.fill(x, y, x + width, y + 1, BORDER);
	}

	/** 既定の角丸で浮く面 */
	public static void panel(DrawContext context, int x, int y, int width, int height) {
		panel(context, x, y, width, height, 10);
	}

	/**
	 * 画面全体にうっすら暗さを足す。
	 *
	 * <p>バニラの「背景を暗くする」だけだと色がのっぺりするので、
	 * 上下にグラデを重ねて奥行きを出している。
	 */
	public static void veil(DrawContext context, int width, int height) {
		context.fillGradient(0, 0, width, height / 2, 0x4A0A0F14, 0x00000000);
		context.fillGradient(0, height / 2, width, height, 0x00000000, 0x660A0F14);
	}

	/** 進捗バー（0〜1） */
	public static void progress(DrawContext context, int x, int y, int width, int height, float ratio) {
		fillRound(context, x, y, width, height, height / 2, SURFACE_INPUT);
		strokeRound(context, x, y, width, height, height / 2, BORDER);

		int filled = (int) Math.round(width * Math.max(0F, Math.min(1F, ratio)));

		if (filled > 2) {
			fillRound(context, x, y, filled, height, height / 2, ACCENT);
		}
	}
}
