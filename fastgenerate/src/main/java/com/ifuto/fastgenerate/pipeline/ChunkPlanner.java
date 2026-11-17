package com.ifuto.fastgenerate.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「次にどのチャンクを作るか」を決める純ロジック。
 *
 * <p>人間の移動は連続なので、<b>これから踏む順</b>に作れば、プレイヤーが
 * 到着したときには地形ができている。並べ替えの優先度は「到着までの時間」の
 * 近似で、距離から進行方向の射影を引いたもの:</p>
 *
 * <pre>
 *   urgency = 距離 − cos(進行方向とのなす角) × directionBias × (radius / 2)
 *            （背後はさらに +2 のペナルティ）
 * </pre>
 *
 * <p>これは最短処理時間順（Smith 1956 / Schrage 1968）を「到着予測」に置き換えた
 * もので、平均待ち時間を最小化する。進行方向の先読みはストライド・プリフェッチ
 * （Smith 1982 / Joseph &amp; Grunwald 1997）と同じ発想。</p>
 *
 * <p>Minecraft に依存しない（テストできる）。</p>
 */
public final class ChunkPlanner {
	/** これより遅い移動は「静止」とみなす [ブロック/tick] */
	public static final double STILL_SPEED = 0.02;
	/** 背後にあるときの追加ペナルティ */
	public static final double BEHIND_PENALTY = 2.0;

	/** 視点（プレイヤーのチャンク座標と 1 tick あたりの移動量） */
	public record Viewpoint(int chunkX, int chunkZ, double vx, double vz) {
		public double speed() {
			return Math.hypot(vx, vz);
		}
	}

	private ChunkPlanner() {
	}

	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}

	/**
	 * 候補チャンクを「作るべき順」に並べて返す。
	 *
	 * @param views         視点（プレイヤーごと）
	 * @param radius        視点からの半径 [チャンク]
	 * @param directionBias 進行方向の重み（0 で同心円）
	 * @param limit         返す最大数（0 以下なら全部）
	 */
	public static long[] order(List<Viewpoint> views, int radius, double directionBias, int limit) {
		if (views.isEmpty() || radius < 0) {
			return new long[0];
		}
		Map<Long, Double> scores = new HashMap<>();
		int r2 = radius * radius;
		for (Viewpoint view : views) {
			double speed = view.speed();
			boolean moving = speed > STILL_SPEED;
			double ux = moving ? view.vx() / speed : 0.0;
			double uz = moving ? view.vz() / speed : 0.0;
			double frontBoost = directionBias * radius * 0.5;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dz * dz > r2) {
						continue;
					}
					long key = key(view.chunkX() + dx, view.chunkZ() + dz);
					double dist = Math.sqrt(dx * dx + dz * dz);
					double cos = dist > 1.0e-9 ? (dx * ux + dz * uz) / dist : 0.0;
					double urgency = dist - Math.max(0.0, cos) * frontBoost;
					if (cos < -0.25 && moving) {
						urgency += BEHIND_PENALTY;   // 背後のチャンクは後回し
					}
					Double previous = scores.get(key);
					if (previous == null || urgency < previous) {
						scores.put(key, urgency);
					}
				}
			}
		}

		List<Map.Entry<Long, Double>> entries = new ArrayList<>(scores.entrySet());
		// 同点はキー順で決める（HashMap の反復順に依存しない = テストも実機も再現可能）
		entries.sort((a, b) -> {
			int byScore = Double.compare(a.getValue(), b.getValue());
			return byScore != 0 ? byScore : Long.compare(a.getKey(), b.getKey());
		});
		int size = limit > 0 ? Math.min(limit, entries.size()) : entries.size();
		long[] out = new long[size];
		for (int i = 0; i < size; i++) {
			out[i] = entries.get(i).getKey();
		}
		return out;
	}

	/** そのチャンクがどれかの視点の半径内にあるか（チケット解放の判定に使う） */
	public static boolean withinAny(List<Viewpoint> views, int x, int z, int radius) {
		int r2 = radius * radius;
		for (Viewpoint view : views) {
			int dx = x - view.chunkX();
			int dz = z - view.chunkZ();
			if (dx * dx + dz * dz <= r2) {
				return true;
			}
		}
		return false;
	}
}
