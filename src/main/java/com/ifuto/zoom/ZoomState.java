package com.ifuto.zoom;

import com.ifuto.zoom.config.EasingType;
import com.ifuto.zoom.config.ZoomConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Holds the runtime zoom state and computes the zoom factor used for rendering each frame.
 *
 * <p>Two kinds of motion are used:</p>
 * <ul>
 *   <li><b>Key press / release</b>: a fixed-length ease-out animation over the configured
 *       duration. The duration never depends on the zoom distance, so zooming back out
 *       always takes the same time, no matter how far the scroll wheel took you.</li>
 *   <li><b>Scroll wheel</b>: exponential smoothing that chases the target (≈175 ms to
 *       mostly settle). Repeated wheel ticks blend into one continuous glide instead of
 *       restarting a short animation for every notch, which used to feel choppy.</li>
 * </ul>
 *
 * <p>Curves are applied to the zoom factor directly (linear space): the perceived on-screen
 * speed follows {@code d(zoom)/dt}, so an ease-out here visibly decelerates from the first
 * frame.</p>
 */
@Environment(EnvType.CLIENT)
public final class ZoomState {
	private ZoomState() {
	}

	/** Safety cap so extreme zoom levels cannot break the projection matrix. */
	public static final double HARD_MAX_ZOOM = 1000.0D;

	/**
	 * Time constant of the scroll smoothing. An exponential chase is ~95% settled after
	 * three time constants, so this makes one wheel notch glide for about 175 ms.
	 */
	private static final double SCROLL_TAU_SECONDS = 0.175D / 3.0D;

	/** Longest frame step the smoothing integrates; longer hitches just snap to the target. */
	private static final double MAX_FRAME_SECONDS = 0.1D;

	private static boolean active;

	/** Target zoom factor while active (changed by scrolling). */
	private static double zoomLevel = -1.0D;

	// Animation state, all in plain (linear) zoom factor space.
	private static double animFrom = 1.0D;
	private static double animTo = 1.0D;
	private static double animCurrent = 1.0D;
	private static long animStartNanos;
	private static long animDurationNanos;

	/** True while the scroll smoothing owns {@link #animCurrent} instead of an ease animation. */
	private static boolean smoothing;
	private static long lastSmoothSampleNanos;

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

		retarget(false);
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
		retarget(true);
		return true;
	}

	/** Resets everything (used when the config changes). */
	public static void reset() {
		ZoomConfig config = ZoomConfig.get();
		zoomLevel = config.defaultZoom;
		retarget(false);
	}

	/**
	 * @return the zoom factor to render with this frame; 1.0 means "no zoom".
	 */
	public static double getRenderZoom() {
		long now = System.nanoTime();

		if (smoothing) {
			if (lastSmoothSampleNanos > 0L) {
				double dt = Math.min(MAX_FRAME_SECONDS, (now - lastSmoothSampleNanos) / 1.0E9D);

				if (dt > 0.0D) {
					animCurrent += (animTo - animCurrent) * (1.0D - Math.exp(-dt / SCROLL_TAU_SECONDS));

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
				EasingType easing = ZoomConfig.get().easing;
				animCurrent = animFrom + (animTo - animFrom) * easing.apply(Math.max(0.0D, progress));
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
			// Blend wheel ticks into one continuous glide instead of restarting a fixed
			// animation per notch (the start-stop rhythm felt choppy).
			smoothing = true;
			lastSmoothSampleNanos = System.nanoTime();
			return;
		}

		// Key press / release: always the configured duration, independent of the distance,
		// so returning from any zoom level takes exactly the same time.
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
