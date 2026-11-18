package com.ifuto.fastplace.human;

import java.util.Random;

/**
 * 近接（剣）アシスト用の「人間の追従」モデル。
 *
 * <p>これは視線を機械的に目標へ貼り付けるモデルではない。<b>加算型</b>で、目標へ向かう
 * 小さな角速度を 1 フレームぶんだけ足す。プレイヤーのマウス入力はそのまま生きる。</p>
 *
 * <p><b>カクつかせないための設計</b></p>
 * <ul>
 *   <li>1 回の更新は「1 フレーム」。20 回/秒の tick で書くと 50ms ごとに段差が出るので、
 *       描画フレームごとに {@link #stepFrame} を呼ぶ（速度は deg/s なので
 *       60fps でも 20fps でも同じ見た目になる）。</li>
 *   <li>振戦とドリフトは <b>連続時間の OU 過程</b> として刻む（{@code α^(dt/50)}）。
 *       フレームレートが上がってもノイズの帯域が変わらない ＝ 高 fps でザワつかない。</li>
 *   <li>速度は指数的に立ち上がる（筋・眼球の慣性）。1 フレームの移動量は残り誤差で
 *       クリップするので行き過ぎない。</li>
 * </ul>
 *
 * <p><b>人間らしさの出どころ</b></p>
 * <ul>
 *   <li><b>反応時間</b> 90–150ms（Rashbass 1961 / Lisberger et al. 1987）</li>
 *   <li><b>速度プロファイル</b> メインシーケンス {@code PV ≈ 850(1−e^(−A/6))} deg/s
 *       を弱めたもの（Bahill 1975 / Collewijn 1988）</li>
 *   <li><b>滑らかさの設定</b> 立ち上がりの時定数を伸ばせる（大きいほどゆったり寄る）</li>
 *   <li><b>進行方向による偏り</b> {@code strafeBiasDeg}。歩きながら狙うと、体の移動に
 *       対して視覚の補償がわずかに残り、狙いが進行方向と<b>逆</b>へ数度ずれる。
 *       これは「狙う点」をずらすだけで、向きには積まれない。</li>
 *   <li><b>行き過ぎない / 悪化しない</b> 呼び出し側の「悪化したら適用しない」判定と
 *       {@link #pullInside} で担保する。</li>
 * </ul>
 */
public final class TrackingAim {
	/** これ以下は動かない（人間の停止精度 = わずかな undershoot）[deg] */
	public static final double DEADZONE_DEG = 0.08;
	/** 角速度の立ち上がり時定数 [ms]（smoothing = 1.0 のとき） */
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
	/** 生理的振戦の大きさ [deg] と AR(1) 係数（50ms 刻みでの値） */
	public static final double TREMOR_SIGMA_DEG = 0.05;
	public static final double TREMOR_ALPHA = 0.72;
	/** 低周波ドリフトの上限 [deg] と 50ms あたりの歩幅 [deg] */
	public static final double DRIFT_LIMIT_DEG = 0.12;
	public static final double DRIFT_STEP_DEG = 0.012;
	/** 進行方向バイアスの上限 [deg] */
	public static final double MAX_STRAFE_BIAS_DEG = 4.0;

	private final Random random;

	/** いま出している角速度 [deg/s]（慣性つき） */
	private double speed;
	private double tremorX;
	private double tremorY;
	private double driftX;
	private double driftY;
	private long lastMs = -1L;
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

	/** tick 由来の呼び出し（dt は前回からの経過時間から求める） */
	public double[] step(long nowMs, double errYawDeg, double errPitchDeg, double strength) {
		double dt = lastMs < 0L ? 50.0 : Math.max(5.0, Math.min(120.0, nowMs - lastMs));
		return stepAt(nowMs, dt, errYawDeg, errPitchDeg, strength, 0.0, 1.0);
	}

	/**
	 * フレーム由来の呼び出し。
	 *
	 * @param nowMs         現在時刻（反応時間の判定に使う）
	 * @param dtMs          前フレームからの経過 [ms]
	 * @param errYawDeg     目標ヨー − いまのヨー [deg]（±180 折り返し済み）
	 * @param errPitchDeg   目標ピッチ − いまのピッチ [deg]
	 * @param strength      アシストの強さ（0.2〜1.0）
	 * @param strafeBiasDeg 進行方向による狙いの偏り [deg]
	 * @param smoothing     滑らかさ（1.0 標準、大きいほど立ち上がりが遅い）
	 * @return {deltaYaw, deltaPitch} [deg]
	 */
	public double[] stepFrame(long nowMs, double dtMs, double errYawDeg, double errPitchDeg,
			double strength, double strafeBiasDeg, double smoothing) {
		return stepAt(nowMs, dtMs, errYawDeg, errPitchDeg, strength, strafeBiasDeg, smoothing);
	}

	private double[] stepAt(long nowMs, double dtMs, double errYawDeg, double errPitchDeg,
			double strength, double strafeBiasDeg, double smoothing) {
		lastMs = nowMs;
		double dt = clamp(dtMs, 2.0, 120.0);

		if (humanize) {
			updateNoise(dt);
		}

		// 振戦・ドリフト・進行方向バイアスは「狙う点」をずらす。向きには積まない
		double biasYaw = humanize ? clamp(strafeBiasDeg, -MAX_STRAFE_BIAS_DEG, MAX_STRAFE_BIAS_DEG) : 0.0;
		double ey = errYawDeg + biasYaw + tremorX + driftX;
		double ep = errPitchDeg + tremorY + driftY;
		double mag = Math.hypot(ey, ep);

		if ((humanize && nowMs < onsetUntil) || mag < DEADZONE_DEG) {
			speed *= Math.pow(0.85, dt / 50.0);
			return new double[] {0.0, 0.0};
		}

		double want = clamp(peakSpeed(mag) * SPEED_GAIN,
			MIN_SPEED_DEG_PER_SEC, MAX_SPEED_DEG_PER_SEC) * clampStrength(strength);
		double tau = TAU_ACCEL_MS / clamp(smoothing, 0.4, 2.5);
		speed += (want - speed) * Math.min(1.0, dt / tau);

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

	/**
	 * 連続時間の OU 過程としてノイズを進める。
	 * {@code α^(dt/50)} で刻むので、フレームレートが変わっても帯域と分散が変わらない。
	 */
	private void updateNoise(double dt) {
		double driftStep = DRIFT_STEP_DEG * Math.sqrt(dt / 50.0);
		driftX = clamp(driftX + gaussian() * driftStep, -DRIFT_LIMIT_DEG, DRIFT_LIMIT_DEG);
		driftY = clamp(driftY + gaussian() * driftStep, -DRIFT_LIMIT_DEG, DRIFT_LIMIT_DEG);

		double alpha = Math.pow(TREMOR_ALPHA, dt / 50.0);
		double gain = Math.sqrt(Math.max(0.0, 1.0 - alpha * alpha));
		tremorX = alpha * tremorX + gain * gaussian() * TREMOR_SIGMA_DEG;
		tremorY = alpha * tremorY + gain * gaussian() * TREMOR_SIGMA_DEG;
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
