package com.ifuto.fastplace;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 連打タイミングの統計モデル。人間の運動タイミング研究に基づく:
 *
 * <ul>
 *   <li>間隔 = 時計成分 + 運動実装遅延の差分（Wing &amp; Kristofferson 1973）。
 *       運動成分は I = C + M_i - M_{i-1} なので、lag-1 自己相関は負になる</li>
 *   <li>時計成分は ex-Gaussian（正規 + 指数裾）— 反応時間・間隔産出の標準モデル
 *       （Hohle 1965 / Luce 1986 / Dawson 1988）</li>
 *   <li>時計成分に低周波のゆっくりしたドリフトを持つ（人間の間隔産出の 1/f 的長距離相関、
 *       Gilden et al. 1995 Science の近似として、bounded slow random walk を加えた）</li>
 *   <li>基本間隔は設定値（既定 55ms）。元値にだけ疲労上限（+25%、time-on-task の劣化に相当）。
 *       連打していない間は指数的に回復する</li>
 *   <li>19 回は普通の乱数、20 回目だけ暗号学的乱数（SecureRandom）</li>
 *   <li>分布の偏り比（sigma/tau 比）は固定しない。クリックごとにランダムウォーク →
 *       標準偏差も毎回変わる</li>
 *   <li>毎回のクリックに ±2.5% の微小なブレ</li>
 *   <li>2 秒ごとに Jitter の強さ（jitterSigma）と外れ値率（outlierProb）が動的に変わる</li>
 *   <li>外れ値: hesitation（1.35〜1.9 倍、まれ）とごく稀な速い外れ値</li>
 *   <li>表示 CPS は小数1桁・整数にならず・毎 tick 細かく変動する</li>
 * </ul>
 */
public final class ClickTimingModel {
	/** 疲労の上限（元値に対する比率）。不便にならないよう高くしない */
	public static final double FATIGUE_MAX = 0.25;
	/** クリックごとの疲労増加（残り容量に対する比率。漸近に近づくだけ） */
	public static final double FATIGUE_GAIN = 0.008;
	/** 疲労の回復時定数（ミリ秒）。指数回復、半減期 ≈ 1.5 秒。idle ≥ 500ms のときだけ進む */
	public static final double RECOVERY_TAU_MS = 2200.0;
	/** 微小なブレ（毎回・±2.5%） */
	public static final double MICRO_JITTER = 0.025;
	/** ソフトクランプ。ここを下回る間隔は出さない */
	public static final double MIN_INTERVAL_MS = 15.0;
	public static final double MAX_INTERVAL_MS = 1000.0;

	// 動的シフトの範囲（2 秒ごと）
	public static final double SIGMA_MIN = 0.015;
	public static final double SIGMA_MAX = 0.075;
	public static final double OUTLIER_MIN = 0.012;
	public static final double OUTLIER_MAX = 0.045;

	// ex-Gaussian の平均に対する比率（論理形: 平均 = mu + tau、標準偏差 ~0.24*Mean）
	private static final double SIGMA_RATIO = 0.10;   // 正規成分（時計＋運動の実装揺れ）
	private static final double TAU_RATIO = 0.22;     // 指数成分（右裾 = 反応時間研究の tau に相当）
	private static final double MOTOR_RATIO = 0.075;  // 運動遅延成分 M（Wing–Kristofferson）

	/** 時計の低周波ドリフトが取りうる範囲（±平均の8%） */
	private static final double DRIFT_LIMIT = 0.08;

	/** idle と見なす閾値（これ以上クリックしなければ回復モード） */
	private static final long IDLE_THRESHOLD_MS = 500;

	private static final SecureRandom CRYPTO = new SecureRandom();

	private long clickCount = 0;
	private double fatigue = 0.0;
	private double cpsEma = 1000.0 / 55.0;
	private double lastInterval = 55.0;

	// 分布形状のランダムウォーク（比率は固定しない）
	private double sigmaRatioWalk = 1.0;
	private double tauRatioWalk = 1.0;
	private double jitterSigma = 0.04;
	private double outlierProb = 0.025;

	// 低周波ドリフト（bounded walk）と運動遅延の前回値
	private double drift = 0.0;
	private double prevMotor = 0.0;

	private long lastClickMs = -1;
	private long lastTickMs = -1;
	private long lastParamShiftMs = -1;

	public void resetStats() {
		clickCount = 0;
		fatigue = 0.0;
		cpsEma = 1000.0 / 55.0;
		sigmaRatioWalk = 1.0;
		tauRatioWalk = 1.0;
		jitterSigma = 0.04;
		outlierProb = 0.025;
		drift = 0.0;
		prevMotor = 0.0;
		lastClickMs = -1;
		lastTickMs = -1;
		lastParamShiftMs = -1;
	}

	/** 毎 tick 呼ぶ。疲労の回復（idle のときだけ）と 2 秒ごとの強さのシフトを進める。nowMs は明示指定 */
	public void tick(long nowMs) {
		if (lastTickMs < 0) {
			lastTickMs = nowMs;
			lastParamShiftMs = nowMs;
			return;
		}
		long dt = Math.max(0, nowMs - lastTickMs);
		lastTickMs = nowMs;

		// 連打していない間だけ指数的に回復（連打中は溜まり続ける）
		if (lastClickMs < 0 || nowMs - lastClickMs >= IDLE_THRESHOLD_MS) {
			fatigue *= Math.exp(-dt / RECOVERY_TAU_MS);
		}

		// 2 秒ごとに Jitter の強さと外れ値率を動的にシフト
		if (nowMs - lastParamShiftMs >= 2000) {
			Random r = ThreadLocalRandom.current();
			jitterSigma = clamp(jitterSigma + (r.nextDouble() - 0.5) * 0.03, SIGMA_MIN, SIGMA_MAX);
			outlierProb = clamp(outlierProb + (r.nextDouble() - 0.5) * 0.014, OUTLIER_MIN, OUTLIER_MAX);
			lastParamShiftMs = nowMs;
		}
	}

	/** このクリックが暗号学的乱数の番か（20 回ごと） */
	public boolean isCryptoTurn() {
		return (clickCount + 1) % 20L == 0;
	}

	/** クリックを1回発火したときの次の間隔ミリ秒を返す */
	public long fireClick(long nowMs, double baseMs) {
		Random rand = (clickCount + 1) % 20L == 0 ? CRYPTO : ThreadLocalRandom.current();
		clickCount++;
		lastClickMs = nowMs;

		// 疲労: 残り容量の FATIGUE_GAIN ずつ（漸近。元値は FATIGUE_MAX を超えない）
		fatigue += (FATIGUE_MAX - fatigue) * FATIGUE_GAIN;

		// 分布形状のランダムウォーク（偏り比を固定しない → 標準偏差も毎回変わる）
		sigmaRatioWalk = clamp(sigmaRatioWalk + (rand.nextDouble() - 0.5) * 0.06, 0.7, 1.5);
		tauRatioWalk = clamp(tauRatioWalk + (rand.nextDouble() - 0.5) * 0.07, 0.6, 1.6);

		double mean = baseMs * (1.0 + fatigue);          // 元値（+疲労、上限 FATIGUE_MAX）
		double sigmaC = mean * SIGMA_RATIO * sigmaRatioWalk;
		double tau = mean * TAU_RATIO * tauRatioWalk;
		double mu = mean - tau;

		// 低周波ドリフト（bounded walk。人間の 1/f 的長距離相関の近似）
		drift = clamp(drift + (rand.nextDouble() - 0.5) * mean * 0.05, -DRIFT_LIMIT * mean, DRIFT_LIMIT * mean);

		// 時計成分（ex-Gaussian = 正規 + 指数裾）
		double clock = mu + sigmaC * gaussian(rand) + drift + sampleExponential(rand, tau);

		// 運動遅延成分（Wing–Kristofferson: I = C + M_i - M_{i-1}）。lag-1 自己相関が負になる
		double motor = MOTOR_RATIO * mean * gaussian(rand);
		double interval = clock + motor - prevMotor;
		prevMotor = motor;

		// 毎回の微小なブレ（±2.5%）とログ正規 Jitter（平均1。上限を突破できる）
		double micro = 1.0 + (rand.nextDouble() * 2.0 - 1.0) * MICRO_JITTER;
		double jitter = sampleLogNormal(rand, jitterSigma);
		double outlier = sampleOutlier(rand);

		interval = interval * micro * jitter * outlier;
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

	/** 互換のため残す（旧テスト用）。sigma 比ウォークの現在値 */
	public double skewShape() {
		return sigmaRatioWalk;
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
			// hesitation: たまに大きく遅れる（注意の途切れ / 反応時間裾）
			return 1.35 + rand.nextDouble() * 0.55;
		}
		if (roll < outlierProb + 0.006) {
			// ごく稀に急ぎすぎる
			return 0.85 + rand.nextDouble() * 0.08;
		}
		return 1.0;
	}

	private static double sampleExponential(Random rand, double tau) {
		return -tau * Math.log(1.0 - Math.max(1.0e-12, rand.nextDouble()));
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
