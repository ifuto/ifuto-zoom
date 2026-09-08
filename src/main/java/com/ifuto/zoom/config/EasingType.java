package com.ifuto.zoom.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * Easing curves used for the zoom animation.
 *
 * <p>The same curve is used for zooming in and for zooming back out, so both directions feel
 * identical. The default {@link #EASE_OUT} starts fast and keeps slowing down.</p>
 */
@Environment(EnvType.CLIENT)
public enum EasingType {
	/** No animation at all. */
	INSTANT("instant") {
		@Override
		protected double curve(double t) {
			return 1.0D;
		}
	},
	/** Constant speed. */
	LINEAR("linear") {
		@Override
		protected double curve(double t) {
			return t;
		}
	},
	/** Quadratic ease-out: fast start, slow finish (default). */
	EASE_OUT("ease_out") {
		@Override
		protected double curve(double t) {
			double inv = 1.0D - t;
			return 1.0D - inv * inv;
		}
	},
	/** Cubic ease-out: even snappier start, longer glide. */
	EASE_OUT_STRONG("ease_out_strong") {
		@Override
		protected double curve(double t) {
			double inv = 1.0D - t;
			return 1.0D - inv * inv * inv;
		}
	},
	/** Quadratic ease-in: slow start, fast finish. */
	EASE_IN("ease_in") {
		@Override
		protected double curve(double t) {
			return t * t;
		}
	},
	/** Smooth on both ends. */
	EASE_IN_OUT("ease_in_out") {
		@Override
		protected double curve(double t) {
			return t < 0.5D ? 2.0D * t * t : 1.0D - Math.pow(-2.0D * t + 2.0D, 2.0D) / 2.0D;
		}
	};

	private final String name;

	EasingType(String name) {
		this.name = name;
	}

	protected abstract double curve(double t);

	/**
	 * @param progress linear progress in [0, 1]
	 * @return eased progress in [0, 1]
	 */
	public double apply(double progress) {
		double t = Math.min(1.0D, Math.max(0.0D, progress));
		return this.curve(t);
	}

	public String asString() {
		return this.name;
	}

	public String getTranslationKey() {
		return "ifuto-zoom.config.easing." + this.name;
	}

	public Text getText() {
		return Text.translatable(this.getTranslationKey());
	}
}
