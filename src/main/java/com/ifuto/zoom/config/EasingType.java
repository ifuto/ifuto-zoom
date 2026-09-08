package com.ifuto.zoom.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;
import net.minecraft.util.StringIdentifiable;

/**
 * Easing curves used for the zoom animation.
 *
 * <p>"Zooming in" uses the curve itself (slow start, fast finish = ease-in), while zooming back out
 * uses the mirrored curve so the whole movement feels symmetrical.</p>
 */
@Environment(EnvType.CLIENT)
public enum EasingType implements StringIdentifiable {
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
	/** Quadratic ease-in (default). */
	EASE_IN("ease_in") {
		@Override
		protected double curve(double t) {
			return t * t;
		}
	},
	/** Cubic ease-in, a more pronounced "pull". */
	EASE_IN_STRONG("ease_in_strong") {
		@Override
		protected double curve(double t) {
			return t * t * t;
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
	 * @param progress  linear progress in [0, 1]
	 * @param zoomingIn true when the zoom factor is increasing
	 * @return eased progress in [0, 1]
	 */
	public double apply(double progress, boolean zoomingIn) {
		double t = Math.min(1.0D, Math.max(0.0D, progress));

		if (zoomingIn || this == LINEAR || this == INSTANT || this == EASE_IN_OUT) {
			return this.curve(t);
		}

		// Mirror the ease-in curve for the way back (= ease-out).
		return 1.0D - this.curve(1.0D - t);
	}

	@Override
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
