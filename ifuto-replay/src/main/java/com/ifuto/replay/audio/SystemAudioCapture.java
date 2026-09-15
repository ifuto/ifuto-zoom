package com.ifuto.replay.audio;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * **PC 全体の音** を ffmpeg で取る（OS の「いま鳴っている音」の入力）。
 *
 * <p>仕組みは書き出しと同じで、ffmpeg に外でやってもらう。
 * Minecraft 側には何も足さないので、音を取れない環境でもゲームは壊れない
 * （そのときは「音声なし」で録画を続ける）。
 *
 * | OS | 取り方 | 必要な物 |
 * | --- | --- | --- |
 * | Windows | `-f dshow -i audio=…` | ステレオミキサー、または VB-CABLE などの仮想デバイス |
 * | Linux | `-f pulse -i ….monitor` | PulseAudio / PipeWire（たいてい最初から入っている） |
 * | macOS | `-f avfoundation -i :…` | BlackHole などの仮想デバイス（**標準では取れない**） |
 *
 * <p>音声は Opus（`.ogg`）で書く。しゃべり声も BGM もこれで十分で、ファイルは小さい。
 */
public final class SystemAudioCapture {
	private static final int DETECT_TIMEOUT_MS = 5000;
	private static final String[] LOOPBACK_HINTS = {
			"stereo mix", "what u hear", "loopback", "monitor", "blackhole", "soundflower", "vb-cable", "cable output"
	};

	private SystemAudioCapture() {
	}

	/** ffmpeg に渡す引数（PC 全体の音 → Opus） */
	public static List<String> command(String ffmpegPath, int bitrateKbps, @Nullable String device, Path output) {
		List<String> command = new ArrayList<>();
		command.add(ffmpegPath);
		command.add("-y");
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("warning");

		switch (os()) {
			case WINDOWS -> {
				command.add("-f");
				command.add("dshow");
				command.add("-i");
				command.add("audio=" + blankToDefault(device, "default"));
			}
			case MACOS -> {
				command.add("-f");
				command.add("avfoundation");
				command.add("-i");
				command.add(":" + blankToDefault(device, "0"));
			}
			default -> {
				command.add("-f");
				command.add("pulse");
				command.add("-i");
				command.add(blankToDefault(device, "default"));
			}
		}

		command.add("-ac");
		command.add("2");
		command.add("-ar");
		command.add("48000");
		command.add("-c:a");
		command.add("libopus");
		command.add("-b:a");
		command.add(bitrateKbps + "k");
		command.add("-f");
		command.add("ogg");
		command.add(output.toAbsolutePath().toString());
		return command;
	}

	/**
	 * 機器を自動で選ぶ。
	 *
	 * <p>設定が空欄のときだけ呼ばれる。「ループバックっぽい名前」を優先し、
	 * 無ければ一覧の先頭を使う（Windows の dshow は `default` で通ることもある）。
	 */
	public static @Nullable String autoDevice(String ffmpegPath) {
		List<String> devices = detectDevices(ffmpegPath);

		if (devices.isEmpty()) {
			return null;
		}

		for (String device : devices) {
			String lower = device.toLowerCase(Locale.ROOT);

			for (String hint : LOOPBACK_HINTS) {
				if (lower.contains(hint)) {
					return device;
				}
			}
		}

		return devices.get(0);
	}

	/** 使えそうな音声機器を探す（見つからなければ空） */
	public static List<String> detectDevices(String ffmpegPath) {
		return switch (os()) {
			case WINDOWS -> parseDshow(run(listDshow(ffmpegPath)));
			case MACOS -> parseAvFoundation(run(listAvFoundation(ffmpegPath)));
			default -> parsePactl(run(listPactlSources()));
		};
	}

	// --- OS ごとの一覧コマンド ---

	private static List<String> listDshow(String ffmpegPath) {
		return List.of(ffmpegPath, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
	}

	private static List<String> listAvFoundation(String ffmpegPath) {
		return List.of(ffmpegPath, "-hide_banner", "-list_devices", "true", "-f", "avfoundation", "-i", "");
	}

	private static List<String> listPactlSources() {
		return List.of("pactl", "list", "short", "sources");
	}

	// --- 解析 ---

	/** dshow: `"Stereo Mix (Realtek(R) Audio)" (audio)` という行から名前を取る */
	private static List<String> parseDshow(String output) {
		List<String> devices = new ArrayList<>();

		for (String line : output.split("\\R")) {
			if (!line.contains("(audio)")) {
				continue;
			}

			int start = line.indexOf('"');
			int end = line.indexOf('"', start + 1);

			if (start >= 0 && end > start) {
				devices.add(line.substring(start + 1, end));
			}
		}

		return devices;
	}

	/** avfoundation: `AVFoundation audio devices:` の後の `[0] 名前` */
	private static List<String> parseAvFoundation(String output) {
		List<String> devices = new ArrayList<>();
		boolean audioSection = false;

		for (String line : output.split("\\R")) {
			String trimmed = line.trim();

			if (trimmed.contains("AVFoundation audio devices")) {
				audioSection = true;
				continue;
			}

			if (trimmed.contains("AVFoundation video devices")) {
				audioSection = false;
				continue;
			}

			if (!audioSection || !trimmed.startsWith("[")) {
				continue;
			}

			int close = trimmed.indexOf(']');

			if (close > 0 && close + 1 < trimmed.length()) {
				String name = trimmed.substring(close + 1).trim();

				if (!name.isEmpty()) {
					devices.add(name);
				}
			}
		}

		return devices;
	}

	/** pactl: `0	alsa_output....monitor	module...` の2列目 */
	private static List<String> parsePactl(String output) {
		List<String> devices = new ArrayList<>();

		for (String line : output.split("\\R")) {
			String[] columns = line.split("\\t");

			if (columns.length >= 2 && !columns[1].isBlank()) {
				devices.add(columns[1].trim());
			}
		}

		return devices;
	}

	// --- 実行 ---

	/** 一覧コマンドの結果（標準出力と標準エラーをまとめて）を取る */
	private static String run(List<String> command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = readAll(process.getInputStream());

			if (!process.waitFor(DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
			}

			return output;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			return "";
		}
	}

	/** 一覧は数行なので、そのまま全部読む */
	private static String readAll(InputStream in) throws IOException {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String blankToDefault(@Nullable String device, String fallback) {
		return device == null || device.isBlank() ? fallback : device.trim();
	}

	private enum Os {
		WINDOWS,
		MACOS,
		OTHER
	}

	private static Os os() {
		String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		if (name.contains("win")) {
			return Os.WINDOWS;
		}

		if (name.contains("mac") || name.contains("darwin")) {
			return Os.MACOS;
		}

		return Os.OTHER;
	}
}
