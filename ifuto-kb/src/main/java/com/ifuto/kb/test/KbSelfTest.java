package com.ifuto.kb.test;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.kb.measure.SpikeFit;
import com.ifuto.kb.model.BaselineTable;
import com.ifuto.kb.model.Estimator;
import com.ifuto.kb.model.Sample;
import com.ifuto.kb.model.ServerRecord;

/**
 * MC を触らない純粋ロジックの自己テスト。CI の check で毎回走る。
 * 落ちたらビルドごと落とす。
 */
public class KbSelfTest {
	private static int passed = 0;

	public static void main(String[] args) {
		testMedian();
		testSpikeFit();
		testBaselineTransfer();
		testServerSummary();
		testJsonRoundTrip();
		testRings();
		System.out.println("[kb-selftest] ALL PASS (" + passed + " checks)");
	}

	private static void check(boolean cond, String name) {
		if (!cond) {
			throw new AssertionError("[kb-selftest] FAIL: " + name);
		}
		passed++;
		System.out.println("[kb-selftest] ok: " + name);
	}

	private static void testMedian() {
		check(Estimator.median(List.of(1.0, 3.0, 2.0)) == 2.0, "median-odd");
		check(Estimator.median(List.of(1.0, 2.0, 3.0, 4.0)) == 2.5, "median-even");
		check(Estimator.median(List.of(1.0, -1.0, 3.0)) == 2.0, "median-skips-invalid");
		check(Estimator.median(List.of()) == -1, "median-empty");
		check(Estimator.iqr(List.of(1.0, 2.0, 3.0, 4.0)) == 2.0, "iqr-basic");
		check(Estimator.iqr(List.of(5.0)) == 0, "iqr-single");
		check(Estimator.iqr(List.of()) == -1, "iqr-empty");
	}

	private static void testSpikeFit() {
		ArrayList<Double> speeds = new ArrayList<>();
		for (int t = 0; t < 6; t++) {
			speeds.add(0.4 * Math.pow(0.91, t));
		}
		SpikeFit.Fit fit = SpikeFit.fit(speeds);
		check(!fit.fallback(), "fit-clean");
		check(Math.abs(fit.v0() - 0.4) < 0.008, "fit-v0");
		check(Math.abs(fit.decay() - 0.91) < 0.005, "fit-decay");
		SpikeFit.Fit single = SpikeFit.fit(List.of(0.5));
		check(single.fallback() && single.v0() == 0.5, "fit-fallback-single");
		SpikeFit.Fit growing = SpikeFit.fit(List.of(0.1, 0.2, 0.4, 0.8));
		check(growing.fallback(), "fit-fallback-growing");
		SpikeFit.Fit walking = SpikeFit.fit(List.of(0.23, 0.23, 0.23, 0.23, 0.23));
		check(walking.constantMotion(), "fit-constant-motion");
		check(!fit.constantMotion(), "fit-decay-not-constant");
		check(Estimator.median(List.of(-1.0, -1.0)) == -1, "median-all-invalid");
		check(Estimator.iqr(List.of(1.0, 2.0, 3.0, 4.0, 5.0)) == 3.0, "iqr-odd");
	}

	private static Sample mkSample(String dir, String atkKind, int kb, boolean sprint,
			String victimKind, boolean victimAir, double obsH, double obsV, boolean vValid) {
		Sample s = new Sample();
		s.dir = dir;
		s.attacker.kind = atkKind;
		s.attacker.kb = kb;
		s.attacker.sprint = sprint;
		s.attacker.weapon = "minecraft:iron_sword";
		s.victim.kind = victimKind;
		s.victim.airborne = victimAir;
		s.obsH = obsH;
		s.obsV = obsV;
		s.vValid = vValid;
		s.confidence = "high";
		return s;
	}

	private static void testBaselineTransfer() {
		BaselineTable table = new BaselineTable();
		for (int i = 0; i < 3; i++) {
			table.ingest(mkSample("received", "player", 0, false, "self", false, 0.42, 0.4, true));
		}
		check(table.calibratedCells() == 1, "baseline-cells");
		BaselineTable.Expected calibrated = table.expectedFor(
			mkSample("dealt", "player", 0, false, "player", false, 0.42, 0.4, true));
		check(!calibrated.seed() && Math.abs(calibrated.h() - 0.42) < 1e-9, "baseline-calibrated");
		// 未校正の k1 は P0 からの転写（0.8 * 0.42/0.4 = 0.84）
		BaselineTable.Expected k1 = table.expectedFor(
			mkSample("dealt", "player", 1, false, "player", false, 0.8, 0.4, true));
		check(k1.seed() && Math.abs(k1.h() - 0.84) < 0.01, "baseline-transfer");
		// 完全耐性MOBは使えない
		for (int i = 0; i < 3; i++) {
			table.ingest(mkSample("dealt", "player", 0, false, "minecraft:iron_golem", false, 0.01, 0, false));
		}
		BaselineTable.Expected immune = table.expectedFor(
			mkSample("dealt", "player", 0, false, "minecraft:iron_golem", false, 0.01, 0, false));
		check(immune.h() < 0, "baseline-immune");
	}

	private static void testServerSummary() {
		ServerRecord rec = new ServerRecord();
		for (int i = 0; i < 9; i++) {
			Sample s = mkSample("dealt", "player", 0, false, "player", false, 0.42, 0.4, true);
			s.ratioH = 1.02;
			s.ratioV = 0.98;
			rec.ingest(s, 1000 + i);
		}
		Sample weak = mkSample("dealt", "player", 0, false, "player", false, 0.2, 0.2, true);
		weak.attacker.weakSuspect = true;
		weak.ratioH = 0.5;
		rec.ingest(weak, 2000);
		ServerRecord.Summary sum = rec.summary();
		check(sum.dealt.n == 10, "summary-n");
		check(sum.dealt.weak == 1, "summary-weak");
		check(Math.abs(sum.dealt.kh - 1.02) < 1e-9, "summary-kh");
		check(Math.abs(sum.dealt.kv - 0.98) < 1e-9, "summary-kv");
		check(sum.dealt.byWeapon.get("minecraft:iron_sword").n == 9, "summary-weapon");
	}

	private static void testJsonRoundTrip() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		ServerRecord rec = new ServerRecord();
		rec.address = "mc.example.com";
		Sample s = mkSample("received", "player", 1, true, "self", false, 0.8, 0.4, true);
		s.ratioH = 1.0;
		rec.ingest(s, 1234);
		String json = rec.toJson(gson);
		check(json.contains("mc.example.com") && json.contains("empirical-vs-singleplayer-baseline"),
			"json-content");
		ServerRecord back = gson.fromJson(json, ServerRecord.class);
		check(back != null && back.samples.size() == 1 && "mc.example.com".equals(back.address),
			"json-roundtrip");
	}

	private static void testRings() {
		ServerRecord rec = new ServerRecord();
		for (int i = 0; i < 205; i++) {
			rec.ingest(mkSample("dealt", "player", 0, false, "player", false, 0.4, 0.4, true), i);
		}
		check(rec.samples.size() == 200 && rec.dropped == 5, "ring-samples");
		BaselineTable table = new BaselineTable();
		for (int i = 0; i < 50; i++) {
			table.ingest(mkSample("dealt", "player", 0, false, "minecraft:mob" + i, false, 0.4, 0.4, true));
		}
		check(table.cells.size() <= BaselineTable.MAX_CELLS && table.droppedCells > 0, "ring-cells");
	}
}
