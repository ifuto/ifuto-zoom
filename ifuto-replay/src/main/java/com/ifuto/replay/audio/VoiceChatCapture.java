package com.ifuto.replay.audio;

import com.ifuto.replay.IfutoReplayClient;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * VC の声だけを1本の音声にする。
 *
 * <p>Simple Voice Chat は **再生される直前の生の音**（48kHz モノラル、20ms ずつ）を
 * プラグインへくれるので、それを順に並べるだけで「VC だけの音声」になる。
 *
 * <p>声が途切れている間も **無音を書き込んで時刻を合わせる** のが要点。
 * そうしないと、あとで絵と一緒にしたときにズレていく。
 */
public final class VoiceChatCapture {
	/** Simple Voice Chat の音声は 48kHz */
	public static final int SAMPLE_RATE = 48000;

	/** 1フレームは 20ms（960サンプル） */
	public static final int FRAME_SIZE = (SAMPLE_RATE / 1000) * 20;

	private static final long FRAME_MS = 20L;

	/** 1回の書き込みに混ぜる上限（溜めすぎを防ぐ） */
	private static final int MAX_FRAMES_PER_WRITE = 16;

	private final BlockingQueue<short[]> queue = new LinkedBlockingQueue<>(512);
	private volatile @Nullable PcmCapture capture;
	private volatile @Nullable Thread thread;
	private volatile boolean running;

	public VoiceChatCapture() {
	}

	/** 録り始める（VC が無い環境では何もしない） */
	public synchronized boolean start(Path output, int bitrateKbps, String ffmpegPath) {
		if (this.running) {
			return false;
		}

		PcmCapture started = PcmCapture.start(ffmpegPath, output, SAMPLE_RATE, 1, bitrateKbps, FRAME_SIZE);

		if (started == null) {
			return false;
		}

		this.capture = started;
		this.running = true;
		Thread worker = new Thread(this::loop, "ifuto-replay-voice");
		this.thread = worker;
		worker.setDaemon(true);
		worker.start();
		return true;
	}

	/** 録り終える */
	public synchronized void stop() {
		this.running = false;
		Thread worker = this.thread;
		this.thread = null;

		if (worker != null) {
			worker.interrupt();

			try {
				worker.join(2000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		PcmCapture current = this.capture;
		this.capture = null;

		if (current != null) {
			current.stop();
		}

		this.queue.clear();
	}

	public boolean isRunning() {
		PcmCapture current = this.capture;
		return this.running && current != null && current.isRunning();
	}

	/**
	 * 届いた声を1フレーム受け取る（48kHz モノラル 20ms）。
	 *
	 * <p>ここは VC 側のスレッドから呼ばれるので、溜めるだけにして即座に帰る。
	 */
	public void offer(short[] frame) {
		if (!this.running || frame == null || frame.length == 0) {
			return;
		}

		this.queue.offer(frame);
	}

	/** 実時間に合わせて20msずつ書き出す（来なければ無音） */
	private void loop() {
		short[] chunk = new short[FRAME_SIZE];

		while (this.running) {
			long startedAt = System.nanoTime();
			Arrays.fill(chunk, (short) 0);

			short[] frame;
			int mixed = 0;

			while (mixed < MAX_FRAMES_PER_WRITE && (frame = this.queue.poll()) != null) {
				mix(chunk, frame);
				mixed++;
			}

			PcmCapture current = this.capture;

			if (current != null) {
				current.write(chunk);
			}

			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

			if (elapsedMs < FRAME_MS) {
				try {
					Thread.sleep(FRAME_MS - elapsedMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/** 同時にしゃべった声を足す（音割れしないように範囲内に収める） */
	private static void mix(short[] target, short[] source) {
		int length = Math.min(target.length, source.length);

		for (int i = 0; i < length; i++) {
			int value = target[i] + source[i];

			if (value > Short.MAX_VALUE) {
				value = Short.MAX_VALUE;
			} else if (value < Short.MIN_VALUE) {
				value = Short.MIN_VALUE;
			}

			target[i] = (short) value;
		}
	}
}
