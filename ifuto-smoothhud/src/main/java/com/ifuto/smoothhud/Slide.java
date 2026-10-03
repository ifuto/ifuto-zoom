package com.ifuto.smoothhud;

/**
 * 選択枠を滑らかに追いかける（このフォークの直し所）。
 *
 * <p>元 Mod は {@code 位置 += 差 × 秒 × 速さ} をそのまま足していた。
 * カクつきで秒が伸びると1回で行き過ぎ、枠がホットバーの外へ飛び出す。
 * 初回（前回時刻0）は秒が約束17億秒になり、枠が彼方へ消える。
 *
 * <p>ここでは進み具合を1以下に丸め、0.5pxまで寄ったら吸着し、
 * 最後に0〜8枠目の中に固定する。速さを上げても絶対に追い越さない。
 */
public final class Slide {
	/** 1枠の幅 */
	public static final float SLOT = 20.0f;
	/** 8枠目の左端（枠の右の上限） */
	public static final float MAX_X = 8 * SLOT;

	private Slide() {
	}

	/**
	 * 今の位置を目標へ少し進める。
	 *
	 * @param current 今の位置
	 * @param target 目標（枠番号×20）
	 * @param deltaTime 前回からの秒
	 * @param speed 速さ（設定値）
	 * @return 進んだ位置（0〜160の中）
	 */
	public static float toward(float current, float target, float deltaTime, float speed) {
		float goal = Math.max(0.0f, Math.min(MAX_X, target));

		if (!Float.isFinite(current)) {
			return goal;
		}

		float seconds = Math.min(Math.max(deltaTime, 0.0f), 0.1f);
		float step = Math.min(1.0f, seconds * speed);
		float next = current + (goal - current) * step;

		if (Math.abs(goal - next) < 0.5f) {
			next = goal;
		}

		return Math.max(0.0f, Math.min(MAX_X, next));
	}
}
