package com.ifuto.replay.gui.widget;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 入りきらない文字を横に流して全部見せる。
 *
 * <p>入りきるときはただ出すだけ。はみ出すときだけ、少し止まって→流して→
 * 少し止まって→頭に戻る、を繰り返す。はみ出したぶんは枠の外に出さない。
 */
public final class MarqueeText {
	/** 流し始め・流し終わりに止まる時間 */
	private static final long HOLD_MS = 1200L;

	/** 流す速さ（1秒あたり） */
	private static final double SPEED_PX_PER_SEC = 28.0;

	private MarqueeText() {
	}

	/**
	 * 文字を出す。はみ出すときだけ自動で横に流れる。
	 *
	 * @param x 枠の左端
	 * @param y 文字の上端
	 * @param maxWidth 枠の幅（0 以下なら何もしない）
	 */
	public static void draw(DrawContext context, TextRenderer renderer, Text text, int x, int y,
			int maxWidth, int color) {
		if (maxWidth <= 0) {
			return;
		}

		int width = renderer.getWidth(text);

		if (width <= maxWidth) {
			context.drawText(renderer, text, x, y, color, false);
			return;
		}

		int overflow = width - maxWidth;
		long travelMs = Math.max(1L, (long) (overflow / SPEED_PX_PER_SEC * 1000.0));
		long cycle = HOLD_MS + travelMs + HOLD_MS;
		// 中身でずらす（同じ画面の流しが一斉に動かないように）
		long phase = text.getString().hashCode() & 0x3FFL;
		long t = (System.currentTimeMillis() + phase) % cycle;

		long offset;

		if (t < HOLD_MS) {
			offset = 0L;
		} else if (t < HOLD_MS + travelMs) {
			offset = overflow * (t - HOLD_MS) / travelMs;
		} else {
			offset = overflow;
		}

		context.enableScissor(x, y, x + maxWidth, y + renderer.fontHeight);

		try {
			context.drawText(renderer, text, x - (int) offset, y, color, false);
		} finally {
			context.disableScissor();
		}
	}
}
