package com.ifuto.kb.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;

/** 1鯖分の記録。ファイル形式 = クリップボード形式 = この toJson。 */
public class ServerRecord {
	public String address = "?";
	public long firstSeen;
	public long lastSeen;
	public String modVersion = "?";
	public ArrayList<Sample> samples = new ArrayList<>();
	public long dropped = 0;
	public Map<String, Long> excluded = new LinkedHashMap<>();

	public static final int MAX_SAMPLES = 200;

	public static class WStat {
		public int n;
		public double kh = -1;
	}

	public static class Dir {
		public int n;
		public double kh = -1;
		public double khIqr = -1;
		public double kv = -1;
		public double kvIqr = -1;
		public int nV;
		public double highFrac = -1;
		public double seedFrac = -1;
		public int weak;
		public Map<String, WStat> byWeapon = new LinkedHashMap<>();
	}

	public static class Summary {
		public Dir dealt = new Dir();
		public Dir received = new Dir();
	}

	public void ingest(Sample s, long nowMs) {
		if (firstSeen == 0) {
			firstSeen = nowMs;
		}
		lastSeen = nowMs;
		samples.add(s);
		while (samples.size() > MAX_SAMPLES) {
			samples.remove(0);
			dropped++;
		}
	}

	public Summary summary() {
		Summary out = new Summary();
		fillDir(out.dealt, "dealt");
		fillDir(out.received, "received");
		return out;
	}

	private void fillDir(Dir dir, String which) {
		ArrayList<Double> rhs = new ArrayList<>();
		ArrayList<Double> rvs = new ArrayList<>();
		Map<String, ArrayList<Double>> byW = new LinkedHashMap<>();
		int high = 0;
		int seed = 0;
		for (Sample s : samples) {
			if (!which.equals(s.dir)) {
				continue;
			}
			dir.n++;
			if (s.attacker.weakSuspect) {
				dir.weak++;
				continue;
			}
			if (s.ratioH >= 0) {
				rhs.add(s.ratioH);
				byW.computeIfAbsent(s.attacker.weapon, k -> new ArrayList<>()).add(s.ratioH);
			}
			if (s.vValid && s.ratioV >= 0) {
				rvs.add(s.ratioV);
			}
			if ("high".equals(s.confidence)) {
				high++;
			}
			if (s.seedUsed) {
				seed++;
			}
		}
		dir.kh = Estimator.median(rhs);
		dir.khIqr = Estimator.iqr(rhs);
		dir.nV = rvs.size();
		dir.kv = Estimator.median(rvs);
		dir.kvIqr = Estimator.iqr(rvs);
		int primary = dir.n - dir.weak;
		if (primary > 0) {
			dir.highFrac = (double) high / primary;
			dir.seedFrac = (double) seed / primary;
		}
		for (Map.Entry<String, ArrayList<Double>> e : byW.entrySet()) {
			WStat w = new WStat();
			w.n = e.getValue().size();
			w.kh = Estimator.median(e.getValue());
			dir.byWeapon.put(e.getKey(), w);
		}
	}

	public String toJson(Gson gson) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("address", address);
		root.put("mod", "ifuto-kb");
		root.put("modVersion", modVersion);
		root.put("firstSeen", firstSeen);
		root.put("lastSeen", lastSeen);
		root.put("method", "empirical-vs-singleplayer-baseline");
		root.put("summary", summary());
		root.put("excluded", excluded);
		root.put("dropped", dropped);
		root.put("samples", samples);
		return gson.toJson(root);
	}
}
