package com.ifuto.fastplace.test;

import com.ifuto.fastplace.ClickTimingModel;

import java.util.Arrays;

/**
 * ClickTimingModel のヘッドレス自己テスト。CI で毎回走らせる。
 */
public final class ClickModelSelfTest {
	private static int passed = 0;

	public static void main(String[] args) {
		testBasicBoundsAndFatigue();
		testRightSkew();
		testFatigueCapsAndNeverPins();
		testFatigueRecovery();
		testDynamicParamsStayInRange();
		testDisplayCpsNeverIntegerAndFluctuates();
		testBaseIntervalScaling();
		testResetStats();

		System.out.println("[fastplace-test] all " + passed + " checks passed");
	}

	private static void testBasicBoundsAndFatigue() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 10_000_000L;
		double base = 55.0;
		int n = 30000;
		double[] samples = new double[n];
		for (int i = 0; i < n; i++) {
			model.tick(now);
			samples[i] = model.fireClick(now, base);
			now += 50;
		}
		for (double s : samples) {
			check(!Double.isNaN(s) && !Double.isInfinite(s), "sample is finite");
			check(s >= ClickTimingModel.MIN_INTERVAL_MS - 0.5 && s <= ClickTimingModel.MAX_INTERVAL_MS + 0.5,
				"sample within bounds: " + s);
		}
		check(model.fatigue() >= 0.0 && model.fatigue() <= ClickTimingModel.FATIGUE_MAX + 1.0e-9,
			"fatigue within cap: " + model.fatigue());

		double head = mean(samples, 0, 80);
		double tail = mean(samples, n - 500, n);
		check(head < 66.0, "early clicks near base interval: " + head);
		check(tail > 65.0, "fatigue pushes later intervals up: " + tail);
		check(tail > head, "fatigue monotonically slows clicks: head=" + head + " tail=" + tail);
		System.out.printf("[fastplace-test] mean head=%.2f tail=%.2f fatigue=%.4f%n", head, tail, model.fatigue());
	}

	private static void testRightSkew() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 20_000_000L;
		double base = 55.0;
		int n = 12000;
		double[] samples = new double[n];
		for (int i = 0; i < n; i++) {
			model.tick(now);
			samples[i] = model.fireClick(now, base);
			now += 50;
		}
		double[] sorted = samples.clone();
		Arrays.sort(sorted);
		double median = sorted[n / 2];
		double mean = mean(samples, 0, n);
		check(median < mean, "right-skewed (median < mean): median=" + median + " mean=" + mean);
		System.out.printf("[fastplace-test] median=%.2f mean=%.2f (skew ok)%n", median, mean);
	}

	private static void testFatigueCapsAndNeverPins() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 30_000_000L;
		double base = 55.0;
		for (int i = 0; i < 600; i++) {
			model.tick(now);
			model.fireClick(now, base);
			// 回復が起きないように詰めて撃ち続ける
			now += 30;
		}
		check(model.fatigue() < ClickTimingModel.FATIGUE_MAX,
			"fatigue never reaches the cap exactly: " + model.fatigue());
		check(model.fatigue() > ClickTimingModel.FATIGUE_MAX * 0.9,
			"fatigue asymptotically approaches the cap: " + model.fatigue());
		// Jitter 込みの素の間隔は上限を突破できる（ログ正規 Jitter があるので max は元値上限を超える）
		double meanOfBoosted = 0.0;
		int m = 3000;
		for (int i = 0; i < m; i++) {
			model.tick(now);
			meanOfBoosted += model.fireClick(now, base);
			now += 30;
		}
		meanOfBoosted /= m;
		check(meanOfBoosted > base * (1.0 + ClickTimingModel.FATIGUE_MAX) * 0.9,
			"boosted mean plausible: " + meanOfBoosted);
		System.out.printf("[fastplace-test] fatigue=%.4f (cap %.2f)%n", model.fatigue(), ClickTimingModel.FATIGUE_MAX);
	}

	private static void testFatigueRecovery() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 40_000_000L;
		for (int i = 0; i < 400; i++) {
			model.tick(now);
			model.fireClick(now, 55.0);
			now += 40;
		}
		double before = model.fatigue();
		check(before > 0.05, "fatigue accumulated: " + before);
		// 60 秒放置（連打しない）→ ほぼ完全回復
		long end = now + 60_000;
		for (long t = now; t < end; t += 100) {
			model.tick(t);
		}
		check(model.fatigue() < 0.01, "fatigue recovered while idle: " + model.fatigue());
		System.out.printf("[fastplace-test] fatigue %.4f -> %.6f after 60s idle%n", before, model.fatigue());
	}

	private static void testDynamicParamsStayInRange() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 50_000_000L;
		double sigmaFirst = model.jitterSigma();
		double outlierFirst = model.outlierProb();
		for (long t = now; t < now + 30_000; t += 100) {
			model.tick(t);
		}
		check(model.jitterSigma() >= ClickTimingModel.SIGMA_MIN && model.jitterSigma() <= ClickTimingModel.SIGMA_MAX,
			"jitterSigma in range: " + model.jitterSigma());
		check(model.outlierProb() >= ClickTimingModel.OUTLIER_MIN && model.outlierProb() <= ClickTimingModel.OUTLIER_MAX,
			"outlierProb in range: " + model.outlierProb());
		check(model.skewShape() >= 0.7 && model.skewShape() <= 1.5,
			"sigma ratio walk in range: " + model.skewShape());
		// 30 秒で最低一度は動くはず（ランダムウォークなので厳密には変わるはず）
		check(Math.abs(model.jitterSigma() - sigmaFirst) > 1.0e-9
					|| Math.abs(model.outlierProb() - outlierFirst) > 1.0e-9,
			"params drift over 2s windows");
		System.out.printf("[fastplace-test] sigma %.4f->%.4f outlier %.4f->%.4f%n",
			sigmaFirst, model.jitterSigma(), outlierFirst, model.outlierProb());
	}

	private static void testDisplayCpsNeverIntegerAndFluctuates() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 60_000_000L;
		for (int i = 0; i < 200; i++) {
			model.tick(now);
			model.fireClick(now, 55.0);
			now += 50;
		}
		boolean varied = false;
		double first = model.displayCps(now);
		for (int i = 0; i < 3000; i++) {
			double v = model.displayCps(now + i);
			check(!Double.isNaN(v) && v > 0.0, "displayCps positive: " + v);
			double rounded = Math.round(v * 10.0) / 10.0;
			check(Math.abs(rounded - Math.rint(rounded)) > 0.001,
				"displayCps never an integer: " + v);
			if (Math.abs(v - first) > 1.0e-9) {
				varied = true;
			}
		}
		check(varied, "displayCps fluctuates between ticks");
		System.out.println("[fastplace-test] displayCps stays fractional and fluctuates");
	}

	private static void testBaseIntervalScaling() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 70_000_000L;
		double base = 120.0;
		int n = 2000;
		double sum = 0.0;
		for (int i = 0; i < n; i++) {
			model.tick(now);
			sum += model.fireClick(now, base);
			now += 60;
		}
		double mean = sum / n;
		check(mean > 110.0 && mean < 175.0, "larger base interval scales: " + mean);
		System.out.printf("[fastplace-test] base=120ms mean=%.2f%n", mean);
	}

	private static void testResetStats() {
		ClickTimingModel model = new ClickTimingModel();
		long now = 80_000_000L;
		for (int i = 0; i < 100; i++) {
			model.tick(now);
			model.fireClick(now, 55.0);
			now += 50;
		}
		model.resetStats();
		check(model.fatigue() == 0.0, "reset clears fatigue");
		check(model.clickCount() == 0, "reset clears counter");
		check(model.isCryptoTurn() == false, "fresh model is not on crypto turn yet");
		System.out.println("[fastplace-test] resetStats ok");
	}

	private static double mean(double[] samples, int from, int to) {
		double sum = 0.0;
		for (int i = from; i < to; i++) {
			sum += samples[i];
		}
		return sum / (to - from);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			System.out.println("[fastplace-test] FAIL: " + message);
			System.exit(1);
		}
		passed++;
	}
}
