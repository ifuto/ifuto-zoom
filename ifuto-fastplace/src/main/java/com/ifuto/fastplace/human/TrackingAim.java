package com.ifuto.fastplace.human;

import java.util.Random;

/**
 * 近接（剣）アシスト用の「人間の追従」モデル。
 *
 * <p>これは視線を機械的に目標へ貼り付けるモデルではない。<b>加算型</b>で、毎 tick
 * 目標へ向かう小さな角速度を 1 つぶんだけ足す。プレイヤーのマウス入力はそのまま
 * 生きるので、アシストが手を引っ張り返すことはない。</p>
 *
 * <p>人間らしさの出どころ:</p>
 * <ul>
 *   <li><b>反応時間</b> {@code ONSET}: 新しい相手を捉えてから動き出すまで 90–150ms
 *       （滑動性追従の立ち上がり潜時 100–130ms / Rashbass 1961, Lisberger et al. 1987）。</li>
 *   <li><b>速度プロファイル</b>: 目的の角速度はメインシーケンスの飽和式
 *       {@code PV ≈ 850 (1 − e^(−A/6)) deg/s}（Bahill et al. 1975, Collewijn et al. 1988）
 *       を係数で弱めたもの。上限を切ってあるので「サッケードのような瞬間移動」にはならない。</li>
 *   <li><b>立ち上がりの遅れ</b> {@code TAU_ACCEL}: 筋・眼球の慣性で速度は指数的に立ち上がる
 *       （Fitts 1954 の movement time、Woodworth 1899 の初期インパルス）。</li>
 *   <li><b>小さいズレはゆっくり</b>: 速度が振幅に比例するので、微調整はゆっくり詰まる
 *       （Fitts の難度と運動時間の関係）。</li>
 *   <li><b>振戦・ドリフト</b>: AR(1) の生理的振戦（8–12Hz を 20TPS に落としたもの）と
 *       有界ランダムウォークのドリフト。目標点だけを揺らすので、向きは累積しない
 *       （累積させるとランダムウォークで外れる＝これが「再現しすぎて外す」の正体）。</li>
 *   <li><b>行き過ぎない</b>: 1 tick の移動量は残りの誤差でクリップ。目標を通り越さない。</li>
 * </ul>
 *
 * <p>「外さない」ための保証は {@link #pullInside} と、呼び出し側の
 * 「悪化したら適用しない」判定で担保する。</p>
 */
public final class TrackingAim {
	/** これ以下は動かない（人間の停止精度 = わずかな undershoot）[deg] */
	public static final double DEADZONE_DEG = 0.08;
	/** 角速度の立ち上がり時定数 [ms] */
	public static final double TAU_ACCEL_MS = 65.0;
	/** アシストが出せる角速度の上限 [deg/s]（サッケードの速さにはしない） */
	public static final double MAX_SPEED_DEG_PER_SEC = 220.0;
	/** ごく小さいズレでも最低限これだけは詰める [deg/s] */
	public static final double MIN_SPEED_DEG_PER_SEC = 24.0;
	/** メインシーケンス式を何倍に弱めるか（1.0 でサッケード相当） */
	public static final double SPEED_GAIN = 0.35;
	/** 反応時間の範囲 [ms] */
	public static final double ONSET_MIN_MS = 90.0;
	public static final double ONSET_MAX_MS = 150.0;
	/** 生理的振戦の大きさ [deg] と AR(1) 係数 */
	public static final double TREMOR_SIGMA_DEG = 0.05;
	public static final double TREMOR_ALPHA = 0.72;
	/** 低周波ドリフトの上限 [deg] と 1 tick あたりの歩幅 [deg] */
	public static final double DRIFT_LIMIT_DEG = 0.12;
	public static final double DRIFT_STEP_DEG = 0.012;

	private final Random random;

	/** いま出している角速度 [deg/s]（慣性つき） */
	private double speed;
	private double tremorX;
	private double tremorY;
	private double driftX;
	private double driftY;
	private long lastMs = -1;
	/** ここまでは動かない（反応時間） */
	private long onsetUntil;
	/** false にすると反応時間と振戦を切る（設定で人間らしさ OFF のとき） */
	private boolean humanize = true;

	public TrackingAim() {
		this(new Random());
	}

	public TrackingAim(Random random) {
		this.random = random;
	}

	/** 新しい相手を捉えた瞬間に呼ぶ。反応時間ぶんだけ動き出しが遅れる */
	public void acquire(long nowMs) {
		onsetUntil = nowMs + Math.round(ONSET_MIN_MS + random.nextDouble() * (ONSET_MAX_MS - ONSET_MIN_MS));
		speed = 0.0;
	}

	public void setHumanize(boolean on) {
		humanize = on;
		if (!on) {
			tremorX = 0.0;
			tremorY = 0.0;
			driftX = 0.0;
			driftY = 0.0;
		}
	}

	public void reset() {
		speed = 0.0;
		tremorX = 0.0;
		tremorY = 0.0;
		driftX = 0.0;
		driftY = 0.0;
		lastMs = -1L;
		onsetUntil = 0L;
	}

	public boolean resting(long nowMs) {
		return nowMs < onsetUntil;
	}

	/** いまの角速度 [deg/s]（テスト・デバッグ用） */
	public double speedDegPerSec() {
		return speed;
	}

	/**
	 * 1 tick ぶんの「足す」回転量を返す。
	 *
	 * @param nowMs       現在時刻
	 * @param errYawDeg   目標のヨー − いまのヨーの差 [deg]（±180 に折り返し済み）
	 * @param errPitchDeg 目標のピッチ − いまのピッチの差 [deg]
	 * @param strength    アシストの強さ（0.2〜1.0 想定）
	 * @return {deltaYaw, deltaPitch} [deg]
	 */
	public double[] step(long nowMs, double errYawDeg, double errPitchDeg, double strength) {
		double dt = lastMs < 0L ? 50.0 : Math.max(5.0, Math.min(120.0, nowMs - lastMs));
		lastMs = nowMs;

		if (humanize) {
			updateNoise(dt);
		}

		// 振戦・ドリフトは「狙う点」を揺らす。向きそのものには積まない
		double ey = errYawDeg + tremorX + driftX;
		double ep = errPitchDeg + tremorY + driftY;
		double mag = Math.hypot(ey, ep);

		if ((humanize && nowMs < onsetUntil) || mag < DEADZONE_DEG) {
			speed *= 0.85;
			return new double[] {0.0, 0.0};
		}

		double want = clamp(peakSpeed(mag) * SPEED_GAIN,
			MIN_SPEED_DEG_PER_SEC, MAX_SPEED_DEG_PER_SEC) * clampStrength(strength);
		speed += (want - speed) * Math.min(1.0, dt / TAU_ACCEL_MS);

		double step = Math.min(speed * dt / 1000.0, mag);
		return new double[] {ey / mag * step, ep / mag * step};
	}

	/**
	 * 命中判定の角度半径を超えて外れたぶんを、内側へ引き戻す補正量を返す。
	 * 呼び出し側は「アシストの結果が悪化したとき」だけこれを足せばよい。
	 */
	public static double[] pullInside(double errYawDeg, double errPitchDeg, double limitDeg) {
		double mag = Math.hypot(errYawDeg, errPitchDeg);
		if (mag <= limitDeg || mag < 1.0e-9 || limitDeg <= 0.0) {
			return new double[] {0.0, 0.0};
		}
		double k = (mag - limitDeg) / mag;
		return new double[] {errYawDeg * k, errPitchDeg * k};
	}

	/** メインシーケンスのピーク角速度 [deg/s] */
	public static double peakSpeed(double amplitudeDeg) {
		return 850.0 * (1.0 - Math.exp(-Math.abs(amplitudeDeg) / 6.0));
	}

	private void updateNoise(double dt) {
		double stepDeg = DRIFT_STEP_DEG * Math.sqrt(dt / 50.0);
		driftX = clamp(driftX + gaussian() * stepDeg, -DRIFT_LIMIT_DEG, DRIFT_LIMIT_DEG);
		driftY = clamp(driftY + gaussian() * stepDeg, -DRIFT_LIMIT_DEG, DRIFT_LIMIT_DEG);
		tremorX = TREMOR_ALPHA * tremorX + (1.0 - TREMOR_ALPHA) * gaussian() * TREMOR_SIGMA_DEG;
		tremorY = TREMOR_ALPHA * tremorY + (1.0 - TREMOR_ALPHA) * gaussian() * TREMOR_SIGMA_DEG;
	}

	private double gaussian() {
		double u1 = Math.max(1.0e-12, random.nextDouble());
		double u2 = random.nextDouble();
		return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
	}

	/** 強さ 0.2 でも「まったく効かない」にはしない（速度上限の 45% は残す） */
	private static double clampStrength(double strength) {
		double s = clamp(strength, 0.05, 1.0);
		return 0.45 + 0.55 * s;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}
}
