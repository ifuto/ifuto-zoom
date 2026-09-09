package com.ifuto.zoom;

import com.ifuto.zoom.config.EasingType;
import com.ifuto.zoom.config.ZoomConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Holds the runtime zoom state and computes the smoothly eased zoom factor used for rendering.
 *
 * <p>The easing is applied directly to the zoom factor (linear space), because that is what the
 * eye actually perceives: the on-screen growth speed follows {@code d(zoom)/dt}, so an ease-out
 * curve in this space visibly decelerates from the very first frame. (Applying ease-out in log
 * space mostly cancels out against the exponential conversion and feels like it speeds up
 * instead.)</p>
 *
 * <p>The animation duration is scaled by the size of the change: a full 3x zoom uses the
 * configured duration, a small scroll notch uses a fraction of it, so scrolling stays responsive
 * while the big zoom-in keeps its dramatic glide.</p>
 */
@Environment(EnvType.CLIENT)
public final class ZoomState {
	private ZoomState() {
	}

	/** Safety cap so extreme zoom levels cannot break the projection matrix. */
	public static final double HARD_MAX_ZOOM = 1000.0D;

	/** The reference zoom distance that takes the full configured duration. */
	private static final double REFERENCE_DISTANCE = Math.log(3.0D);

	/** Duration clamps relative to the configured duration. */
	private static final double MIN_DURATION_FACTOR = 0.25D;
	private static final double MAX_DURATION_FACTOR = 2.0D;

	private static boolean active;

	/** Target zoom factor while active (changed by scrolling). */
	private static double zoomLevel = -1.0D;

	// Animation state, all in plain (linear) zoom factor space.
	private static double animFrom = 1.0D;
	private static double animTo = 1.0D;
	private static double animCurrent = 1.0D;
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
				double eased = easing.apply(progress);
				animCurrent = animFrom + (animTo - animFrom) * eased;
			}
		}

		if (animCurrent < 1.0E-3D) {
			animCurrent = 1.0E-3D;
		}

		lastRenderZoom = animCurrent;
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
		double target = active ? getZoomLevel() : 1.0D;

		if (Math.abs(target - animTo) < 1.0E-9D) {
			return;
		}

		ZoomConfig config = ZoomConfig.get();

		if (config.easing == EasingType.INSTANT || config.easeDurationMs <= 0) {
			animFrom = animTo = animCurrent = target;
			animDurationNanos = 0L;
			return;
		}

		// Continue from wherever the animation currently is, so mid-animation changes stay smooth.
		animFrom = Math.max(1.0E-3D, animCurrent);
		animTo = target;
		animStartNanos = System.nanoTime();

		// Scale the duration with the size of the change: a 3x jump takes the configured time,
		// a gentle scroll notch only a fraction, huge jumps up to double.
		double distance = Math.abs(Math.log(animTo / animFrom));
		double factor = Math.min(MAX_DURATION_FACTOR, Math.max(MIN_DURATION_FACTOR, distance / REFERENCE_DISTANCE));
		animDurationNanos = (long) (config.easeDurationMs * factor * 1_000_000.0D);
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
