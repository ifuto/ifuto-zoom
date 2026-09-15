package com.ifuto.replay.export;

import com.ifuto.replay.IfutoReplayClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 使える GPU エンコーダーを探す（NVIDIA / Intel / AMD / Apple の順）。
 *
 * <p>GPU 側の H.264（NVENC など）は、x264 の medium とほぼ同じ画質で
 * 数倍の速さが出る。あるなら使う。無ければ CPU（libx264）に任せる。
 * 結果は ffmpeg の場所ごとに覚えておく（起動のたびに探さない）。
 */
public final class EncoderProbe {
	private static final String[] HARDWARE = {
			"h264_nvenc",
			"h264_qsv",
			"h264_amf",
			"h264_videotoolbox",
	};

	/** 1回の問い合わせに待つ上限 */
	private static final long PROBE_TIMEOUT_SECONDS = 10L;

	private static final Object LOCK = new Object();
	private static String cachedFfmpeg;
	private static String cachedEncoder;

	private EncoderProbe() {
	}

	/**
	 * 使うエンコーダー名。GPU が使えなければ null（CPU で出す）。
	 */
	public static String select(String ffmpegPath) {
		synchronized (LOCK) {
			if (ffmpegPath != null && ffmpegPath.equals(cachedFfmpeg)) {
				return cachedEncoder;
			}

			String found = null;

			for (String encoder : HARDWARE) {
				if (available(ffmpegPath, encoder)) {
					found = encoder;
					break;
				}
			}

			cachedFfmpeg = ffmpegPath;
			cachedEncoder = found;

			if (found != null) {
				IfutoReplayClient.LOGGER.info("[ifuto-replay] GPU エンコーダーを使います: {}", found);
			} else {
				IfutoReplayClient.LOGGER.info("[ifuto-replay] GPU エンコーダーが無いので CPU で出します");
			}

			return found;
		}
	}

	private static boolean available(String ffmpegPath, String encoder) {
		try {
			ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-hide_banner", "-h", "encoder=" + encoder);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			drain(process);

			if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}

			return process.exitValue() == 0;
		} catch (IOException | RuntimeException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** 出力を読み捨てる（読まないと詰まる） */
	private static void drain(Process process) {
		Thread thread = new Thread(() -> {
			try (InputStream in = process.getInputStream()) {
				byte[] buffer = new byte[8192];

				while (in.read(buffer) > 0) {
					// 読むだけ
				}
			} catch (IOException ignored) {
				// 終わっただけで問題ない
			}
		}, "ifuto-replay-encoder-probe");

		thread.setDaemon(true);
		thread.start();
	}
}
