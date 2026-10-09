package com.ifuto.kb.measure;

import java.util.List;

/**
 * 殴った相手の速度スパイクから初速 v0 を復元する。
 * モデル: v_t = v0 * d^t（指数減衰）。対数で直線回帰する。MC 非依存・単体テスト対象。
 */
public final class SpikeFit {
	private SpikeFit() {
	}

	public record Fit(double v0, double decay, int n, boolean fallback) {
	}

	public static Fit fit(List<Double> speeds) {
		double max = 0;
		for (double v : speeds) {
			if (v > max) {
				max = v;
			}
		}
		// (t, ln v) の最小二乗法。0 以下は捨てる。
		double sumT = 0, sumY = 0, sumTT = 0, sumTY = 0;
		int n = 0;
		for (int t = 0; t < speeds.size(); t++) {
			double v = speeds.get(t);
			if (v <= 0.0001) {
				continue;
			}
			double y = Math.log(v);
			sumT += t;
			sumY += y;
			sumTT += (double) t * t;
			sumTY += t * y;
			n++;
		}
		if (n < 2) {
			return new Fit(max, -1, n, true);
		}
		double denom = n * sumTT - sumT * sumT;
		if (Math.abs(denom) < 1e-9) {
			return new Fit(max, -1, n, true);
		}
		double b = (n * sumTY - sumT * sumY) / denom;
		double a = (sumY - b * sumT) / n;
		double decay = Math.exp(b);
		// 発散（加速）は物理的におかしいので最大値に倒す
		if (decay > 1.0 || decay < 0.5) {
			return new Fit(max, -1, n, true);
		}
		return new Fit(Math.exp(a), decay, n, false);
	}
}
