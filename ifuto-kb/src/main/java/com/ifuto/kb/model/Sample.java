package com.ifuto.kb.model;

/**
 * 1ヒット分の計測サンプル。そのまま JSON になる。
 * 無効値はすべて -1（NaN は JSON を壊すので使わない）。
 */
public class Sample {
	public String dir = "?"; // dealt（殴り）/ received（被弾）
	public long tick;
	public int pingSelf = -1;
	public int pingOther = -1;
	public Attacker attacker = new Attacker();
	public Victim victim = new Victim();
	public double damage = -1; // received のみ確定値、dealt は -1
	public boolean damageConfirmed;
	public double obsH = -1; // 実測の初速（横・縦、ブロック/tick）
	public double obsV = -1;
	public double baseH = -1; // 基準表の値（耐性割り戻し前）
	public double baseV = -1;
	public double expH = -1; // 期待値（耐性込み）
	public double expV = -1;
	public double ratioH = -1; // 係数 = 実測 / 期待
	public double ratioV = -1;
	public boolean vValid; // 縦が推定に使えるか（設置 + 飽和なし）
	public boolean seedUsed; // 基準が推定値（未校正セル）
	public String confidence = "med"; // high / med / low
	public String note = "";

	public static class Attacker {
		public String kind = "?"; // player / MOBのID
		public String weapon = "?"; // アイテムID
		public int kb; // ノックバックエンチャント
		public boolean sprint;
		public boolean airborne;
		public boolean falling; // クリティカル相当の落下中
		public long swingIntervalMs = -1; // dealt のみ：前スイングからの間隔
		public boolean weakSuspect; // 弱ヒット疑い（主推定から除外）
	}

	public static class Victim {
		public String kind = "?"; // self / player / MOBのID
		public double resist; // KB耐性（ネザライト x 0.1 で統一）
		public int netherite;
		public boolean airborne;
	}
}
