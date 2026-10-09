package com.ifuto.kb.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * シングルプレイ実測の基準表。バニラ = 1.0 の分母。
 * セル = （攻撃者・KBレベル・被害者の設置・被害者種）。未校正セルは推定値 + 転写で補う。
 */
public class BaselineTable {
	public static class Cell {
		public double h = 0.4;
		public double v = 0.4;
		public int n = 0;
		public boolean usable = true;
		public ArrayList<Double> hs = new ArrayList<>();
		public ArrayList<Double> vs = new ArrayList<>();
	}

	public Map<String, Cell> cells = new LinkedHashMap<>();
	public Map<String, Long> excluded = new LinkedHashMap<>();
	public int droppedCells = 0;

	public static final int MAX_CELLS = 48;
	private static final int RING = 32;

	public record Expected(double h, double v, boolean seed) {
	}

	public static String keyFor(Sample s) {
		String g = s.victim.airborne ? "g0" : "g1";
		// プレイヤー被害者は耐性で割り戻せるので同一セル。MOBは innate 耐性が種で違うので分ける。
		String v = ("self".equals(s.victim.kind) || "player".equals(s.victim.kind)) ? "vP" : "v" + s.victim.kind;
		String a;
		if ("player".equals(s.attacker.kind)) {
			int k = Math.max(0, s.attacker.kb + (s.attacker.sprint ? 1 : 0));
			a = "P:k" + Math.min(k, 6);
		} else {
			a = "M:" + s.attacker.kind;
		}
		return a + ":" + g + ":" + v;
	}

	public Expected expectedFor(Sample s) {
		String key = keyFor(s);
		Cell cell = cells.get(key);
		if (cell != null && !cell.usable) {
			return new Expected(-1, -1, true); // 完全耐性MOBなど：推定に使えない
		}
		if (cell != null && cell.n >= 3) {
			return new Expected(cell.h, cell.v, false);
		}
		int k = 0;
		if ("player".equals(s.attacker.kind)) {
			k = Math.max(0, s.attacker.kb + (s.attacker.sprint ? 1 : 0));
		}
		double h = 0.4 * (k + 1);
		double v = s.victim.airborne ? -1 : Math.min(0.4, h);
		// P0（素手・非スプリント・設置）が校正済みなら形状ごと転写する
		Cell p0 = cells.get("P:k0:g1:vP");
		if (p0 != null && p0.n >= 3 && p0.usable && p0.h > 0.05) {
			double f = p0.h / 0.4;
			h *= f;
			if (v >= 0) {
				v = Math.min(0.45, v * f);
			}
		}
		return new Expected(h, v, true);
	}

	public void ingest(Sample s) {
		String key = keyFor(s);
		Cell cell = cells.get(key);
		if (cell == null) {
			if (cells.size() >= MAX_CELLS) {
				droppedCells++;
				return;
			}
			cell = new Cell();
			cells.put(key, cell);
		}
		if (s.obsH >= 0) {
			cell.hs.add(s.obsH);
			while (cell.hs.size() > RING) {
				cell.hs.remove(0);
			}
		}
		if (s.vValid && s.obsV >= 0) {
			cell.vs.add(s.obsV);
			while (cell.vs.size() > RING) {
				cell.vs.remove(0);
			}
		}
		cell.n++;
		double mh = Estimator.median(cell.hs);
		if (mh >= 0) {
			cell.h = mh;
		}
		double mv = Estimator.median(cell.vs);
		if (mv >= 0) {
			cell.v = mv;
		}
		if (cell.n >= 3 && cell.h < 0.05) {
			cell.usable = false;
		}
	}

	public int calibratedCells() {
		int n = 0;
		for (Cell c : cells.values()) {
			if (c.n >= 3 && c.usable) {
				n++;
			}
		}
		return n;
	}

	public int totalSamples() {
		int n = 0;
		for (Cell c : cells.values()) {
			n += c.n;
		}
		return n;
	}
}
