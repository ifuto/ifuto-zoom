package com.ifuto.zoom;

import com.ifuto.zoom.config.EasingType;
import com.ifuto.zoom.config.ZoomConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Holds the runtime zoom state and computes the smoothly eased zoom factor used for rendering.
 *
 * <p>The animation is done in logarithmic space so that every scroll step feels equally strong,
 * no matter how far you are already zoomed in.</p>
 */
@Environment(EnvType.CLIENT)
public final class ZoomState {
	private ZoomState() {
	}

	/** Safety cap so extreme zoom levels cannot break the projection matrix. */
	public static final double HARD_MAX_ZOOM = 1000.0D;

	private static boolean active;

	/** Target zoom factor while active (changed by scrolling). */
	private static double zoomLevel = -1.0D;

	// Animation state (all in log space).
	private static double animFrom;
	private static double animTo;
	private static double animCurrent;
	private static long animStartNanos;
	private static long animDurationNanos;

	private static double lastRenderZoom = 1.0D;

	public static boolean isActive() {
		return active;
	}

	/** @return true while the mod is changing the FOV (including the closing animation). */
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

		retarget();
	}

	/**
	 * Applies a mouse scroll while zooming.
	 *
	 * @param amount vertical scroll amount (positive = scroll up = zoom in)
	 * @return true when the scroll was consumed by the zoom
	 */
	public static boolean onScroll(double amount) {
		ZoomConfig config = ZoomConfig.get();

		if (!active || !config.scrollToZoom || amount == 0.0D) {
			return false;
		}

		double step = Math.max(1.001D, config.scrollStep);
		zoomLevel = clampZoom(getZoomLevel() * Math.pow(step, amount), config);
		retarget();
		return true;
	}

	/** Resets everything (used when the config changes). */
	public static void reset() {
		ZoomConfig config = ZoomConfig.get();
		zoomLevel = config.defaultZoom;
		retarget();
	}

	/**
	 * @return the zoom factor to render with this frame; 1.0 means "no zoom".
	 */
	public static double getRenderZoom() {
		double target = animTo;

		if (animDurationNanos <= 0L) {
			animCurrent = target;
		} else {
			double progress = (System.nanoTime() - animStartNanos) / (double) animDurationNanos;

			if (progress >= 1.0D) {
				progress = 1.0D;
				animDurationNanos = 0L;
				animCurrent = target;
			} else {
				if (progress < 0.0D) {
					progress = 0.0D;
				}

				EasingType easing = ZoomConfig.get().easing;
				boolean zoomingIn = animTo >= animFrom;
				double eased = easing.apply(progress, zoomingIn);
				animCurrent = animFrom + (animTo - animFrom) * eased;
			}
		}

		lastRenderZoom = Math.exp(animCurrent);

		if (lastRenderZoom < 1.0E-3D) {
			lastRenderZoom = 1.0E-3D;
		}

		return lastRenderZoom;
	}

	/** @return a mouse sensitivity multiplier for the current zoom, 1.0 when nothing should change. */
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

	private static void retarget() {
		double target = Math.log(active ? getZoomLevel() : 1.0D);

		if (Math.abs(target - animTo) < 1.0E-9D) {
			return;
		}

		// Continue from wherever the animation currently is, so mid-animation changes stay smooth.
		animFrom = animCurrent;
		animTo = target;
		animStartNanos = System.nanoTime();

		ZoomConfig config = ZoomConfig.get();
		long duration = Math.max(0, config.easeDurationMs) * 1_000_000L;

		if (config.easing == EasingType.INSTANT) {
			duration = 0L;
		}

		animDurationNanos = duration;

		if (duration <= 0L) {
			animCurrent = animTo;
		}
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
