package com.ifuto.replay.export;

import com.ifuto.replay.audio.AudioTracks;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.recording.ReplayFormat;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 書き出しの設定1回分。
 *
 * <p>FPS・解像度・ビットレートは「録ったあとに好きに決められる」のがこの方式のいちばんの利点。
 * 既定値は設定ファイル（`config/ifuto-replay.json`）から持ってくる。
 */
public record ExportOptions(
		int fps,
		int width,
		int height,
		int bitrateKbps,
		String ffmpegPath,
		long startMs,
		long endMs,
		Path output,
		@Nullable List<Path> audio,
		int speedPercent,
		boolean hardwareAccel
) {
	/** 録画1本ぶんの既定の設定を作る */
	public static ExportOptions defaultFor(ReplayConfig config, Path recording, long durationMs) {
		String base = recording.getFileName().toString();

		if (base.endsWith(ReplayFormat.FILE_EXTENSION)) {
			base = base.substring(0, base.length() - ReplayFormat.FILE_EXTENSION.length());
		}

		Path output = uniqueOutput(ReplayConfig.getSaveDirectory().resolve("exports").resolve(base + ".mp4"));

		return new ExportOptions(
				config.exportFps,
				config.exportWidth,
				config.exportHeight,
				config.exportBitrateKbps,
				config.ffmpegPath,
				0L,
				Math.max(0L, durationMs),
				output,
				AudioTracks.select(recording, true),
				config.exportSpeedPercent,
				config.exportHardwareAccel
		);
	}

	/** 同じ名前のファイルがあれば `_2`, `_3` … を付ける */
	public static Path uniqueOutput(Path wanted) {
		if (!Files.exists(wanted)) {
			return wanted;
		}

		String name = wanted.getFileName().toString();
		String base = name;
		String extension = "";
		int dot = name.lastIndexOf('.');

		if (dot > 0) {
			base = name.substring(0, dot);
			extension = name.substring(dot);
		}

		for (int i = 2; i < 1000; i++) {
			Path candidate = wanted.resolveSibling(base + "_" + i + extension);

			if (!Files.exists(candidate)) {
				return candidate;
			}
		}

		return wanted;
	}
}
