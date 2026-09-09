package com.ifuto.zoom.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * Easing curves used for the zoom animation.
 *
 * <p>The same curve is used for zooming in and for zooming back out, so both directions feel
 * identical. The default {@link #EASE_OUT} starts fast and keeps slowing down. The power
 * curves ({@link #EASE_OUT}, {@link #EASE_IN}, {@link #EASE_IN_OUT}) accept a freely
 * configurable exponent, so the waveform can be tuned continuously from the config screen.</p>
 */
@Environment(EnvType.CLIENT)
public enum EasingType {
	/** No animation at all. */
	INSTANT("instant") {
		@Override
		protected double curve(double t, double power) {
			return 1.0D;
		}
	},
	/** Constant speed. */
	LINEAR("linear") {
		@Override
		protected double curve(double t, double power) {
			return t;
		}
	},
	/** Power ease-out: fast start, slow finish (default, exponent configurable). */
	EASE_OUT("ease_out") {
		@Override
		protected double curve(double t, double power) {
			return 1.0D - Math.pow(1.0D - t, power);
		}
	},
	/** Fixed cubic ease-out: extra snappy start, long glide. */
	EASE_OUT_STRONG("ease_out_strong") {
		@Override
		protected double curve(double t, double power) {
			double inv = 1.0D - t;
			return 1.0D - inv * inv * inv;
		}
	},
	/** Power ease-in: slow start, fast finish (exponent configurable). */
	EASE_IN("ease_in") {
		@Override
		protected double curve(double t, double power) {
			return Math.pow(t, power);
		}
	},
	/** Symmetric power curve, smooth on both ends (exponent configurable). */
	EASE_IN_OUT("ease_in_out") {
		@Override
		protected double curve(double t, double power) {
			return t < 0.5D
					? 0.5D * Math.pow(2.0D * t, power)
					: 1.0D - 0.5D * Math.pow(2.0D * (1.0D - t), power);
		}
	};

	/** Exponents outside this range would feel broken, keep the GUI honest. */
	public static final double MIN_POWER = 1.0D;
	public static final double MAX_POWER = 8.0D;
	private static final double DEFAULT_POWER = 2.0D;

	private final String name;

	EasingType(String name) {
		this.name = name;
	}

	protected abstract double curve(double t, double power);

	/**
	 * @param progress linear progress in [0, 1]
	 * @return eased progress in [0, 1] using the default quadratic exponent
	 */
	public double apply(double progress) {
		return this.apply(progress, DEFAULT_POWER);
	}

	/**
	 * @param progress linear progress in [0, 1]
	 * @param power exponent of the curve (clamped to {@link #MIN_POWER}..{@link #MAX_POWER})
	 * @return eased progress in [0, 1]
	 */
	public double apply(double progress, double power) {
		double t = Math.min(1.0D, Math.max(0.0D, progress));
		double p = Math.min(MAX_POWER, Math.max(MIN_POWER, power));
		return this.curve(t, p);
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
