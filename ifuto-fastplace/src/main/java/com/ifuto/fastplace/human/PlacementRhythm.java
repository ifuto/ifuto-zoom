package com.ifuto.fastplace.human;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ブロック設置（右クリック）のタイミングモデル。Minecraft 非依存。
 *
 * <p>設置のテンポそのものは「足元のブロックが次に必要になる距離」で決まる
 * （＝歩く速さに同期する）。ここが受け持つのは、その上に乗る人間の揺らぎ:</p>
 *
 * <ul>
 *   <li>Wing &amp; Kristofferson (1973) "Response delays and the timing of discrete motor
 *       responses", Percept. Psychophys. 14:5–12. 連続運動の間隔は
 *       I = 時計成分 + 運動遅延の差分で、lag-1 自己相関が負になる。</li>
 *   <li>Hohle (1965) / Luce (1986): 反応時間・間隔産出は ex-Gaussian（正規 + 指数裾）。</li>
 *   <li>Gilden, Thornton &amp; Mallon (1995) Science 267:1837–1839: 人間の間隔産出には
 *       1/f の長距離相関がある。ここでは低周波ドリフトで近似する。</li>
 *   <li>Harris &amp; Wolpert (1998): 速く打つほどタイミングの分散が増える（信号依存ノイズ）。</li>
 * </ul>
 *
 * <p>長く続けると疲労でわずかに遅くなり、手を止めると指数回復する。
 * たまに「ためらい」（大きく遅れる）と「二度打ち」（少し速い）が混ざる。</p>
 */
public final class PlacementRhythm {
	/** 連続設置の最小間隔の基準 [ms]（≈10 CPS まで許す） */
	public static final double BASE_MS = 100.0;
	public static final double MIN_GAP_MS = 55.0;
	public static final double MAX_GAP_MS = 620.0;
	/** 疲労の上限（基準間隔に対する比率） */
	public static final double FATIGUE_MAX = 0.22;
	/** 1 回ごとの疲労の増え方（残り容量に対する比率） */
	public static final double FATIGUE_GAIN = 0.010;
	/** 疲労の回復時定数 [ms]。idle が 400 ms 以上で進む */
	public static final double RECOVERY_TAU_MS = 2400.0;
	public static final long IDLE_THRESHOLD_MS = 400L;
	/** ためらい（大きく遅れる）の確率 */
	public static final double HESITATION_PROB = 0.045;
	/** 二度打ち（少し速い）の確率 */
	public static final double DOUBLE_TAP_PROB = 0.035;
	/** 間隔の対数正規ゆらぎ */
	public static final double LOG_SIGMA = 0.17;
	/** 「狙いが合ってから押すまで」の反応時間（ex-Gaussian, 平均 ≈ 45 ms） */
	public static final double REACTION_MEAN_MS = 29.0;
	public static final double REACTION_TAU_MS = 16.0;
	public static final double REACTION_SIGMA_MS = 9.0;

	private double fatigue;
	private long lastPlacementMs = -1;
	private long lastTickMs = -1;
	private double drift = 1.0;

	/** 毎 tick 呼ぶ。手が止まっている間の疲労回復と、ゆっくりしたテンポの揺れを進める */
	public void tick(long nowMs) {
		if (lastTickMs < 0) {
			lastTickMs = nowMs;
			return;
		}
		long dt = Math.max(0L, nowMs - lastTickMs);
		lastTickMs = nowMs;
		if (lastPlacementMs < 0 || nowMs - lastPlacementMs >= IDLE_THRESHOLD_MS) {
			fatigue *= Math.exp(-dt / RECOVERY_TAU_MS);
		}
		// 1/f 的な低周波のテンポ揺れ（bounded walk）
		drift += (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.012;
		drift = Math.max(0.90, Math.min(1.10, drift));
	}

	/** 次の設置までの最小間隔 [ms] */
	public long nextGapMs(Random random, long nowMs, boolean safeToDelay) {
		lastPlacementMs = nowMs;

		fatigue += (FATIGUE_MAX - fatigue) * FATIGUE_GAIN;
		double mean = BASE_MS * (1.0 + fatigue) * drift;
		double value = mean * sampleLogNormal(random, LOG_SIGMA);

		if (safeToDelay && random.nextDouble() < HESITATION_PROB) {
			// 注意の途切れ・置き直し
			value *= 1.35 + random.nextDouble() * 0.75;
		} else if (!safeToDelay && random.nextDouble() < DOUBLE_TAP_PROB) {
			// 二度打ち（詰めて置く）
			value *= 0.70 + random.nextDouble() * 0.12;
		}
		return (long) Math.round(clamp(value, MIN_GAP_MS, MAX_GAP_MS));
	}

	/** 狙いが合ってから実際に押すまでの遅れ [ms] */
	public long reactionMs(Random random) {
		double value = REACTION_MEAN_MS + REACTION_SIGMA_MS * gaussian(random)
			+ sampleExponential(random, REACTION_TAU_MS);
		return (long) Math.round(clamp(value, 8.0, 190.0));
	}

	public double fatigue() {
		return fatigue;
	}

	public double tempoDrift() {
		return drift;
	}

	public void reset() {
		fatigue = 0.0;
		lastPlacementMs = -1;
		lastTickMs = -1;
		drift = 1.0;
	}

	public static double gaussian(Random random) {
		double u1 = Math.max(1.0e-12, random.nextDouble());
		double u2 = random.nextDouble();
		return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
	}

	public static double sampleExponential(Random random, double tau) {
		return -tau * Math.log(1.0 - Math.max(1.0e-12, random.nextDouble()));
	}

	public static double sampleLogNormal(Random random, double sigma) {
		return Math.exp(gaussian(random) * sigma - 0.5 * sigma * sigma);
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}
}
