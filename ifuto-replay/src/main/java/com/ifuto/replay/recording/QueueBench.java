package com.ifuto.replay.recording;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 受け渡し箱（{@link MpscPacketQueue}）の正しさと速さ比べ。
 *
 * <p>CI で毎回走る。壊れていたら落とす（壊れたキューは出さない）。
 * 4人入れ×1人取り出しで「全部がちょうど1回」届くか、いっぱい・空っぽの
 * 判定、寝ている取り出し側が起こされるかを見る。速さは鍵つきの箱
 * （{@link ArrayBlockingQueue}）と比べる。
 *
 * <p>結果は {@code ::notice::} でも出す（Checks から読めるように）。
 */
public final class QueueBench {
	private QueueBench() {
	}

	public static void main(String[] args) throws Exception {
		try {
			run();
		} catch (Throwable t) {
			System.out.println("::error::QFAIL " + t);

			for (StackTraceElement e : t.getStackTrace()) {
				System.out.println("::error::QFAIL at " + e);
			}

			throw t;
		}
	}

	private static void run() throws Exception {
		checkEmptyFull();
		checkWakeup();
		checkExactlyOnce(1, 200_000);
		checkExactlyOnce(4, 100_000);

		double mpsc1 = perf(true, 1);
		double abq1 = perf(false, 1);
		double mpsc4 = perf(true, 4);
		double abq4 = perf(false, 4);

		report("correctness OK (exactly-once 1P/4P, full/empty, wakeup)");
		report(String.format("1P1C mpsc=%.2fM/s abq=%.2fM/s speedup=%.2fx",
				mpsc1, abq1, mpsc1 / abq1));
		report(String.format("4P1C mpsc=%.2fM/s abq=%.2fM/s speedup=%.2fx",
				mpsc4, abq4, mpsc4 / abq4));
	}

	private static void report(String line) {
		System.out.println("[qbench] " + line);
		System.out.println("::notice::QBENCH " + line);
	}

	/** いっぱい・空っぽの判定（1人で） */
	private static void checkEmptyFull() {
		MpscPacketQueue queue = new MpscPacketQueue(64);

		if (queue.poll() != null) {
			throw new AssertionError("空なのに取れた");
		}

		PacketTask marker = PacketTask.marker(1L, "");

		for (int i = 0; i < 64; i++) {
			if (!queue.offer(marker)) {
				throw new AssertionError("64 個入るはずが " + i + " 個でいっぱい");
			}
		}

		if (queue.offer(marker)) {
			throw new AssertionError("いっぱいなのに入った");
		}

		for (int i = 0; i < 64; i++) {
			if (queue.poll() == null) {
				throw new AssertionError(i + " 個しか取れない");
			}
		}

		if (queue.poll() != null) {
			throw new AssertionError("取り切ったのに残っている");
		}
	}

	/** 寝ている取り出し側が、入れられたら起きるか */
	private static void checkWakeup() throws Exception {
		MpscPacketQueue queue = new MpscPacketQueue(64);

		long idleStart = System.nanoTime();

		if (queue.poll(50L, TimeUnit.MILLISECONDS) != null) {
			throw new AssertionError("空なのに取れた");
		}

		if (System.nanoTime() - idleStart < TimeUnit.MILLISECONDS.toNanos(40L)) {
			throw new AssertionError("待たずに帰ってきた");
		}

		Thread producer = new Thread(() -> {
			try {
				Thread.sleep(30L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			queue.offer(PacketTask.marker(7L, ""));
		});
		producer.setDaemon(true);
		producer.start();

		long start = System.nanoTime();
		PacketTask task = queue.poll(5L, TimeUnit.SECONDS);
		long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		producer.join();

		if (task == null || task.timeMs != 7L) {
			throw new AssertionError("起こされて取れなかった");
		}

		if (waitedMs > 1000L) {
			throw new AssertionError("起こされずに寝過ごした: " + waitedMs + "ms");
		}
	}

	/** 全部がちょうど1回届くか（番号の合計と xor で見る） */
	private static void checkExactlyOnce(int producers, int perProducer) throws Exception {
		MpscPacketQueue queue = new MpscPacketQueue(2048);
		long total = (long) producers * perProducer;
		long[] sum = new long[1];
		long[] xor = new long[1];
		long[] count = new long[1];

		Thread consumer = new Thread(() -> {
			while (count[0] < total) {
				PacketTask task = queue.poll();

				if (task != null) {
					sum[0] += task.timeMs;
					xor[0] ^= task.timeMs;
					count[0]++;
				}
			}
		});
		consumer.setDaemon(true);
		consumer.start();

		Thread[] threads = new Thread[producers];

		for (int p = 0; p < producers; p++) {
			final long base = (long) p * perProducer;
			threads[p] = new Thread(() -> {
				for (long i = 0; i < perProducer; i++) {
					PacketTask task = PacketTask.marker(base + i, "");

					while (!queue.offer(task)) {
						Thread.yield();
					}
				}
			});
			threads[p].setDaemon(true);
			threads[p].start();
		}

		for (Thread thread : threads) {
			thread.join(60_000L);

			if (thread.isAlive()) {
				throw new AssertionError("入れる側が終わらない");
			}
		}

		consumer.join(60_000L);

		if (consumer.isAlive()) {
			throw new AssertionError("取り出し側が終わらない");
		}

		long wantSum = total * (total - 1L) / 2L;
		long wantXor = xorTo(total - 1L);

		if (count[0] != total || sum[0] != wantSum || xor[0] != wantXor) {
			throw new AssertionError("抜け・重複あり: count=" + count[0] + "/" + total
					+ " sum=" + sum[0] + "/" + wantSum + " xor=" + xor[0] + "/" + wantXor);
		}
	}

	/** 0 ^ 1 ^ ... ^ n */
	private static long xorTo(long n) {
		return switch ((int) (n & 3L)) {
			case 0 -> n;
			case 1 -> 1L;
			case 2 -> n + 1L;
			default -> 0L;
		};
	}

	/** 速さ比べ（100万件・件数/秒を100万単位で返す） */
	private static double perf(boolean mpsc, int producers) throws Exception {
		int capacity = 2048;
		MpscPacketQueue mpscQueue = mpsc ? new MpscPacketQueue(capacity) : null;
		ArrayBlockingQueue<PacketTask> abq = mpsc ? null : new ArrayBlockingQueue<>(capacity);
		int perProducer = 1_000_000 / producers;
		long total = (long) producers * perProducer;
		long[] count = new long[1];

		Thread consumer = new Thread(() -> {
			while (count[0] < total) {
				PacketTask task = mpsc ? mpscQueue.poll() : abq.poll();

				if (task != null) {
					count[0]++;
				}
			}
		});
		consumer.setDaemon(true);

		Runnable produce = () -> {
			PacketTask task = PacketTask.marker(0L, "");

			for (long i = 0; i < perProducer; i++) {
				if (mpsc) {
					while (!mpscQueue.offer(task)) {
						// いっぱい
					}
				} else {
					while (!abq.offer(task)) {
						// いっぱい
					}
				}
			}
		};

		// 温め
		Thread warmConsumer = new Thread(() -> {
			long warmed = 0;

			while (warmed < total) {
				if ((mpsc ? mpscQueue.poll() : abq.poll()) != null) {
					warmed++;
				}
			}
		});
		warmConsumer.setDaemon(true);
		warmConsumer.start();
		produce.run();
		warmConsumer.join();

		consumer.start();
		Thread[] threads = new Thread[producers];
		long start = System.nanoTime();

		for (int p = 0; p < producers; p++) {
			threads[p] = new Thread(produce);
			threads[p].setDaemon(true);
			threads[p].start();
		}

		for (Thread thread : threads) {
			thread.join();
		}

		consumer.join();
		double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
		return total / seconds / 1_000_000.0;
	}
}
