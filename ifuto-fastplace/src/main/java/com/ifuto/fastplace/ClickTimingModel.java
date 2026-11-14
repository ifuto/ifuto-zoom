package com.ifuto.fastplace;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 連打タイミングの統計モデル。全部ここに閉じ込める（ヘッドレスでテストできるように）。
 *
 * <ul>
 *   <li>基本間隔は設定値（既定 55ms）。速いクリックより遅いクリックが出やすい右裾分布（ガンマ分布）</li>
 *   <li>19 回は普通の乱数、20 回目だけ暗号学的乱数（SecureRandom）でサンプル</li>
 *   <li>毎回のクリックに微小なブレ（±2.5%、人には分からない）</li>
 *   <li>疲労は残り容量の FATIGUE_GAIN ずつ漸近的に溜まる（限度値ベタ張り付きしない）。
 *       元値にだけ上限（+25%）がかかり、Jitter は上限を突破できる。
 *       連打していない間は指数的に回復する</li>
 *   <li>Jitter の強さ（jitterSigma）と外れ値率は 2 秒ごとにランダムウォークで動的に変わる</li>
 *   <li>外れ値（たまに大きく遅れる hesitation）を含める</li>
 *   <li>形状パラメータがランダムウォークするので標準偏差は毎回変わる</li>
 *   <li>表示 CPS は小数1桁・整数にならず・毎 tick 細かく変動する</li>
 * </ul>
 */
public final class ClickTimingModel {
	/** 疲労の上限（元値に対する比率）。不便にならないよう高くしない */
	public static final double FATIGUE_MAX = 0.25;
	/** クリックごとの疲労増加（残り容量に対する比率。漸近に近づくだけ） */
	public static final double FATIGUE_GAIN = 0.008;
	/** 疲労の回復時定数（ミリ秒）。指数回復、半減期 ≈ 1.5 秒 */
	public static final double RECOVERY_TAU_MS = 2200.0;
	/** 微小なブレ（毎回・±2.5%） */
	public static final double MICRO_JITTER = 0.025;
	/** ソフトクランプ。ここを下回る間隔は出さない（連打が壊れて見えるのを防ぐ） */
	public static final double MIN_INTERVAL_MS = 15.0;
	public static final double MAX_INTERVAL_MS = 1000.0;
	public static final double SIGMA_MIN = 0.015;
	public static final double SIGMA_MAX = 0.075;
	public static final double OUTLIER_MIN = 0.012;
	public static final double OUTLIER_MAX = 0.045;

	private static final SecureRandom CRYPTO = new SecureRandom();

	private long clickCount = 0;
	private double skewShape = 3.2;
	private double jitterSigma = 0.04;
	private double outlierProb = 0.025;
	private double fatigue = 0.0;
	private double cpsEma = 1000.0 / 55.0;
	private double lastInterval = 55.0;
	private long lastTickMs = -1;
	private long lastParamShiftMs = -1;

	public void resetStats() {
		clickCount = 0;
		fatigue = 0.0;
		cpsEma = 1000.0 / 55.0;
		skewShape = 3.2;
		jitterSigma = 0.04;
		outlierProb = 0.025;
		lastTickMs = -1;
		lastParamShiftMs = -1;
	}

	/** 毎 tick 呼ぶ。疲労の回復と、2 秒ごとの強さの動的シフトを進める。nowMs は明示指定 */
	public void tick(long nowMs) {
		if (lastTickMs < 0) {
			lastTickMs = nowMs;
			lastParamShiftMs = nowMs;
			return;
		}
		long dt = Math.max(0, nowMs - lastTickMs);
		lastTickMs = nowMs;

		// 連打していない間はちょっとずつ回復（指数）
		fatigue *= Math.exp(-dt / RECOVERY_TAU_MS);

		// 2 秒ごとに Jitter の強さと外れ値率を動的にシフト（微細でプレイヤーには分からない）
		if (nowMs - lastParamShiftMs >= 2000) {
			Random r = ThreadLocalRandom.current();
			jitterSigma = clamp(jitterSigma + (r.nextDouble() - 0.5) * 0.03, SIGMA_MIN, SIGMA_MAX);
			outlierProb = clamp(outlierProb + (r.nextDouble() - 0.5) * 0.014, OUTLIER_MIN, OUTLIER_MAX);
			lastParamShiftMs = nowMs;
		}
	}

	/** このクリックが暗号学的乱数の番か（20 回ごと）。HUD／テスト用 */
	public boolean isCryptoTurn() {
		return (clickCount + 1) % 20L == 0;
	}

	/** クリックを1回発火したときの次の間隔ミリ秒を返す */
	public long fireClick(long nowMs, double baseMs) {
		Random rand = (clickCount + 1) % 20L == 0 ? CRYPTO : ThreadLocalRandom.current();
		clickCount++;

		// 疲労: 残り容量の FATIGUE_GAIN ずつ（漸近。元値は FATIGUE_MAX を決して超えない）
		fatigue += (FATIGUE_MAX - fatigue) * FATIGUE_GAIN;

		// 形状パラメータをランダムウォークさせる → 標準偏差は毎回変わる
		skewShape = clamp(skewShape + (rand.nextDouble() - 0.5) * 0.35, 2.2, 5.5);

		double mean = baseMs * (1.0 + fatigue);   // 元値（+疲労、上限 FATIGUE_MAX）
		double theta = mean / skewShape;
		double gammaCore = sampleGamma(rand, skewShape, theta);
		double micro = 1.0 + (rand.nextDouble() * 2.0 - 1.0) * MICRO_JITTER;
		double jitter = sampleLogNormal(rand, jitterSigma);   // Jitter は上限を突破できる
		double outlier = sampleOutlier(rand);

		double interval = gammaCore * micro * jitter * outlier;
		interval = clamp(interval, MIN_INTERVAL_MS, MAX_INTERVAL_MS);
		lastInterval = interval;

		double instCps = 1000.0 / interval;
		cpsEma += (instCps - cpsEma) * 0.22;
		return Math.round(interval);
	}

	/** 表示用 CPS。毎 tick 呼ぶたびに細かく変動し、決して整数にならない */
	public double displayCps(long nowMs) {
		double wobble = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.06 * cpsEma;  // ±3%
		double v = Math.max(0.1, cpsEma + wobble);
		double rounded = Math.round(v * 10.0) / 10.0;
		if (rounded == Math.floor(rounded)) {
			rounded += 0.1;   // 整数張り付き防止
		}
		return rounded;
	}

	/** 最後にサンプルされた素の間隔（ms） */
	public double lastInterval() {
		return lastInterval;
	}

	public double fatigue() {
		return fatigue;
	}

	public double jitterSigma() {
		return jitterSigma;
	}

	public double outlierProb() {
		return outlierProb;
	}

	public double skewShape() {
		return skewShape;
	}

	public long clickCount() {
		return clickCount;
	}

	public double cpsEma() {
		return cpsEma;
	}

	private double sampleOutlier(Random rand) {
		double roll = rand.nextDouble();
		if (roll < outlierProb) {
			// hesitation: たまに大きく遅れる（人間の迷い）
			return 1.35 + rand.nextDouble() * 0.55;
		}
		if (roll < outlierProb + 0.006) {
			// ごく稀に急ぎすぎる
			return 0.85 + rand.nextDouble() * 0.08;
		}
		return 1.0;
	}

	/** ガンマ分布（Marsaglia–Tsang）。k >= 1 前提（skewShape は 2.2 以上） */
	static double sampleGamma(Random rand, double k, double theta) {
		double d = k - 1.0 / 3.0;
		double c = 1.0 / Math.sqrt(9.0 * d);
		for (;;) {
			double x = gaussian(rand);
			double t = 1.0 + c * x;
			if (t <= 0.0) {
				continue;
			}
			double v = t * t * t;
			double logU = Math.log(Math.max(1.0e-12, rand.nextDouble()));
			double rhs = 0.5 * x * x + d * (1.0 - v + Math.log(v));
			if (logU < rhs) {
				return d * v * theta;
			}
		}
	}

	private static double gaussian(Random rand) {
		double u1 = Math.max(1.0e-12, rand.nextDouble());
		double u2 = rand.nextDouble();
		return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
	}

	private static double sampleLogNormal(Random rand, double sigma) {
		return Math.exp(gaussian(rand) * sigma - 0.5 * sigma * sigma);
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}
}
