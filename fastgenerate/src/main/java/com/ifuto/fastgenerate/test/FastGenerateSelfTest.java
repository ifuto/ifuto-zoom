package com.ifuto.fastgenerate.test;

import com.ifuto.fastgenerate.FastGeneratePreLaunch;
import com.ifuto.fastgenerate.pipeline.ChunkPlanner;
import com.ifuto.fastgenerate.pipeline.TickController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * FastGenerate の中核ロジックのヘッドレス自己テスト。
 *
 * <p>Minecraft 抜きで検証できる部分（優先順位・AIMD 制御・スレッド数・
 * ウィンドウとスループットの関係）を毎回 CI で回す。</p>
 */
public final class FastGenerateSelfTest {
	private static int passed = 0;

	public static void main(String[] args) {
		testBgThreads();
		testPlannerCoverage();
		testPlannerDirection();
		testPlannerDedupe();
		testPlannerAheadBeforeBehind();
		testControllerEwma();
		testControllerAimd();
		testControllerHysteresis();
		testLittleLaw();
		testWindowBounds();
		System.out.println("[fastgenerate-test] all " + passed + " checks passed");
	}

	/** 2〜4 コアでは全コア、8 コア以上は 1 本あける */
	private static void testBgThreads() {
		check(FastGeneratePreLaunch.bgThreads(1) == 1, "1 core -> 1 thread");
		check(FastGeneratePreLaunch.bgThreads(2) == 2, "2 cores -> 2 threads (vanilla: 1)");
		check(FastGeneratePreLaunch.bgThreads(4) == 4, "4 cores -> 4 threads (vanilla: 3)");
		check(FastGeneratePreLaunch.bgThreads(8) == 7, "8 cores -> 7 threads");
		check(FastGeneratePreLaunch.bgThreads(64) == 16, "64 cores -> capped at 16");
		System.out.println("[fastgenerate-test] bgThreads: 2->" + FastGeneratePreLaunch.bgThreads(2)
			+ " 4->" + FastGeneratePreLaunch.bgThreads(4)
			+ " 8->" + FastGeneratePreLaunch.bgThreads(8));
	}

	/** 半径内を漏れなく・重複なく、範囲外を含まない */
	private static void testPlannerCoverage() {
		List<ChunkPlanner.Viewpoint> views = List.of(new ChunkPlanner.Viewpoint(0, 0, 0.0, 0.0));
		int radius = 6;
		long[] order = ChunkPlanner.order(views, radius, 0.0, 0);
		Set<Long> seen = new HashSet<>();
		for (long key : order) {
			int dx = ChunkPlanner.keyX(key);
			int dz = ChunkPlanner.keyZ(key);
			check(dx * dx + dz * dz <= radius * radius, "outside radius: " + dx + "," + dz);
			check(seen.add(key), "duplicate: " + dx + "," + dz);
		}
		check(seen.contains(ChunkPlanner.key(0, 0)), "center included");
		check(seen.contains(ChunkPlanner.key(radius, 0)), "edge included");
		check(!seen.contains(ChunkPlanner.key(radius + 1, 0)), "past edge excluded");
		check(!seen.contains(ChunkPlanner.key(5, 5)), "corner outside circle excluded");
		System.out.println("[fastgenerate-test] coverage: " + order.length + " chunks in r=" + radius);
	}

	/** 進行方向のチャンクが前に来る（プリフェッチの本体） */
	private static void testPlannerDirection() {
		List<ChunkPlanner.Viewpoint> moving = List.of(new ChunkPlanner.Viewpoint(0, 0, 0.5, 0.0));
		long[] flat = ChunkPlanner.order(moving, 8, 0.0, 24);
		long[] biased = ChunkPlanner.order(moving, 8, 1.0, 24);

		double flatDx = averageDx(flat);
		double biasedDx = averageDx(biased);
		check(biasedDx > flatDx + 1.0,
			"direction bias pulls front chunks forward: flat=" + flatDx + " biased=" + biasedDx);

		double flatDist = averageDistance(flat);
		double biasedDist = averageDistance(biased);
		check(biasedDist > flatDist,
			"front chunks are farther away than the flat nearest-first order: "
				+ flatDist + " vs " + biasedDist);
		System.out.printf(Locale.ROOT,
			"[fastgenerate-test] direction: flat(dx=%.2f, d=%.2f) biased(dx=%.2f, d=%.2f)%n",
			flatDx, flatDist, biasedDx, biasedDist);
	}

	/** 同じ場所に居る 2 人の候補は 1 回だけ */
	private static void testPlannerDedupe() {
		List<ChunkPlanner.Viewpoint> one = List.of(new ChunkPlanner.Viewpoint(0, 0, 0.0, 0.0));
		List<ChunkPlanner.Viewpoint> two = List.of(
			new ChunkPlanner.Viewpoint(0, 0, 0.0, 0.0),
			new ChunkPlanner.Viewpoint(0, 0, 0.0, 0.0));
		long[] single = ChunkPlanner.order(one, 5, 0.0, 0);
		long[] doubled = ChunkPlanner.order(two, 5, 0.0, 0);
		check(single.length == doubled.length, "duplicate viewpoints deduped");
		System.out.println("[fastgenerate-test] dedupe: " + single.length + " == " + doubled.length);
	}

	/** 前 (-1) より後ろ (+1) が先に来ない */
	private static void testPlannerAheadBeforeBehind() {
		List<ChunkPlanner.Viewpoint> moving = List.of(new ChunkPlanner.Viewpoint(0, 0, 1.0, 0.0));
		long[] order = ChunkPlanner.order(moving, 6, 1.0, 0);
		int ahead = indexOf(order, ChunkPlanner.key(2, 0));
		int behind = indexOf(order, ChunkPlanner.key(-2, 0));
		check(ahead >= 0 && behind >= 0, "both candidates present");
		check(ahead < behind, "ahead (" + ahead + ") before behind (" + behind + ")");
		System.out.println("[fastgenerate-test] ahead index " + ahead + " < behind index " + behind);
	}

	/** EWMA は一定入力を数 τ で追いかける */
	private static void testControllerEwma() {
		TickController controller = new TickController(1, 16, 4);
		for (int i = 0; i < 40; i++) {
			controller.sample(60.0, 0.0);
			controller.update(1000.0, 0.0);   // 目標を大きくして増やさない
		}
		check(Math.abs(controller.mspt() - 60.0) < 1.0,
			"EWMA converges: " + controller.mspt());
		System.out.printf(Locale.ROOT, "[fastgenerate-test] ewma after 40 ticks: %.2fms%n", controller.mspt());
	}

	/** 余裕があれば増え、重ければ減る */
	private static void testControllerAimd() {
		TickController light = new TickController(2, 12, 2);
		for (int i = 0; i < 400; i++) {
			light.sample(20.0, 12.0);
			light.update(45.0, 30.0);
		}
		check(light.window() == 12, "light load grows to max: " + light.window());

		TickController heavy = new TickController(2, 12, 12);
		for (int i = 0; i < 400; i++) {
			heavy.sample(120.0, 60.0);
			heavy.update(45.0, 30.0);
		}
		check(heavy.window() == 2, "heavy load shrinks to min: " + heavy.window());

		TickController frameBound = new TickController(2, 12, 12);
		for (int i = 0; i < 400; i++) {
			frameBound.sample(10.0, 80.0);   // CPU は余裕でもフレームが詰まる
			frameBound.update(45.0, 30.0);
		}
		check(frameBound.window() < 12, "frame time also throttles: " + frameBound.window());
		System.out.println("[fastgenerate-test] aimd: light=" + light.window()
			+ " heavy=" + heavy.window() + " frameBound=" + frameBound.window());
	}

	/** 変化した直後は HOLD_TICKS のあいだ動かさない（振動しない） */
	private static void testControllerHysteresis() {
		TickController controller = new TickController(2, 12, 2);
		int previous = controller.window();
		int changedAt = -1;
		for (int i = 0; i < 100; i++) {
			controller.sample(20.0, 0.0);
			int window = controller.update(45.0, 0.0);
			if (window != previous) {
				changedAt = i;
				break;
			}
			previous = window;
		}
		check(changedAt >= 0, "window changes at least once under light load");

		int held = controller.update(45.0, 0.0);
		check(held == controller.window(), "window is stable right after a change");
		for (int i = 0; i < TickController.HOLD_TICKS - 2; i++) {
			check(controller.update(45.0, 0.0) == held, "window held during HOLD_TICKS");
		}
		// HOLD が明けたら、重い負荷ではちゃんと下がる
		for (int i = 0; i < 60; i++) {
			controller.sample(120.0, 0.0);
			controller.update(45.0, 0.0);
		}
		check(controller.window() < held, "window shrinks once the hold expires: "
			+ held + " -> " + controller.window());
		System.out.println("[fastgenerate-test] hysteresis: changed at tick " + changedAt
			+ ", held=" + held + ", after load=" + controller.window());
	}

	/**
	 * Little の法則: 同時実行 L・所要 W なら スループット = L/W。
	 * ウィンドウを広げるほど速くなるが、L/W で頭打ちになることを確認する。
	 */
	private static void testLittleLaw() {
		double t2 = throughput(2, 4, 4000);
		double t4 = throughput(4, 4, 4000);
		double t8 = throughput(8, 4, 4000);
		check(near(t2, 0.5), "window 2 / latency 4 -> 0.5 chunks/tick, got " + t2);
		check(near(t4, 1.0), "window 4 / latency 4 -> 1.0 chunks/tick, got " + t4);
		check(near(t8, 2.0), "window 8 / latency 4 -> 2.0 chunks/tick, got " + t8);
		check(t8 > t4 && t4 > t2, "throughput grows with the window");
		System.out.printf(Locale.ROOT, "[fastgenerate-test] little: w2=%.2f w4=%.2f w8=%.2f chunks/tick%n",
			t2, t4, t8);
	}

	/** ウィンドウは常に [min, max] に収まる */
	private static void testWindowBounds() {
		TickController controller = new TickController(3, 9, 3);
		java.util.Random random = new java.util.Random(1);
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int i = 0; i < 5000; i++) {
			controller.sample(random.nextDouble() * 150.0, random.nextDouble() * 60.0);
			int window = controller.update(45.0, 30.0);
			min = Math.min(min, window);
			max = Math.max(max, window);
		}
		check(min >= 3 && max <= 9, "window within bounds: " + min + ".." + max);
		System.out.println("[fastgenerate-test] bounds: " + min + ".." + max);
	}

	// ------------------------------------------------------------------

	/** ウィンドウ W・所要 L tick のパイプラインをシミュレートし、スループットを返す */
	private static double throughput(int window, int latency, int ticks) {
		PriorityQueue<Integer> ends = new PriorityQueue<>();
		int free = window;
		int completed = 0;
		for (int tick = 0; tick < ticks; tick++) {
			while (!ends.isEmpty() && ends.peek() <= tick) {
				ends.poll();
				free++;
				completed++;
			}
			while (free > 0) {
				ends.add(tick + latency);
				free--;
			}
		}
		return completed / (double) (ticks - latency);
	}

	private static double averageDx(long[] keys) {
		double sum = 0;
		for (long key : keys) {
			sum += ChunkPlanner.keyX(key);
		}
		return keys.length == 0 ? 0 : sum / keys.length;
	}

	private static double averageDistance(long[] keys) {
		double sum = 0;
		for (long key : keys) {
			int x = ChunkPlanner.keyX(key);
			int z = ChunkPlanner.keyZ(key);
			sum += Math.sqrt((double) x * x + (double) z * z);
		}
		return keys.length == 0 ? 0 : sum / keys.length;
	}

	private static int indexOf(long[] keys, long key) {
		for (int i = 0; i < keys.length; i++) {
			if (keys[i] == key) {
				return i;
			}
		}
		return -1;
	}

	private static boolean near(double value, double expected) {
		return Math.abs(value - expected) <= expected * 0.1;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			System.out.println("[fastgenerate-test] FAILED: " + message);
			System.exit(1);
		}
		passed++;
	}
}
