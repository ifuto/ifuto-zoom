package com.ifuto.zoom.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * ズームの速度カーブ。拡大・縮小で同じカーブを使うので動きが揃う。
 * power を受け取るものは設定画面の「カーブの強さ」で波形を変えられる。
 */
@Environment(EnvType.CLIENT)
public enum EasingType {
	/** アニメーションなし */
	INSTANT("instant") {
		@Override
		protected double curve(double t, double power) {
			return 1.0D;
		}
	},
	/** 一定速度 */
	LINEAR("linear") {
		@Override
		protected double curve(double t, double power) {
			return t;
		}
	},
	/** 最初速く、だんだん遅く（既定。power で強さを変更可） */
	EASE_OUT("ease_out") {
		@Override
		protected double curve(double t, double power) {
			return 1.0D - Math.pow(1.0D - t, power);
		}
	},
	/** 同上の3次固定版。最初にガッと寄って最後は長めに滑る */
	EASE_OUT_STRONG("ease_out_strong") {
		@Override
		protected double curve(double t, double power) {
			double inv = 1.0D - t;
			return 1.0D - inv * inv * inv;
		}
	},
	/** 最初遅く、だんだん速く */
	EASE_IN("ease_in") {
		@Override
		protected double curve(double t, double power) {
			return Math.pow(t, power);
		}
	},
	/** 両端なめらか */
	EASE_IN_OUT("ease_in_out") {
		@Override
		protected double curve(double t, double power) {
			return t < 0.5D
					? 0.5D * Math.pow(2.0D * t, power)
					: 1.0D - 0.5D * Math.pow(2.0D * (1.0D - t), power);
		}
	};

	// この範囲外の指数は体感が壊れるのでGUIのスライダー範囲としても使う
	public static final double MIN_POWER = 1.0D;
	public static final double MAX_POWER = 8.0D;
	private static final double DEFAULT_POWER = 2.0D;

	private final String name;

	EasingType(String name) {
		this.name = name;
	}

	protected abstract double curve(double t, double power);

	public double apply(double progress) {
		return this.apply(progress, DEFAULT_POWER);
	}

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
