package com.ifuto.fastplace.human;

import java.util.Random;

/**
 * 「どのツールに持ち替えるか」を決めるまでの人間の時間モデル。
 *
 * <p>持ち替えは 1 tick で終わる作業だが、人間はその前に<b>見て・選んで・手を動かす</b>。
 * その「選ぶ」時間を、選択反応時間の古典で組み立てる。</p>
 *
 * <ul>
 *   <li><b>選択肢の数</b>: Hick–Hyman の法則 {@code RT = a + b·log2(n+1)}
 *       （Hick 1952, Hyman 1953）。候補が多いほど遅い。</li>
 *   <li><b>練習のべき乗則</b>: 同じブロックを何度も掘ると、そのブロックに合うツールの
 *       知識が洗練されて速くなる {@code RT ≈ B·N^(−α)}（Newell &amp; Rosenbloom 1981,
 *       α ≈ 0.2–0.4）。初見のブロックは遅い。</li>
 *   <li><b>証拠の強さ（drift-diffusion）</b>: 明らかに速いツールがあるほど決断が速い。
 *       迷うのは「差が小さいとき」（Ratcliff 1978, Ratcliff &amp; McKoon 2008）。
 *       同じ理由で、差が小さいほど<b>選び間違える</b>確率も上がる。</li>
 *   <li><b>反復プライミング</b>: 直前に選んだのと同じスロットなら速い
 *       （Rogers &amp; Monsell 1995 の反復コスト）。</li>
 *   <li><b>誤り後の減速</b>: 1 回間違えると次の判断が遅くなる（Rabbitt 1966）。</li>
 * </ul>
 *
 * <p>このクラスは Minecraft に依存しない（テストできる）。</p>
 */
public final class ToolSelectModel {
	/** Hick–Hyman: RT = A + B·log2(n+1) [ms] */
	public static final double HICK_A_MS = 165.0;
	public static final double HICK_B_MS = 62.0;
	/** 練習のべき乗則: 追加の遅れ = B·(n+1)^(−α) [ms] */
	public static final double PRACTICE_MS = 150.0;
	public static final double PRACTICE_EXP = 0.35;
	/** 同じスロットを続けて選ぶときの短縮率 */
	public static final double REPEAT_FACTOR = 0.88;
	/** 誤り後の減速 [ms]（+ 0〜60ms のばらつき） */
	public static final double POST_ERROR_MS = 110.0;
	/** 判断時間のばらつき（ガウス）[ms] */
	public static final double NOISE_MS = 22.0;
	/** これ以下/以上にはしない [ms] */
	public static final double MIN_RT_MS = 110.0;
	public static final double MAX_RT_MS = 560.0;
	/** 選び間違いの確率（証拠が弱いとき）と、証拠が強いときの下限 */
	public static final double MISPICK_BASE = 0.060;
	public static final double MISPICK_GAIN_SLOPE = 0.030;
	public static final double MISPICK_MIN = 0.004;

	private final Random random;
	private boolean afterError;
	private int lastSlot = -1;

	public ToolSelectModel() {
		this(new Random());
	}

	public ToolSelectModel(Random random) {
		this.random = random;
	}

	/**
	 * 判断にかかる時間を返す。
	 *
	 * @param candidates     持ち替え先の候補数（現在より速いスロットの数）
	 * @param priorEncounters このブロックを今まで掘った回数（練習量）
	 * @param sameSlot       直前に選んだのと同じスロットか
	 * @param gainRatio      最良ツールの速さ ÷ いまの速さ − 1（証拠の強さ）
	 */
	public long decide(int candidates, int priorEncounters, boolean sameSlot, double gainRatio) {
		double rt = HICK_A_MS + HICK_B_MS * log2(Math.max(1, candidates) + 1.0);
		rt += PRACTICE_MS * Math.pow(Math.max(0, priorEncounters) + 1.0, -PRACTICE_EXP);
		rt *= clamp(1.10 - 0.22 * log2(1.0 + Math.max(0.0, gainRatio)), 0.72, 1.10);
		if (sameSlot) {
			rt *= REPEAT_FACTOR;
		}
		if (afterError) {
			rt += POST_ERROR_MS + random.nextDouble() * 60.0;
		}
		rt += gaussian() * NOISE_MS;
		return Math.round(clamp(rt, MIN_RT_MS, MAX_RT_MS));
	}

	/** 選び間違えるか（差が小さいほど間違えやすい） */
	public boolean mispick(double gainRatio) {
		double p = clamp(MISPICK_BASE - MISPICK_GAIN_SLOPE * log2(1.0 + Math.max(0.0, gainRatio)),
			MISPICK_MIN, MISPICK_BASE);
		return random.nextDouble() < p;
	}

	/** 実際に持ち替えたことを記録する（次回の判断に効く） */
	public void onSwitch(int slot, boolean wasMispick) {
		lastSlot = slot;
		afterError = wasMispick;
	}

	public int lastSlot() {
		return lastSlot;
	}

	public boolean afterError() {
		return afterError;
	}

	public void reset() {
		afterError = false;
		lastSlot = -1;
	}

	public static double log2(double value) {
		return Math.log(value) / Math.log(2.0);
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
