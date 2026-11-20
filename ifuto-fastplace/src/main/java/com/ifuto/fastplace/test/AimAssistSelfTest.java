package com.ifuto.fastplace.test;

import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.human.TrackingAim;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * 近接アシストの人間モデル（{@link TrackingAim}）のヘッドレス自己テスト。
 *
 * <p>「人間らしい」と「外さない」の両方を数値で固定する。CI で毎回走る。</p>
 */
public final class AimAssistSelfTest {
	private static int passed = 0;
	/** RShift で狙い始めたときの想定距離 [blocks] */
	private static final double DISTANCE = 3.0;
	/** 当たり判定の半幅 + 余白 [blocks] */
	private static final double HIT_HALF_WIDTH = 0.40;
	/** 当たり判定の角度半径 [deg]（距離 3.0） */
	private static final double HIT_RADIUS = Math.toDegrees(Math.atan2(HIT_HALF_WIDTH, DISTANCE - 0.3));

	public static void main(String[] args) {
		testOnsetDelay();
		testConvergence();
		testNoOvershoot();
		testSpeedCap();
		testMicroNoiseAtRest();
		testStrengthScaling();
		testPullInside();
		testStrafingTargetStaysOnTarget();
		testStrafingWithLeadAndSafety();
		testClickHitbox();
		testStrafeBias();
		testFrameStepsAreSmooth();
		testImplementationDisabledWhenHumanizeOff();
		System.out.println("[aim-test] all " + passed + " checks passed (hit radius "
			+ String.format(java.util.Locale.ROOT, "%.2f", HIT_RADIUS) + " deg at " + DISTANCE + " blocks)");
	}

	/** 新しい相手を捉えても、反応時間までは動かない */
	private static void testOnsetDelay() {
		TrackingAim model = new TrackingAim(new Random(1));
		model.acquire(0L);
		model.step(0L, 20.0, 0.0, 1.0);
		double early = 0.0;
		for (long t = 10L; t <= TrackingAim.ONSET_MIN_MS - 10.0; t += 10L) {
			double[] d = model.step(t, 20.0, 0.0, 1.0);
			early = Math.max(early, Math.hypot(d[0], d[1]));
		}
		check(early == 0.0, "no motion before the reaction time (max step " + early + ")");

		TrackingAim fresh = new TrackingAim(new Random(2));
		fresh.acquire(0L);
		boolean moved = false;
		for (long t = 0L; t <= 400L; t += 25L) {
			double[] d = fresh.step(t, 20.0, 0.0, 1.0);
			if (Math.hypot(d[0], d[1]) > 0.01) {
				moved = true;
				check(t >= TrackingAim.ONSET_MIN_MS, "starts moving only after onset: " + t + "ms");
				break;
			}
		}
		check(moved, "eventually starts moving");
		System.out.println("[aim-test] onset ok");
	}

	/** 20 度のズレは数百 ms で詰まる（速すぎず遅すぎず） */
	private static void testConvergence() {
		TrackingAim model = new TrackingAim(new Random(3));
		model.acquire(0L);
		double view = 0.0;
		double goal = 20.0;
		long settledAt = -1L;
		long t = 0L;
		for (int i = 0; i < 40; i++) {
			t += 50L;
			double[] d = model.step(t, goal - view, 0.0, 1.0);
			view += d[0];
			if (settledAt < 0 && Math.abs(goal - view) < 0.25) {
				settledAt = t;
			}
		}
		check(settledAt > 0, "20deg error settles");
		check(settledAt >= TrackingAim.ONSET_MIN_MS && settledAt <= 700L,
			"20deg settle time is human-scale: " + settledAt + "ms");
		check(Math.abs(goal - view) < 0.3, "final residual small: " + Math.abs(goal - view));
		System.out.println("[aim-test] 20deg closed in " + settledAt + "ms (residual "
			+ String.format(java.util.Locale.ROOT, "%.3f", Math.abs(goal - view)) + "deg)");
	}

	/** 目標を通り越さない（オーバーシュートしない） */
	private static void testNoOvershoot() {
		TrackingAim model = new TrackingAim(new Random(4));
		model.acquire(0L);
		double view = 0.0;
		double goal = 15.0;
		double worstOvershoot = 0.0;
		long t = 0L;
		for (int i = 0; i < 60; i++) {
			t += 50L;
			double[] d = model.step(t, goal - view, 0.0, 1.0);
			view += d[0];
			worstOvershoot = Math.max(worstOvershoot, view - goal);
		}
		check(worstOvershoot < 0.4, "no overshoot: " + worstOvershoot + "deg");
		System.out.println("[aim-test] overshoot " + String.format(java.util.Locale.ROOT, "%.3f", worstOvershoot) + "deg");
	}

	/** 1 tick の回転は人間の上限速度を超えない */
	private static void testSpeedCap() {
		TrackingAim model = new TrackingAim(new Random(5));
		model.acquire(0L);
		double worst = 0.0;
		long t = 0L;
		for (int i = 0; i < 40; i++) {
			t += 50L;
			double[] d = model.step(t, 120.0, 60.0, 1.0);
			worst = Math.max(worst, Math.hypot(d[0], d[1]) / 0.05);
		}
		double cap = TrackingAim.MAX_SPEED_DEG_PER_SEC * 1.02;
		check(worst <= cap, "speed stays under the cap: " + worst + " deg/s <= " + cap);
		System.out.println("[aim-test] peak speed " + String.format(java.util.Locale.ROOT, "%.1f", worst) + " deg/s");
	}

	/** 狙いが合っているときは、ほぼ動かない（振戦だけ） */
	private static void testMicroNoiseAtRest() {
		TrackingAim model = new TrackingAim(new Random(6));
		model.acquire(0L);
		long t = 0L;
		double worst = 0.0;
		for (int i = 0; i < 400; i++) {
			t += 50L;
			double[] d = model.step(t, 0.0, 0.0, 1.0);
			worst = Math.max(worst, Math.hypot(d[0], d[1]));
		}
		check(worst < 0.35, "no drift when on target: " + worst + "deg/tick");
		System.out.println("[aim-test] rest wobble " + String.format(java.util.Locale.ROOT, "%.4f", worst) + "deg/tick");
	}

	/** 強さを下げると遅くなる（が、まったく効かないわけではない） */
	private static void testStrengthScaling() {
		long weak = settleTime(0.25);
		long strong = settleTime(1.0);
		check(weak > strong, "weaker assist is slower: weak=" + weak + "ms strong=" + strong + "ms");
		check(weak < 4000L, "weak assist still converges: " + weak + "ms");
		System.out.println("[aim-test] settle weak(0.25)=" + weak + "ms strong(1.0)=" + strong + "ms");
	}

	private static long settleTime(double strength) {
		TrackingAim model = new TrackingAim(new Random(7));
		model.acquire(0L);
		double view = 0.0;
		long t = 0L;
		for (int i = 0; i < 200; i++) {
			t += 50L;
			double[] d = model.step(t, 20.0 - view, 0.0, strength);
			view += d[0];
			if (Math.abs(20.0 - view) < 0.25) {
				return t;
			}
		}
		return t;
	}

	/** 安全域の外に出たぶんだけ内側へ戻す補正が正しく出る */
	private static void testPullInside() {
		double[] fix = TrackingAim.pullInside(6.0, 0.0, 2.0);
		check(Math.hypot(6.0 - fix[0], fix[1]) <= 2.0 + 1.0e-9,
			"pullInside brings the error inside the limit: " + Math.hypot(6.0 - fix[0], fix[1]));
		check(fix[0] > 0.0 && Math.abs(fix[1]) < 1.0e-9, "pull direction is toward the target");
		double[] none = TrackingAim.pullInside(1.0, 0.5, 2.0);
		check(none[0] == 0.0 && none[1] == 0.0, "already inside -> no correction");
		System.out.println("[aim-test] pullInside ok");
	}

	/**
	 * 最大速度で横に動く相手（5.6 m/s）を 3.0 ブロックから追う。
	 * ロック中の定常状態で、当たり判定の角度半径の内側に留まること。
	 */
	private static void testStrafingTargetStaysOnTarget() {
		TrackingAim model = new TrackingAim(new Random(8));
		// すでにロック中（反応時間は終わっている）という設定
		model.acquire(-5000L);
		double view = 0.0;
		double x = 0.0;
		long t = 0L;
		double worst = 0.0;
		double tail = 0.0;
		for (int i = 0; i < 200; i++) {
			t += 50L;
			x += 5.6 * 0.05;
			double goal = Math.toDegrees(Math.atan2(x, DISTANCE));
			double[] d = model.step(t, goal - view, 0.0, 0.75);
			view += d[0];
			double residual = Math.abs(goal - view);
			worst = Math.max(worst, residual);
			if (t > 1000L) {
				tail = Math.max(tail, residual);
			}
		}
		check(worst < HIT_RADIUS * 0.6,
			"never leaves the target's hitbox while strafing: " + worst + "deg < " + (HIT_RADIUS * 0.6));
		check(tail < 2.5, "steady-state lag stays small: " + tail + "deg");
		System.out.println("[aim-test] strafing 5.6m/s: worst residual "
			+ String.format(java.util.Locale.ROOT, "%.2f", worst) + "deg, tail "
			+ String.format(java.util.Locale.ROOT, "%.2f", tail) + "deg");
	}

	/**
	 * 先読みと安全域ルールを込みにした場合（{@code AimAssistModule} と同じ式）でも、
	 * 横移動する相手の当たり判定の内側に留まること。
	 */
	private static void testStrafingWithLeadAndSafety() {
		final double leadSeconds = 0.065;
		final double leadGain = 0.40;
		final double speedPerTick = 0.28;          // 5.6 m/s
		final double safeLimit = HIT_RADIUS * 0.55;

		TrackingAim model = new TrackingAim(new Random(10));
		model.acquire(-5000L);
		double view = 0.0;
		double x = 0.0;
		double worst = 0.0;
		long t = 0L;
		for (int i = 0; i < 200; i++) {
			t += 50L;
			x += speedPerTick;
			double trueGoal = Math.toDegrees(Math.atan2(x, DISTANCE));
			double lead = Math.toDegrees(Math.atan2(speedPerTick * leadSeconds * 20.0 * leadGain, DISTANCE));
			double errGoal = trueGoal + lead - view;
			double[] d = model.step(t, errGoal, 0.0, 0.75);
			double beforeTrue = Math.abs(trueGoal - view);
			double afterTrue = Math.abs(trueGoal - (view + d[0]));
			double afterGoal = Math.abs(errGoal - d[0]);
			if (!(afterGoal > 0.02 && afterTrue > beforeTrue + 0.02)) {
				double next = view + d[0];
				if (afterTrue > safeLimit) {
					double[] fix = TrackingAim.pullInside(trueGoal - view, 0.0, safeLimit);
					double fixMag = Math.abs(fix[0]);
					if (fixMag > 1.0e-9) {
						double k = Math.min(1.0, Math.abs(d[0]) / fixMag);
						double candidate = view + fix[0] * k;
						if (Math.abs(trueGoal - candidate) <= beforeTrue + 1.0e-6) {
							next = candidate;
						}
					}
				}
				view = next;
			}
			worst = Math.max(worst, Math.abs(trueGoal - view));
		}
		check(worst < HIT_RADIUS * 0.6,
			"stays inside the hitbox with lead + safety: " + worst + "deg");
		System.out.println("[aim-test] lead + safety worst residual "
			+ String.format(java.util.Locale.ROOT, "%.2f", worst) + "deg");
	}

	/** 人間らしさ OFF なら反応時間も振戦も無しで素直に詰める */
	private static void testImplementationDisabledWhenHumanizeOff() {
		TrackingAim model = new TrackingAim(new Random(9));
		model.setHumanize(false);
		model.acquire(0L);
		double[] first = model.step(50L, 20.0, 0.0, 1.0);
		check(Math.hypot(first[0], first[1]) > 0.0, "moves immediately when humanize is off");
		System.out.println("[aim-test] humanize off ok");
	}

	/**
	 * クリック判定: 当たり判定を 1.2 倍に広げた箱に視線が当たれば掴む。
	 * 元の箱の外・広げた箱の内側を狙うケースが「1.2x」の意味。
	 */
	private static void testClickHitbox() {
		Box playerBox = new Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
		Box scaled = AimAssistModule.scaledHitbox(playerBox, 1.2);
		check(Math.abs(scaled.getLengthX() - 0.6 * 1.2) < 1.0e-9, "width x1.2: " + scaled.getLengthX());
		check(Math.abs(scaled.getLengthY() - 1.8 * 1.2) < 1.0e-9, "height x1.2: " + scaled.getLengthY());

		Vec3d eye = new Vec3d(0.0, 1.5, -4.0);
		// 右に 0.33 ずれた視線: 元の箱（半幅 0.3）は外れるが、1.2 倍（半幅 0.36）は当たる
		Vec3d look = new Vec3d(0.33 / 4.0, 0.0, 1.0).normalize();
		Vec3d end = eye.add(look.multiply(6.0));
		check(playerBox.raycast(eye, end).isEmpty(), "0.33 offset misses the real hitbox");
		check(scaled.raycast(eye, end).isPresent(), "0.33 offset hits the 1.2x hitbox");

		Vec3d outside = new Vec3d(0.5 / 4.0, 0.0, 1.0).normalize();
		check(scaled.raycast(eye, eye.add(outside.multiply(6.0))).isEmpty(),
			"0.5 offset misses even the 1.2x hitbox");
		System.out.println("[aim-test] click hitbox: 1.2x catches 0.33 but not 0.5");
	}

	/** 横移動の向きと逆向きに数度ずれる（人間の自己運動の残差） */
	private static void testStrafeBias() {
		double right = AimAssistModule.strafeBiasTarget(0.216, 1.2);
		double left = AimAssistModule.strafeBiasTarget(-0.216, 1.2);
		check(right < 0.0 && left > 0.0, "bias opposes the strafe: " + right + " / " + left);
		check(Math.abs(right + 1.2) < 1.0e-9, "full strafe gives the configured amount: " + right);
		check(AimAssistModule.strafeBiasTarget(0.0, 1.2) == 0.0, "standing still has no bias");
		check(Math.abs(AimAssistModule.strafeBiasTarget(5.0, 1.2)) <= 1.2 + 1.0e-9, "bias is clamped");
		System.out.printf(java.util.Locale.ROOT, "[aim-test] strafe bias: right=%.2f deg%n", right);
	}

	/**
	 * フレーム単位で動かすと 1 フレームの段差が小さく、20Hz で書くより滑らかなこと。
	 * （カクつき = 1 回の更新で動く角度が大きすぎること）
	 */
	private static void testFrameStepsAreSmooth() {
		double frameStep = largestStep(60.0, 8.0);
		double tickStep = largestStep(20.0, 8.0);
		check(frameStep < tickStep * 0.5,
			"60fps steps are much smaller than 20Hz steps: " + frameStep + " vs " + tickStep);

		// 8 度のズレは 1 秒以内に 1 度未満まで詰まる
		TrackingAim model = new TrackingAim(new Random(3));
		double err = 8.0;
		long now = 0L;
		for (int i = 0; i < 60; i++) {
			now += 16;
			double[] delta = model.stepFrame(now, 16.0, err, 0.0, 0.75, 0.0, 1.0);
			err -= Math.abs(delta[0]);
			if (err < 0.0) {
				break;
			}
		}
		check(err < 1.0, "8 deg is closed to <1 deg within 1s: " + err);
		System.out.printf(java.util.Locale.ROOT, "[aim-test] smoothness: 60fps step=%.3f deg, 20Hz step=%.3f deg%n",
			frameStep, tickStep);
	}

	/** 一定のズレに対して 1 回の更新で動く最大角 [deg] */
	private static double largestStep(double hz, double errorDeg) {
		TrackingAim model = new TrackingAim(new Random(11));
		double dt = 1000.0 / hz;
		long now = 0L;
		double largest = 0.0;
		for (int i = 0; i < 200; i++) {
			now += (long) Math.round(dt);
			double[] delta = model.stepFrame(now, dt, errorDeg, 0.0, 0.75, 0.0, 1.0);
			largest = Math.max(largest, Math.abs(delta[0]));
		}
		return largest;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException("[aim-test] FAILED: " + message);
		}
		passed++;
	}
}
