package com.ifuto.fastplace.test;

import com.ifuto.fastplace.human.HumanAim;
import com.ifuto.fastplace.human.PlacementRhythm;

import java.util.Arrays;
import java.util.Random;

/**
 * HumanAim / PlacementRhythm と「ブリッジの距離トリガ」のヘッドレス自己テスト。
 * CI で毎回走らせる（Minecraft を起動しない）。
 */
public final class ScaffoldModelSelfTest {
	private static int passed = 0;

	public static void main(String[] args) {
		testMainSequence();
		testBallisticReachAndOvershoot();
		testMicroTremorAtRest();
		testPursuitSmallCorrection();
		testSmallSaccade();
		testAngleBounds();
		testRhythmDistribution();
		testRhythmFatigue();
		testReactionTimes();
		testBridgeSafety();
		System.out.println("[scaffold-test] all " + passed + " checks passed");
	}

	private static void testMainSequence() {
		double d70 = HumanAim.durationFor(70.0);
		double d5 = HumanAim.durationFor(5.0);
		double d0 = HumanAim.durationFor(0.0);
		check(d70 > 180.0 && d70 < 260.0, "70 deg saccade duration in range: " + d70);
		check(d5 < d70, "small movement is faster: " + d5 + " < " + d70);
		check(d0 <= HumanAim.MIN_MOVE_MS, "zero amplitude clipped to min duration: " + d0);
		double pv = HumanAim.peakVelocityDegPerMs(20.0) * 1000.0;
		check(pv > 500.0 && pv < 850.0, "20 deg peak velocity saturates near main sequence: " + pv);
		System.out.printf("[scaffold-test] main sequence: 70deg=%.0fms 5deg=%.0fms PV20=%.0fdeg/s%n",
			d70, d5, pv);
	}

	private static void testBallisticReachAndOvershoot() {
		HumanAim aim = new HumanAim(new Random(1));
		aim.reset(0.0);
		long t = 1_000_000L;
		double max = 0.0;
		long reachedAt = -1;
		for (int i = 0; i < 40; i++) {
			double out = aim.step(70.0, t, 50.0);
			max = Math.max(max, out);
			if (reachedAt < 0 && Math.abs(aim.angle() - 70.0) < 0.5) {
				reachedAt = i * 50L;
			}
			t += 50;
		}
		check(reachedAt > 0 && reachedAt <= HumanAim.durationFor(70.0) + 60.0,
			"ballistic move lands near predicted duration: " + reachedAt + "ms");
		check(max < 72.5, "no big overshoot: " + max);
		check(Math.abs(aim.angle() - 70.0) < 0.2, "settles exactly: " + aim.angle());
		System.out.printf("[scaffold-test] ballistic 0->70: reached=%dms max=%.2f final=%.2f%n",
			reachedAt, max, aim.angle());
	}

	private static void testMicroTremorAtRest() {
		HumanAim aim = new HumanAim(new Random(2));
		aim.reset(0.0);
		long t = 2_000_000L;
		double sum = 0.0;
		double sumSq = 0.0;
		int n = 400;
		double max = 0.0;
		for (int i = 0; i < n; i++) {
			double out = aim.step(0.0, t, 50.0);
			sum += out;
			sumSq += out * out;
			max = Math.max(max, Math.abs(out));
			t += 50;
		}
		double mean = sum / n;
		double variance = sumSq / n - mean * mean;
		check(variance > 1.0e-6, "tremor has variance: " + variance);
		check(max < 0.8, "tremor stays subtle (<0.8deg): " + max);
		System.out.printf("[scaffold-test] tremor sigma=%.3fdeg max=%.3fdeg%n", Math.sqrt(variance), max);
	}

	private static void testPursuitSmallCorrection() {
		// 3 度未満はサッケードではなく追従（臨界減衰, tau = 70ms）
		HumanAim aim = new HumanAim(new Random(3));
		aim.reset(0.0);
		long t = 3_000_000L;
		long reachedAt = -1;
		for (int i = 0; i < 20; i++) {
			aim.step(2.0, t, 50.0);
			if (reachedAt < 0 && Math.abs(aim.angle() - 2.0) < 0.1) {
				reachedAt = i * 50L;
			}
			t += 50;
		}
		check(reachedAt >= 0 && reachedAt < 450, "small correction converges quickly: " + reachedAt);
		System.out.println("[scaffold-test] small correction reached in " + reachedAt + "ms");
	}

	/** 6 度はサッケード（メインシーケンス）で 1-2 tick で着く */
	private static void testSmallSaccade() {
		HumanAim aim = new HumanAim(new Random(9));
		aim.reset(0.0);
		long t = 9_000_000L;
		long reachedAt = -1;
		for (int i = 0; i < 10; i++) {
			aim.step(6.0, t, 50.0);
			if (reachedAt < 0 && Math.abs(aim.angle() - 6.0) < 0.2) {
				reachedAt = i * 50L;
			}
			t += 50;
		}
		check(reachedAt >= 0 && reachedAt <= 120, "small saccade is fast: " + reachedAt);
		System.out.println("[scaffold-test] 6deg saccade reached in " + reachedAt + "ms");
	}

	private static void testAngleBounds() {
		HumanAim aim = new HumanAim(new Random(4));
		aim.reset(0.0);
		long t = 4_000_000L;
		for (int i = 0; i < 200; i++) {
			double out = aim.step(300.0, t, 50.0);
			check(out >= -90.0 && out <= 90.0, "pitch stays in [-90,90]: " + out);
			t += 50;
		}
		aim.reset(0.0);
		for (int i = 0; i < 200; i++) {
			double out = aim.step(-300.0, t, 50.0);
			check(out >= -90.0 && out <= 90.0, "pitch stays in [-90,90] (negative): " + out);
			t += 50;
		}
		System.out.println("[scaffold-test] angle bounds ok");
	}

	private static void testRhythmDistribution() {
		PlacementRhythm rhythm = new PlacementRhythm();
		Random random = new Random(5);
		long t = 5_000_000L;
		int n = 8000;
		long[] samples = new long[n];
		long sum = 0;
		for (int i = 0; i < n; i++) {
			rhythm.tick(t);
			samples[i] = rhythm.nextGapMs(random, t, i % 2 == 0);
			sum += samples[i];
			check(samples[i] >= PlacementRhythm.MIN_GAP_MS && samples[i] <= PlacementRhythm.MAX_GAP_MS,
				"gap within bounds: " + samples[i]);
			t += 120;
		}
		double mean = sum / (double) n;
		long[] sorted = samples.clone();
		Arrays.sort(sorted);
		double median = sorted[n / 2];
		check(mean > 80.0 && mean < 200.0, "mean gap plausible: " + mean);
		check(median < mean, "right-skewed gaps (median < mean): median=" + median + " mean=" + mean);
		// 注意の途切れ（たまの長い間）が尾として出ていること
		int slow = 0;
		for (long sample : samples) {
			if (sample > median * 1.8) {
				slow++;
			}
		}
		check(slow >= 20 && slow <= 300, "lapses form a tail: " + slow + " long gaps");
		System.out.printf("[scaffold-test] gaps mean=%.1fms median=%dms lapses=%d%n",
			mean, (long) median, slow);
	}

	private static void testRhythmFatigue() {
		PlacementRhythm rhythm = new PlacementRhythm();
		Random random = new Random(6);
		long t = 6_000_000L;
		int n = 120;
		double head = 0;
		for (int i = 0; i < n; i++) {
			rhythm.tick(t);
			head += rhythm.nextGapMs(random, t, false);
			t += 110;
		}
		double tail = 0;
		for (int i = 0; i < n; i++) {
			rhythm.tick(t);
			tail += rhythm.nextGapMs(random, t, false);
			t += 110;
		}
		check(rhythm.fatigue() > 0.08, "fatigue builds up: " + rhythm.fatigue());
		check(tail > head, "fatigue slows the tail: head=" + (head / n) + " tail=" + (tail / n));
		// 十分に休むと回復する
		for (long idle = t; idle < t + 60_000; idle += 100) {
			rhythm.tick(idle);
		}
		check(rhythm.fatigue() < 0.01, "fatigue recovers while idle: " + rhythm.fatigue());
		System.out.printf("[scaffold-test] fatigue head=%.1fms tail=%.1fms recovered=%.4f%n",
			head / n, tail / n, rhythm.fatigue());
	}

	private static void testReactionTimes() {
		PlacementRhythm rhythm = new PlacementRhythm();
		Random random = new Random(7);
		double sum = 0;
		int n = 4000;
		for (int i = 0; i < n; i++) {
			long value = rhythm.reactionMs(random);
			check(value >= 8 && value <= 190, "reaction within bounds: " + value);
			sum += value;
		}
		double mean = sum / n;
		check(mean > 30.0 && mean < 80.0, "mean reaction plausible: " + mean);
		System.out.printf("[scaffold-test] reaction mean=%.1fms%n", mean);
	}

	/**
	 * 実際の幾何（1 ブロック = 1m、スプリント 5.6 m/s = 0.28 m/tick）で、
	 * 距離トリガ + 1 tick 遅れのクリックが 100 ブロック落ちずに進めるか。
	 */
	private static void testBridgeSafety() {
		double speed = 0.28;          // blocks / tick
		double halfWidth = 0.3;       // プレイヤーの当たり判定の半分
		double triggerMin = 1.15;
		double triggerMax = 1.70;
		Random random = new Random(8);

		boolean[] solid = new boolean[400];
		for (int i = 0; i <= 20; i++) {
			solid[i] = true;
		}
		double z = 20.5;
		double trigger = triggerMin + random.nextDouble() * (triggerMax - triggerMin);
		int placed = 0;
		int ticks = 0;
		boolean fell = false;
		boolean pending = false;
		while (placed < 100 && ticks < 4000 && !fell) {
			z += speed;
			// サポートの終端 = 連続して埋まっている最後の列の far edge
			int column = (int) Math.floor(z);
			int end = column;
			while (end + 1 < solid.length && solid[end + 1]) {
				end++;
			}
			double supportEnd = end + 1.0;
			if (z - halfWidth > supportEnd + 1.0e-9) {
				fell = true;
				break;
			}
			int firstAir = end + 1;
			double centre = firstAir + 0.5;
			double distance = centre - z;
			if (!pending && distance <= trigger) {
				// 1 tick 遅れ（反応時間）を模して、次 tick で置く
				pending = true;
				continue;
			}
			if (pending) {
				solid[firstAir] = true;
				placed++;
				trigger = triggerMin + random.nextDouble() * (triggerMax - triggerMin);
				pending = false;
			}
			ticks++;
		}
		check(!fell, "never falls while bridging");
		check(placed >= 95 && placed <= 110, "places about one block per block: " + placed);
		System.out.println("[scaffold-test] bridge sim: placed=" + placed + " blocks, fell=" + fell);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException("[scaffold-test] FAILED: " + message);
		}
		passed++;
	}
}
