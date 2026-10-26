package com.ifuto.replay.recording;

import com.ifuto.replay.config.CompressionMode;
import com.ifuto.replay.config.ReplayConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 写し待ちの待機列の自己テスト。gradle testSnapshot / CI で毎回。
 *
 * <p>ゲームなしで走る。順番・上限・終了時の片付けを見る。
 */
public final class SnapshotHoldSelfTest {
	private static int passed = 0;

	public static void main(String[] args) throws Exception {
		testHoldOrder();
		testHoldCap();
		testSessionWiring();
		testCancelAndDouble();
		testOfferAfterFinish();
		System.out.println("SnapshotHoldSelfTest: ALL " + passed + " TESTS PASSED");
	}

	private static void check(boolean cond, String name) {
		if (!cond) {
			throw new AssertionError("FAILED: " + name);
		}

		passed++;
	}

	private static void testHoldOrder() {
		RecordingSession.SnapshotHold hold = new RecordingSession.SnapshotHold();
		check(hold.add(PacketTask.marker(1, "M1"), 1024), "hold add 1");
		check(hold.add(PacketTask.marker(2, "M2"), 1024), "hold add 2");
		check(hold.add(PacketTask.marker(3, "M3"), 1024), "hold add 3");
		check(hold.count() == 3, "hold count");

		List<PacketTask> drained = hold.drain();
		check(drained.size() == 3, "drain size");
		check("M1".equals(drained.get(0).text) && "M2".equals(drained.get(1).text)
				&& "M3".equals(drained.get(2).text), "drain order");
		check(hold.count() == 0 && hold.bytes() == 0, "drain clears");
		check(hold.drain().isEmpty(), "double drain empty");
		check(!hold.add(null, 1024), "hold rejects null");
	}

	private static void testHoldCap() {
		RecordingSession.SnapshotHold hold = new RecordingSession.SnapshotHold();
		byte[] big = new byte[100];
		check(hold.add(PacketTask.input(1, big), 150), "cap fits");
		check(!hold.add(PacketTask.input(2, big), 150), "cap overflows");
		check(hold.count() == 1, "cap keeps first");
		check(hold.bytes() == 100, "cap bytes");
	}

	private static ReplayConfig testConfig() {
		ReplayConfig config = new ReplayConfig();
		config.clipMode = false;
		config.compression = CompressionMode.OFF;
		config.recordClientPackets = true;
		return config;
	}

	private static String readFile(Path file) throws Exception {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static void testSessionWiring() throws Exception {
		Path dir = Files.createTempDirectory("ifuto-hold-test");
		Path file = dir.resolve("hold.ifz");
		RecordingSession session = new RecordingSession(file, testConfig(), "1.21.11", "test", "player",
				System.currentTimeMillis());
		session.start();
		session.beginSnapshotHold();
		check(session.offer(PacketTask.marker(10, "HOLD-B1")), "wiring offer held 1");
		check(session.offer(PacketTask.marker(20, "HOLD-B2")), "wiring offer held 2");
		check(!session.completeSnapshotHold(null), "wiring complete without snapshot");
		check(session.offer(PacketTask.marker(30, "HOLD-A3")), "wiring offer direct");

		RecordingSession.Stats stats = session.finish();
		check(stats.errors() == 0 && stats.dropped() == 0, "wiring no loss");

		String raw = readFile(file);
		int b1 = raw.indexOf("HOLD-B1");
		int b2 = raw.indexOf("HOLD-B2");
		int a3 = raw.indexOf("HOLD-A3");
		check(b1 >= 0 && b2 >= 0 && a3 >= 0, "wiring markers in file");
		check(b1 < b2 && b2 < a3, "wiring file order");
	}

	private static void testCancelAndDouble() throws Exception {
		Path dir = Files.createTempDirectory("ifuto-hold-test2");
		Path file = dir.resolve("c.ifz");
		RecordingSession session = new RecordingSession(file, testConfig(), "1.21.11", "t", "p",
				System.currentTimeMillis());
		session.start();
		session.beginSnapshotHold();
		session.offer(PacketTask.marker(1, "C1"));
		session.cancelSnapshotHold();
		session.cancelSnapshotHold();
		check(!session.completeSnapshotHold(null), "cancel then complete is noop");
		session.offer(PacketTask.marker(2, "C2"));

		RecordingSession.Stats stats = session.finish();
		check(stats.dropped() == 0, "cancel no loss");

		String raw = readFile(file);
		check(raw.indexOf("C1") >= 0 && raw.indexOf("C1") < raw.indexOf("C2"), "cancel order");
	}

	private static void testOfferAfterFinish() throws Exception {
		Path dir = Files.createTempDirectory("ifuto-hold-test3");
		RecordingSession session = new RecordingSession(dir.resolve("f.ifz"), testConfig(), "1.21.11", "t", "p",
				System.currentTimeMillis());
		session.start();
		session.beginSnapshotHold();
		session.offer(PacketTask.marker(1, "F1"));
		// 終了が待機列を流してから閉じる
		session.finish();
		check(!session.offer(PacketTask.marker(2, "F2")), "offer after finish dropped");
		check(!session.completeSnapshotHold(null), "complete after finish is noop");

		String raw = readFile(dir.resolve("f.ifz"));
		check(raw.indexOf("F1") >= 0, "finish drains hold");
		check(raw.indexOf("F2") < 0, "finish drops late offer");
	}
}
