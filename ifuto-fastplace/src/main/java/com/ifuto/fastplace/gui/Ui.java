package com.ifuto.fastplace.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.HashMap;
import java.util.Map;

/**
 * HTML のモックを Minecraft の GUI にそのまま持ち込むための描画部品。
 *
 * <p>これまでの見た目は「45° の面取り（カクカク）」だった。ここでは
 * CSS の {@code border-radius} と同じように、角を <b>1px 単位で丸めて
 * アンチエイリアスする</b>（行ごとに内側の幅を計算し、端の 1px は被覆率の
 * アルファで描く）。文字はバニラフォントを<b>拡大せず等倍・影なし</b>で置くので、
 * ピクセルが間引かれず「カクカクした太字」にならない。</p>
 *
 * <p>色は CSS の変数のつもりで 1 か所にまとめてある。書き換えるだけで
 * 画面全体のテーマが変わる。</p>
 */
public final class Ui {
	// ---- テーマ（CSS の :root 相当） ----
	/** パネルの地。88% なので後ろの世界がうっすら透ける */
	public static final int SURFACE = 0xE0151518;
	public static final int SURFACE_2 = 0xE01B1B1F;
	public static final int SURFACE_3 = 0xE0222228;
	/** 1px の縁（白 8%） */
	public static final int BORDER = 0x1FFFFFFF;
	public static final int BORDER_STRONG = 0x33FFFFFF;
	/** 行のホバー / 選択（白の薄いオーバーレイ） */
	public static final int HOVER = 0x12FFFFFF;
	public static final int SELECTED = 0x1FFFFFFF;
	/** アクセント（エメラルド） */
	public static final int ACCENT = 0xFF2ED3A0;
	public static final int ACCENT_DIM = 0xFF1E9C77;
	public static final int ACCENT_SOFT = 0x2E2ED3A0;
	/** 文字 */
	public static final int TEXT = 0xFFF1F1F4;
	public static final int MUTED = 0xFFA0A0AC;
	public static final int DIM = 0xFF6C6C78;
	public static final int WHITE = 0xFFFFFFFF;
	public static final int SHADOW = 0x66000000;

	/** 角の丸み（HTML の border-radius: 10px 相当） */
	public static final int RADIUS = 10;
	public static final int RADIUS_SM = 6;

	private static final Map<String, Float> ANIM = new HashMap<>();
	private static final Map<String, Long> ANIM_AT = new HashMap<>();

	private Ui() {
	}

	// ------------------------------------------------------------------
	// 図形
	// ------------------------------------------------------------------

	/**
	 * 角丸の長方形。行ごとに幅を計算し、端は 1px の被覆率でブレンドする。
	 * 四隅だけを 1 行ずつ描き、中央は 1 回で塗るので呼んでも重くない。
	 */
	public static void roundRect(DrawContext ctx, double x, double y, double w, double h,
			double radius, int color) {
		if (w <= 0.0 || h <= 0.0 || ((color >>> 24) & 0xFF) == 0) {
			return;
		}
		double r = Math.max(0.0, Math.min(radius, Math.min(w, h) * 0.5));
		int alpha = (color >>> 24) & 0xFF;
		int top = (int) Math.ceil(y);
		int bottom = (int) Math.floor(y + h);
		if (bottom <= top) {
			return;
		}
		if (r < 0.5) {
			ctx.fill((int) Math.round(x), top, (int) Math.round(x + w), bottom, color);
			return;
		}
		int left = (int) Math.floor(x);
		int right = (int) Math.ceil(x + w);
		for (int py = top; py < bottom; py++) {
			double cy = (py + 0.5) - y;
			double inset = 0.0;
			double dr = cy < r ? r - cy : (cy > h - r ? cy - (h - r) : 0.0);
			if (dr > 0.0) {
				inset = r - Math.sqrt(Math.max(0.0, r * r - dr * dr));
			}
			double l = x + inset;
			double rr = x + w - inset;
			if (rr - l < 0.6) {
				continue;
			}
			int x0 = Math.max(left, (int) Math.ceil(l));
			int x1 = Math.min(right, (int) Math.floor(rr));
			if (x1 > x0) {
				ctx.fill(x0, py, x1, py + 1, color);
			}
			// 端の 1px は被覆率のぶんだけ薄く描く（縦のアンチエイリアス）
			double lo = x0 - l;
			if (lo > 0.03 && x0 - 1 >= left) {
				ctx.fill(x0 - 1, py, x0, py + 1, withAlpha(color, (int) Math.round(alpha * lo)));
			}
			double ro = rr - x1;
			if (ro > 0.03 && x1 < right) {
				ctx.fill(x1, py, x1 + 1, py + 1, withAlpha(color, (int) Math.round(alpha * ro)));
			}
		}
	}

	/** 角丸の線（外側を縁色で塗って、内側を地色で塗り直す） */
	public static void border(DrawContext ctx, double x, double y, double w, double h,
			double radius, int borderColor, int fillColor) {
		roundRect(ctx, x, y, w, h, radius, borderColor);
		roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0.0, radius - 1), fillColor);
	}

	/** 柔らかい影（外側に向かって薄くなる 3 枚） */
	public static void shadow(DrawContext ctx, double x, double y, double w, double h, double radius) {
		roundRect(ctx, x - 3, y + 5, w + 6, h + 3, radius + 3, 0x14000000);
		roundRect(ctx, x - 2, y + 3, w + 4, h + 3, radius + 2, 0x1E000000);
		roundRect(ctx, x, y + 2, w, h + 1, radius + 1, 0x33000000);
	}

	public static void circle(DrawContext ctx, double cx, double cy, double radius, int color) {
		roundRect(ctx, cx - radius, cy - radius, radius * 2.0, radius * 2.0, radius, color);
	}

	/** 横グラデーション（左から右へ色を混ぜる）。ヘッダーの帯に使う */
	public static void hGradient(DrawContext ctx, int x, int y, int w, int h, int left, int right) {
		for (int i = 0; i < w; i++) {
			ctx.fill(x + i, y, x + i + 1, y + h, mix(left, right, (float) i / Math.max(1, w - 1)));
		}
	}

	// ------------------------------------------------------------------
	// 文字
	// ------------------------------------------------------------------

	/**
	 * 文字。等倍なら拡大せずそのまま置く（{@code scale != 1} のときだけ行列を積む）。
	 * 影は付けない（影があると輪郭が汚れて「カクカク」に見える）。
	 */
	public static void text(DrawContext ctx, TextRenderer renderer, String value,
			double x, double y, int color, float scale) {
		if (value == null || value.isEmpty() || ((color >>> 24) & 0xFF) == 0) {
			return;
		}
		if (Math.abs(scale - 1.0f) < 1.0e-4) {
			ctx.drawText(renderer, value, (int) Math.round(x), (int) Math.round(y), color, false);
			return;
		}
		var matrices = ctx.getMatrices();
		matrices.pushMatrix();
		matrices.translate((float) x, (float) y);
		matrices.scale(scale, scale);
		ctx.drawText(renderer, value, 0, 0, color, false);
		matrices.popMatrix();
	}

	public static void textRight(DrawContext ctx, TextRenderer renderer, String value,
			double right, double y, int color, float scale) {
		text(ctx, renderer, value, right - width(renderer, value, scale), y, color, scale);
	}

	public static void textCenter(DrawContext ctx, TextRenderer renderer, String value,
			double centerX, double y, int color, float scale) {
		text(ctx, renderer, value, centerX - width(renderer, value, scale) * 0.5, y, color, scale);
	}

	public static double width(TextRenderer renderer, String value, float scale) {
		return renderer.getWidth(value) * scale;
	}

	// ------------------------------------------------------------------
	// 部品
	// ------------------------------------------------------------------

	/**
	 * ピル型トグル。{@code t} は 0（OFF）〜1（ON）で、呼び出し側が
	 * {@link #anim} でならして渡す（カチッと飛ばない）。
	 */
	public static void toggle(DrawContext ctx, double x, double y, double w, double h, float t) {
		float p = clamp01(t);
		int track = mix(0xFF2C2C33, ACCENT, p);
		roundRect(ctx, x, y, w, h, h * 0.5, track);
		double knob = h - 4.0;
		double kx = x + 2.0 + (w - knob - 4.0) * p;
		circle(ctx, kx + knob * 0.5, y + h * 0.5 + 0.8, knob * 0.5, 0x33000000);
		circle(ctx, kx + knob * 0.5, y + h * 0.5, knob * 0.5, 0xFFFFFFFF);
	}

	/** スライダー。{@code t} は 0〜1 */
	public static void slider(DrawContext ctx, double x, double y, double w, double t, boolean hover) {
		double p = clamp01((float) t);
		double trackH = hover ? 6.0 : 4.0;
		double ty = y - trackH * 0.5;
		roundRect(ctx, x, ty, w, trackH, trackH * 0.5, 0xFF2B2B32);
		double filled = Math.max(trackH, w * p);
		roundRect(ctx, x, ty, filled, trackH, trackH * 0.5, ACCENT);
		double knob = hover ? 11.0 : 9.0;
		circle(ctx, x + w * p, y, knob * 0.5 + 1.0, 0x33000000);
		circle(ctx, x + w * p, y, knob * 0.5, 0xFFFFFFFF);
		circle(ctx, x + w * p, y, knob * 0.5 - 2.0, ACCENT);
	}

	/** 押せるボタン（角丸 + ホバーで 1 段明るく + 押下で少し沈む） */
	public static void button(DrawContext ctx, TextRenderer renderer, String label,
			double x, double y, double w, double h, float hover, boolean pressed, boolean enabled) {
		int base = enabled ? SURFACE_3 : 0xCC191920;
		int fillColor = mix(base, enabled ? 0xFF2A2A33 : 0xCC191920, clamp01(hover));
		if (pressed) {
			fillColor = mix(fillColor, ACCENT_DIM, 0.35f);
		}
		roundRect(ctx, x, y + (pressed ? 1 : 0), w, h, RADIUS_SM, fillColor);
		roundRect(ctx, x, y + (pressed ? 1 : 0), w, 1, RADIUS_SM, BORDER);
		int textColor = !enabled ? DIM : (hover > 0.4f ? TEXT : MUTED);
		textCenter(ctx, renderer, label, x + w * 0.5, y + (h - 8.0) * 0.5 + (pressed ? 1 : 0),
			textColor, 1.0f);
	}

	/** 小さなチップ（キーバインド表示など） */
	public static void chip(DrawContext ctx, TextRenderer renderer, String label,
			double x, double y, float hover) {
		double w = width(renderer, label, 1.0f) + 12.0;
		roundRect(ctx, x, y, w, 15, 7.5, mix(0xFF23232A, 0xFF2C2C35, hover));
		text(ctx, renderer, label, x + 6, y + 4, MUTED, 1.0f);
	}

	public static double chipWidth(TextRenderer renderer, String label) {
		return width(renderer, label, 1.0f) + 12.0;
	}

	// ------------------------------------------------------------------
	// アニメーション（CSS transition のつもり）
	// ------------------------------------------------------------------

	/**
	 * 0/1 の目標へ向かって指数的に寄る値（CSS の transition）。キーごとに
	 * 直前の時刻を覚えているので、フレームレートが変わっても速さが変わらない。
	 */
	public static float anim(String key, boolean on) {
		long now = System.nanoTime();
		long last = ANIM_AT.getOrDefault(key, now);
		ANIM_AT.put(key, now);
		float dt = Math.max(0.0f, Math.min(0.1f, (now - last) / 1.0e9f));
		float target = on ? 1.0f : 0.0f;
		float current = ANIM.getOrDefault(key, target);
		float k = 1.0f - (float) Math.exp(-dt * 18.0);
		current += (target - current) * k;
		if (Math.abs(target - current) < 0.002f) {
			current = target;
		}
		ANIM.put(key, current);
		return current;
	}

	public static void clearAnimations() {
		ANIM.clear();
		ANIM_AT.clear();
	}

	public static float easeOut(float t) {
		float p = clamp01(t);
		return 1.0f - (1.0f - p) * (1.0f - p);
	}

	// ------------------------------------------------------------------
	// 色
	// ------------------------------------------------------------------

	public static int withAlpha(int color, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		return (a << 24) | (color & 0x00FFFFFF);
	}

	public static int scaleAlpha(int color, float factor) {
		int a = (int) Math.round(((color >>> 24) & 0xFF) * clamp01(factor));
		return withAlpha(color, a);
	}

	public static int mix(int from, int to, float t) {
		float p = clamp01(t);
		int a = (int) Math.round(((from >>> 24) & 0xFF) * (1.0f - p) + ((to >>> 24) & 0xFF) * p);
		int r = (int) Math.round(((from >> 16) & 0xFF) * (1.0f - p) + ((to >> 16) & 0xFF) * p);
		int g = (int) Math.round(((from >> 8) & 0xFF) * (1.0f - p) + ((to >> 8) & 0xFF) * p);
		int b = (int) Math.round((from & 0xFF) * (1.0f - p) + (to & 0xFF) * p);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static float clamp01(float v) {
		return v < 0.0f ? 0.0f : Math.min(v, 1.0f);
	}

	public static boolean inside(double mx, double my, double x, double y, double w, double h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}
}
