package com.ifuto.fastplace.human;

import java.util.Random;

/**
 * 1 軸（ピッチ）の「人間らしい」視線移動モデル。Minecraft 非依存の純粋な計算クラス。
 *
 * <p>根拠にした文献:</p>
 * <ul>
 *   <li>Bahill, Clark &amp; Stark (1975) "The main sequence, a tool for studying human eye
 *       movements", Math. Biosci. 24:191–204. サッケードの振幅 A [deg] と持続時間
 *       D [ms] は線形（Collewijn et al. 1988 では D ≈ 2.7A + 23 ms）。</li>
 *   <li>Clark &amp; Stark 系のモデルで使われるピーク角速度の飽和式
 *       PV ≈ 850 (1 - e^(-A/6)) [deg/s]（Hsu, Clark &amp; Stark, Comput. Programs Biomed. 1976）。</li>
 *   <li>Flash &amp; Hogan (1985) "The coordination of arm movements", J. Neurosci. 5:1688–1703.
 *       到達運動は最小ジャーク軌道（10t³ - 15t⁴ + 6t⁵）でよく近似できる。
 *       視線も同じ形の速度プロファイルになる（Yarbus / サッケードの速度波形）。</li>
 *   <li>Harris &amp; Wolpert (1998) "Signal-dependent noise determines motor planning",
 *       Nature 394:780–784. 運動ノイズは指令（速度）の大きさに比例して増える。</li>
 *   <li>生理的振戦は 8–12 Hz・振幅 0.1–0.5 deg 程度。ここでは 20 Hz サンプリングで
 *       表現できる範囲（低周波の揺らぎ + AR(1) ノイズ）に落としてある。</li>
 * </ul>
 *
 * <p>大きい移動（前を向く → 足元を見る、など）は振幅依存の持続時間を持つ
 * 弾道運動として、小さいズレは時定数 ~70 ms の追従系として扱う。どちらも
 * 人間の「見てから置く」テンポに近づけるためのもの。</p>
 */
public final class HumanAim {
	/** これ以上のズレは弾道（サッケード的）に動かす */
	public static final double BALLISTIC_MIN_DEG = 3.0;
	/** 持続時間 D = SLOPE * A + INTERCEPT [ms]（Collewijn et al. 1988） */
	public static final double MAIN_SEQUENCE_SLOPE = 2.7;
	public static final double MAIN_SEQUENCE_INTERCEPT = 23.0;
	public static final double MIN_MOVE_MS = 40.0;
	public static final double MAX_MOVE_MS = 460.0;
	/** 小さいズレの追従時定数 [ms]（遅すぎず速すぎず） */
	public static final double PURSUIT_TAU_MS = 70.0;
	/** 静止時でも出る微小な揺れ（振戦）の標準偏差 [deg] */
	public static final double TREMOR_SIGMA_DEG = 0.045;
	/** ゆっくりした視線ドリフトの上限 [deg] */
	public static final double DRIFT_LIMIT_DEG = 0.30;
	/** 移動中に乗る信号依存ノイズの係数（1 deg/ms あたり） */
	public static final double SIGNAL_NOISE = 0.02;

	private final Random random;

	private double angle;
	private double velocity;

	private boolean planning;
	private double planFrom;
	private double planTo;
	private long planStartMs;
	private double planDurationMs;

	private double drift;
	private double tremor;
	/** 動きの速さで増える狙いの荒れ（出力にだけ乗る） */
	private double signalNoise;

	public HumanAim() {
		this(new Random());
	}

	public HumanAim(Random random) {
		this.random = random;
	}

	public void reset(double value) {
		this.angle = value;
		this.velocity = 0.0;
		this.planning = false;
		this.drift = 0.0;
		this.tremor = 0.0;
		this.signalNoise = 0.0;
	}

	/** ノイズ抜きの角度（内部状態） */
	public double angle() {
		return angle;
	}

	/** 実際に画面／サーバーへ出る角度（振戦 + ドリフト + 動き由来の荒れ、[-90, 90]） */
	public double output() {
		return clamp(angle + tremor + drift + signalNoise, -90.0, 90.0);
	}

	public boolean isMoving() {
		return planning || Math.abs(velocity) > 1.0e-4;
	}

	/**
	 * 目標角度へ 1 ステップ進める。
	 *
	 * @param target  目標ピッチ [deg]（+ が下向き）
	 * @param nowMs   現在時刻
	 * @param dtMs    前回からの経過（20 TPS なら通常 50 ms）
	 * @return ノイズ込みの出力角度
	 */
	public double step(double target, long nowMs, double dtMs) {
		double dt = clamp(dtMs, 1.0, 60.0);
		double error = target - angle;

		if (Math.abs(error) >= BALLISTIC_MIN_DEG) {
			// 新しい目標に変わった / まだ動いていない → 弾道運動を組み直す
			// 目標そのものが変わった / 計画が古すぎる、のときだけ組み直す。
			// （自分が動いたぶんの差で組み直すと、サッケードが間延びして
			//   メインシーケンスの時間から外れる）
			boolean needsPlan = !planning
				|| Math.abs(planTo - target) > 3.0
				|| nowMs - planStartMs > planDurationMs + 80.0;
			if (needsPlan) {
				planning = true;
				planFrom = angle;
				planTo = target;
				planStartMs = nowMs;
				planDurationMs = clamp(MAIN_SEQUENCE_SLOPE * Math.abs(error) + MAIN_SEQUENCE_INTERCEPT,
					MIN_MOVE_MS, MAX_MOVE_MS);
				velocity = 0.0;
			}
			double t = (nowMs - planStartMs) / planDurationMs;
			if (t >= 1.0) {
				angle = planTo;
				planning = false;
				velocity = 0.0;
			} else {
				double tt = Math.max(0.0, t);
				angle = planFrom + (planTo - planFrom) * minimumJerk(tt);
				// 最小ジャークの速度（deg/ms）
				velocity = (planTo - planFrom) * minimumJerkVelocity(tt) / planDurationMs;
			}
		} else {
			planning = false;
			// 追従系（臨界減衰）。目標が細かく動く設置中はこちらが支配的になる。
			// tick 間隔（50ms）でそのまま積分すると応答が歪むので 10ms 以下に分割する
			double remaining = dt;
			while (remaining > 1.0e-9) {
				double h = Math.min(10.0, remaining);
				remaining -= h;
				double omega = 1.0 / PURSUIT_TAU_MS;
				double accel = omega * omega * error - 2.0 * omega * velocity;
				velocity += accel * h;
				double maxV = peakVelocityDegPerMs(Math.max(0.5, Math.abs(error)));
				velocity = clamp(velocity, -maxV, maxV);
				angle += velocity * h;
				error = target - angle;
			}
			if (Math.abs(target - angle) < 0.01 && Math.abs(velocity) < 1.0e-4) {
				angle = target;
				velocity = 0.0;
			}
		}

		angle = clamp(angle, -90.0, 90.0);
		updateNoise(dt);
		return output();
	}

	private void updateNoise(double dt) {
		// 低周波ドリフト（bounded random walk）
		double stepDeg = 0.015 * Math.sqrt(dt / 50.0);
		drift += gaussian() * stepDeg;
		drift = clamp(drift, -DRIFT_LIMIT_DEG, DRIFT_LIMIT_DEG);

		// AR(1) の振戦。8-12 Hz を 20 TPS でならすとこの程度の係数になる
		double alpha = 0.72;
		tremor = alpha * tremor + (1.0 - alpha) * gaussian() * TREMOR_SIGMA_DEG;

		// 信号依存ノイズ（速く動くほど狙いが荒れる）。
		// 内部の角度は汚さず、出力にだけ乗せる（そうしないと狙いが永久に収束しない）
		if (Math.abs(velocity) > 1.0e-4) {
			signalNoise = gaussian() * SIGNAL_NOISE * Math.abs(velocity) * dt;
		} else {
			signalNoise *= 0.6;
		}
	}

	/** ピーク角速度 [deg/ms]。メインシーケンスの飽和式 */
	public static double peakVelocityDegPerMs(double amplitudeDeg) {
		double degPerSec = 850.0 * (1.0 - Math.exp(-amplitudeDeg / 6.0));
		return degPerSec / 1000.0;
	}

	/** 振幅から持続時間 [ms] を出す（テスト・表示用） */
	public static double durationFor(double amplitudeDeg) {
		return clamp(MAIN_SEQUENCE_SLOPE * Math.abs(amplitudeDeg) + MAIN_SEQUENCE_INTERCEPT,
			MIN_MOVE_MS, MAX_MOVE_MS);
	}

	private static double minimumJerk(double t) {
		double tt = clamp(t, 0.0, 1.0);
		return tt * tt * tt * (10.0 + tt * (-15.0 + 6.0 * tt));
	}

	private static double minimumJerkVelocity(double t) {
		double tt = clamp(t, 0.0, 1.0);
		return 30.0 * tt * tt * (1.0 - tt) * (1.0 - tt);
	}

	private double gaussian() {
		double u1 = Math.max(1.0e-12, random.nextDouble());
		double u2 = random.nextDouble();
		return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}
}
