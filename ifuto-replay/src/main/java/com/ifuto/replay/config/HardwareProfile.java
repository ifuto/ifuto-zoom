package com.ifuto.replay.config;

import com.ifuto.replay.IfutoReplayClient;
import org.lwjgl.opengl.GL11;

import java.lang.management.ManagementFactory;

/**
 * 動いている PC の力を見て、推奨設定を出す。
 *
 * <p>見るのは3つ。CPU のコア数、割り当てヒープ、GPU 名。使いすぎ（描画のぶんまで
 * 食う）にも、余裕があるのに絞りすぎ（遅いまま）にもならないよう、比例式＋上下限で決める。
 * 値は使うたびに取り直す（起動時に覚えた古い値は使わない）。
 */
public final class HardwareProfile {
	private static boolean gpuLogged;

	private HardwareProfile() {
	}

	/** 論理コア数 */
	public static int cores() {
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	/** 割り当てヒープ（MB）。ランチャーのメモリ割り当てがそのまま出る */
	public static long heapMb() {
		return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
	}

	/** 物理メモリ（MB）。取れなければ -1 */
	public static long physicalMb() {
		try {
			java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();

			if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
				return sun.getTotalMemorySize() / (1024L * 1024L);
			}
		} catch (Throwable ignored) {
			// 取れない環境では比べない
		}

		return -1L;
	}

	/**
	 * x264 の速さの推奨。コアが少ないほど速い側に寄せる
	 * （遅い CPU で medium にするとエンコードが律速になるため）。
	 */
	public static String recommendedPreset(int cores) {
		if (cores <= 6) {
			return "veryfast";
		}

		if (cores <= 12) {
			return "fast";
		}

		return "medium";
	}

	/**
	 * x264 に使わせるスレッド数。描画と書き込みのぶん2コア空ける。
	 * 上限32（1080p ではそれ以上増やしても描画より速くならないので）。
	 */
	public static int recommendedThreads(int cores) {
		return Math.min(32, Math.max(1, cores - 2));
	}

	/**
	 * 速さに見合うビットレート（kbps）。速いプリセットほど効率が落ちるので、
	 * そのぶん上げて画質を保つ（medium の 20000 が基準）。
	 */
	public static int recommendedBitrateKbps(String preset) {
		if (preset == null) {
			return 20000;
		}

		return switch (preset) {
			case "ultrafast", "superfast" -> 30000;
			case "veryfast" -> 25000;
			case "faster", "fast" -> 22000;
			case "slow", "slower", "veryslow" -> 18000;
			default -> 20000;
		};
	}

	/**
	 * 初回起動の設定ファイルに推奨値を入れる。既にある設定は絶対に触らない
	 * （呼ぶ側が新規作成のときだけ呼ぶ）。
	 */
	public static void applyRecommended(ReplayConfig config) {
		int cores = cores();
		String preset = recommendedPreset(cores);
		config.exportX264Preset = preset;
		config.exportBitrateKbps = recommendedBitrateKbps(preset);

		long heap = heapMb();
		long physical = physicalMb();

		if (physical > 0 && heap > physical * 3L / 4L) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] メモリ割り当てが物理メモリの3/4を超えています（{}MB/{}MB）。OS が苦しくなるので下げ推奨",
					heap, physical);
		} else if (heap < 2048L) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] メモリ割り当てが少なめです（{}MB）。録画の待ち行列を抑えめにします", heap);
		}

		IfutoReplayClient.LOGGER.info("[ifuto-replay] 初回起動の自動調整: CPU{}コア/ヒープ{}MB → プリセット{}/{}kbps/{}スレッド",
				cores, heap, preset, config.exportBitrateKbps, recommendedThreads(cores));
	}

	/**
	 * GPU 名を1回だけ出す。GL がまだ無いうちは黙って次へ（tick ごとに呼ぶ）。
	 */
	public static void maybeLogGpu() {
		if (gpuLogged) {
			return;
		}

		String renderer;

		try {
			renderer = GL11.glGetString(GL11.GL_RENDERER);
		} catch (Throwable ignored) {
			return;
		}

		if (renderer == null || renderer.isBlank()) {
			return;
		}

		gpuLogged = true;
		String vendor = null;

		try {
			vendor = GL11.glGetString(GL11.GL_VENDOR);
		} catch (Throwable ignored) {
			// 本体が取れていれば十分
		}

		String name = renderer.replaceAll("\\s+", " ").trim();

		if (name.length() > 128) {
			name = name.substring(0, 128);
		}

		IfutoReplayClient.LOGGER.info("[ifuto-replay] GPU: {}{}",
				name, vendor == null || vendor.isBlank() ? "" : " (" + vendor.replaceAll("\\s+", " ").trim() + ")");
	}
}
