package com.ifuto.fastplace.test;

import com.ifuto.fastplace.human.ToolSelectModel;

import java.util.Locale;
import java.util.Random;

/**
 * Auto Tool の「選ぶまでの時間」モデル（{@link ToolSelectModel}）のヘッドレス自己テスト。
 *
 * <p>Hick–Hyman / 練習のべき乗則 / drift-diffusion / 反復プライミング / 誤り後減速が
 * 正しい向きに効いていること、そして人間の範囲（110–560ms）に収まっていることを固定する。</p>
 */
public final class AutoToolSelfTest {
	private static int passed = 0;
	private static final int SAMPLES = 600;

	public static void main(String[] args) {
		testHickHyman();
		testPractice();
		testEvidenceStrength();
		testRepetitionPriming();
		testPostErrorSlowing();
		testBounds();
		testMispickRate();
		testPlausibleMean();
		System.out.println("[autotool-test] all " + passed + " checks passed");
	}

	/** 候補が多いほど遅い（Hick–Hyman） */
	private static void testHickHyman() {
		double one = mean(1, 20, false, 1.0);
		double three = mean(3, 20, false, 1.0);
		double nine = mean(9, 20, false, 1.0);
		check(one < three && three < nine,
			"more candidates -> slower: " + one + " / " + three + " / " + nine);
		check(nine - one > 60.0, "candidate effect is material: +" + (nine - one) + "ms");
		System.out.printf(Locale.ROOT, "[autotool-test] hick: 1=%.0fms 3=%.0fms 9=%.0fms%n", one, three, nine);
	}

	/** 同じブロックを掘り慣れると速い（練習のべき乗則） */
	private static void testPractice() {
		double first = mean(9, 0, false, 1.0);
		double familiar = mean(9, 40, false, 1.0);
		check(familiar < first, "practice speeds the decision up: " + first + " -> " + familiar);
		check(first - familiar > 25.0, "practice effect is material: " + (first - familiar) + "ms");
		System.out.printf(Locale.ROOT, "[autotool-test] practice: novice=%.0fms expert=%.0fms%n", first, familiar);
	}

	/** 差が明白なほど決断が速い（drift-diffusion の drift） */
	private static void testEvidenceStrength() {
		double weak = mean(9, 20, false, 0.20);
		double strong = mean(9, 20, false, 6.00);
		check(strong < weak, "bigger gain -> faster: " + weak + " vs " + strong);
		System.out.printf(Locale.ROOT, "[autotool-test] evidence: gain0.2=%.0fms gain6=%.0fms%n", weak, strong);
	}

	/** 同じスロットが続くと速い（反復プライミング） */
	private static void testRepetitionPriming() {
		double repeat = mean(9, 20, true, 1.0);
		double different = mean(9, 20, false, 1.0);
		check(repeat < different, "repeat is faster: " + repeat + " vs " + different);
		System.out.printf(Locale.ROOT, "[autotool-test] priming: repeat=%.0fms new=%.0fms%n", repeat, different);
	}

	/** 誤った直後は遅い（Rabbitt 1966） */
	private static void testPostErrorSlowing() {
		ToolSelectModel model = new ToolSelectModel(new Random(5));
		double clean = 0;
		for (int i = 0; i < SAMPLES; i++) {
			clean += model.decide(9, 20, false, 1.0);
			model.onSwitch(3, false);
		}
		clean /= SAMPLES;

		double afterError = 0;
		for (int i = 0; i < SAMPLES; i++) {
			model.onSwitch(3, true);
			afterError += model.decide(9, 20, false, 1.0);
		}
		afterError /= SAMPLES;
		check(afterError > clean + 60.0,
			"post-error slowing: clean=" + clean + " afterError=" + afterError);
		System.out.printf(Locale.ROOT, "[autotool-test] post-error: clean=%.0fms after=%.0fms%n",
			clean, afterError);
	}

	/** どんな条件でも人間の範囲に収まる */
	private static void testBounds() {
		Random random = new Random(7);
		ToolSelectModel model = new ToolSelectModel(random);
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (int i = 0; i < 5000; i++) {
			long rt = model.decide(1 + random.nextInt(9), random.nextInt(80),
				random.nextBoolean(), random.nextDouble() * 8.0);
			model.onSwitch(random.nextInt(9), random.nextBoolean());
			min = Math.min(min, rt);
			max = Math.max(max, rt);
		}
		check(min >= (long) ToolSelectModel.MIN_RT_MS - 1, "min RT respects floor: " + min);
		check(max <= (long) ToolSelectModel.MAX_RT_MS + 1, "max RT respects ceiling: " + max);
		System.out.println("[autotool-test] bounds: " + min + ".." + max + "ms");
	}

	/** 差が小さいほど取り違える。ただし頻発はしない */
	private static void testMispickRate() {
		ToolSelectModel hard = new ToolSelectModel(new Random(11));
		int hardCount = 0;
		int tries = 20000;
		for (int i = 0; i < tries; i++) {
			if (hard.mispick(0.2)) {
				hardCount++;
			}
		}
		ToolSelectModel easy = new ToolSelectModel(new Random(12));
		int easyCount = 0;
		for (int i = 0; i < tries; i++) {
			if (easy.mispick(6.0)) {
				easyCount++;
			}
		}
		double hardRate = hardCount / (double) tries;
		double easyRate = easyCount / (double) tries;
		check(hardRate > easyRate, "ambiguous tools are mispicked more: " + hardRate + " vs " + easyRate);
		check(hardRate > 0.01 && hardRate < 0.09, "ambiguous mispick rate is human: " + hardRate);
		check(easyRate >= 0.0 && easyRate < 0.02, "obvious choice is rarely fumbled: " + easyRate);
		System.out.printf(Locale.ROOT, "[autotool-test] mispick: ambiguous=%.3f obvious=%.3f%n",
			hardRate, easyRate);
	}

	/** 実戦の典型（9 候補・掘り慣れたブロック）で 200–450ms に収まる */
	private static void testPlausibleMean() {
		double typical = mean(9, 25, false, 3.0);
		check(typical > 180.0 && typical < 450.0, "typical decision time is human: " + typical + "ms");
		System.out.printf(Locale.ROOT, "[autotool-test] typical (9 candidates, familiar): %.0fms%n", typical);
	}

	// ------------------------------------------------------------------

	private static double mean(int candidates, int priorEncounters, boolean sameSlot, double gain) {
		ToolSelectModel model = new ToolSelectModel(new Random(candidates * 31L + priorEncounters * 7L));
		long sum = 0;
		for (int i = 0; i < SAMPLES; i++) {
			sum += model.decide(candidates, priorEncounters, sameSlot, gain);
		}
		return sum / (double) SAMPLES;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			System.out.println("[autotool-test] FAILED: " + message);
			System.exit(1);
		}
		passed++;
	}
}
