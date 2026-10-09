package com.ifuto.kb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 中央値と IQR。要約は外れ値に強い中央値で出す。MC 非依存・単体テスト対象。 */
public final class Estimator {
	private Estimator() {
	}

	/** -1（無効）は除く。空なら -1。 */
	public static double median(List<Double> values) {
		ArrayList<Double> sorted = new ArrayList<>();
		for (double v : values) {
			if (v >= 0) {
				sorted.add(v);
			}
		}
		if (sorted.isEmpty()) {
			return -1;
		}
		Collections.sort(sorted);
		int n = sorted.size();
		if (n % 2 == 1) {
			return sorted.get(n / 2);
		}
		return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
	}

	/** 四分位範囲。n < 2 なら 0、空なら -1。 */
	public static double iqr(List<Double> values) {
		ArrayList<Double> sorted = new ArrayList<>();
		for (double v : values) {
			if (v >= 0) {
				sorted.add(v);
			}
		}
		if (sorted.isEmpty()) {
			return -1;
		}
		if (sorted.size() < 2) {
			return 0;
		}
		Collections.sort(sorted);
		int n = sorted.size();
		double q1 = median(sorted.subList(0, n / 2));
		double q3 = (n % 2 == 1) ? median(sorted.subList(n / 2 + 1, n)) : median(sorted.subList(n / 2, n));
		return Math.max(0, q3 - q1);
	}
}
