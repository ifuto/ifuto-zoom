package com.ifuto.zoom;

import com.ifuto.zoom.config.EasingType;
import com.ifuto.zoom.config.ZoomConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * ズームの実行時状態と毎フレームの描画倍率を計算する。
 *
 * 動きは2種類。キーの押す/離すは設定時間のイーズアウト（距離によらず一定時間）、
 * ホイールは目標への追従スムージング（ノッチごとの再生だとカクつくので）。
 * カーブは倍率そのものに掛ける。画面に見える速さは d(zoom)/dt で決まるので、
 * ここでイーズアウトにすると最初のフレームから目に見えて減速する。
 */
@Environment(EnvType.CLIENT)
public final class ZoomState {
	private ZoomState() {
	}

	/** 極端な倍率で射影行列が壊れないよう一応の上限を掛けておく */
	public static final double HARD_MAX_ZOOM = 1000.0D;

	/** カクつき時に何秒分まで平滑化するか。これ以上フレームが空いたらそのまま瞬間移動させる */
	private static final double MAX_FRAME_SECONDS = 0.1D;

	private static boolean active;

	/** ズーム中の目標倍率（スクロールで変わる） */
	private static double zoomLevel = -1.0D;

	// アニメーション用（全部そのままの倍率で持つ）
	private static double animFrom = 1.0D;
	private static double animTo = 1.0D;
	private static double animCurrent = 1.0D;
	private static long animStartNanos;
	private static long animDurationNanos;

	/** スクロールの平滑化が現在値を管理している間 true（イーズ再生中は false） */
	private static boolean smoothing;
	private static long lastSmoothSampleNanos;

	private static double lastRenderZoom = 1.0D;

	public static boolean isActive() {
		return active;
	}

	/** 閉じるアニメーションを含め、FOV を書き換えている最中か */
	public static boolean isZooming() {
		return active || Math.abs(lastRenderZoom - 1.0D) > 1.0E-4D;
	}

	public static double getZoomLevel() {
		ZoomConfig config = ZoomConfig.get();

		if (zoomLevel <= 0.0D) {
			zoomLevel = config.defaultZoom;
		}

		return clampZoom(zoomLevel, config);
	}

	public static void setActive(boolean newActive) {
		if (active == newActive) {
			return;
		}

		active = newActive;
		ZoomConfig config = ZoomConfig.get();

		if (!active && !config.keepZoomLevel) {
			zoomLevel = config.defaultZoom;
		}

		if (active && zoomLevel <= 0.0D) {
			zoomLevel = config.defaultZoom;
		}

		retarget(false);
	}

	/**
	 * ズーム中のスクロールを倍率変更に使う。
	 */
	public static boolean onScroll(double amount) {
		ZoomConfig config = ZoomConfig.get();

		if (!active || !config.scrollToZoom || amount == 0.0D) {
			return false;
		}

		double step = Math.max(1.001D, config.scrollStep);
		zoomLevel = clampZoom(getZoomLevel() * Math.pow(step, amount), config);
		retarget(true);
		return true;
	}

	/** 設定が変わったとき用に全部戻す */
	public static void reset() {
		ZoomConfig config = ZoomConfig.get();
		zoomLevel = config.defaultZoom;
		retarget(false);
	}

	/**
	 * このフレームの描画倍率。1.0 ならズームなし。
	 */
	public static double getRenderZoom() {
		long now = System.nanoTime();

		if (smoothing) {
			// 指数追従は時定数の3倍でだいたい (95%) 収まるので、1ノッチが scrollSmoothMs くらいの動きになる。
			// 0ms ならスムージングなし＝そのまま目標へ。
			double tauSeconds = ZoomConfig.get().scrollSmoothMs / 3000.0D;

			if (tauSeconds <= 1.0E-4D) {
				animCurrent = animTo;
			} else if (lastSmoothSampleNanos > 0L) {
				double dt = Math.min(MAX_FRAME_SECONDS, (now - lastSmoothSampleNanos) / 1.0E9D);

				if (dt > 0.0D) {
					animCurrent += (animTo - animCurrent) * (1.0D - Math.exp(-dt / tauSeconds));

					if (Math.abs(animTo - animCurrent) < 1.0E-5D) {
						animCurrent = animTo;
					}
				}
			}

			lastSmoothSampleNanos = now;
		} else if (animDurationNanos <= 0L) {
			animCurrent = animTo;
		} else {
			double progress = (now - animStartNanos) / (double) animDurationNanos;

			if (progress >= 1.0D) {
				animDurationNanos = 0L;
				animCurrent = animTo;
			} else {
				ZoomConfig config = ZoomConfig.get();
				animCurrent = animFrom + (animTo - animFrom) * config.easing.apply(Math.max(0.0D, progress), config.easingPower);
			}
		}

		if (animCurrent < 1.0E-3D) {
			animCurrent = 1.0E-3D;
		}

		lastRenderZoom = animCurrent;
		return lastRenderZoom;
	}

	/** 現在のズームに応じたマウス感度の倍率。変更不要なら 1.0 */
	public static double getSensitivityFactor() {
		ZoomConfig config = ZoomConfig.get();

		if (!config.reduceSensitivity) {
			return 1.0D;
		}

		double zoom = getRenderZoom();

		if (zoom <= 1.0D) {
			return 1.0D;
		}

		return 1.0D / Math.pow(zoom, Math.max(0.0D, config.sensitivityStrength));
	}

	private static void retarget(boolean fromScroll) {
		double target = active ? getZoomLevel() : 1.0D;

		if (Math.abs(target - animTo) < 1.0E-9D) {
			return;
		}

		ZoomConfig config = ZoomConfig.get();
		animFrom = Math.max(1.0E-3D, animCurrent);
		animTo = target;

		if (config.easing == EasingType.INSTANT || config.easeDurationMs <= 0) {
			smoothing = false;
			animDurationNanos = 0L;
			animFrom = animTo = animCurrent = target;
			return;
		}

		if (fromScroll && active) {
			// ノッチごとに短いアニメを再生し直すと「動く・止まる」の繰り返しでカクつくので、
			// 目標だけ更新してあとは平滑化に任せる
			smoothing = true;
			lastSmoothSampleNanos = System.nanoTime();
			return;
		}

		// キーの押す/離すは距離に関係なく設定時間で固定。どこまで拡大しても戻るテンポは同じ
		smoothing = false;
		animStartNanos = System.nanoTime();
		animDurationNanos = Math.max(0, config.easeDurationMs) * 1_000_000L;
	}

	private static double clampZoom(double value, ZoomConfig config) {
		double min = Math.max(ZoomConfig.HARD_MIN_ZOOM, config.minZoom);
		double max = HARD_MAX_ZOOM;

		if (Double.isNaN(value)) {
			return min;
		}

		return Math.min(max, Math.max(min, value));
	}
}
