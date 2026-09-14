package com.ifuto.replay.audio;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.recording.ReplayFormat;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 録画と一対で作られる音声ファイル（本体の隣に置くだけの別ファイル）。
 *
 * <p>本体（`.ifreplay`）には手を入れないので、音声が無い録画もそのまま再生できるし、
 * 音声だけ消しても壊れない。
 *
 * <p>取り方ごとにファイルを分けているのは、**あとから VC の声を足したり外したりするため**。
 * <ul>
 *     <li>`.audio.ogg` — Minecraft の音だけ（VC は入らない）</li>
 *     <li>`.voice.ogg` — VC Mod の声だけ</li>
 *     <li>`.system.ogg` — PC 全体の音（声も入っている。取り出し元が違うので別に持つ）</li>
 * </ul>
 */
public final class AudioTracks {
	/** Minecraft の音だけ（VC は入らない） */
	public static final String MINECRAFT_SUFFIX = ".audio.ogg";

	/** VC Mod の声だけ */
	public static final String VOICE_SUFFIX = ".voice.ogg";

	/** PC 全体の音（VC の音も入る） */
	public static final String SYSTEM_SUFFIX = ".system.ogg";

	private AudioTracks() {
	}

	/** 録画ファイルから音声ファイルの土台を作る（`2026-09-13_01-23-45.ifreplay` → `2026-09-13_01-23-45`） */
	public static Path base(Path recording) {
		String name = recording.getFileName().toString();

		if (name.endsWith(ReplayFormat.FILE_EXTENSION)) {
			name = name.substring(0, name.length() - ReplayFormat.FILE_EXTENSION.length());
		}

		return recording.resolveSibling(name);
	}

	/** Minecraft の音だけのファイル */
	public static Path minecraftTrack(Path recording) {
		return withSuffix(recording, MINECRAFT_SUFFIX);
	}

	/** VC の声だけのファイル */
	public static Path voiceTrack(Path recording) {
		return withSuffix(recording, VOICE_SUFFIX);
	}

	/** PC 全体の音のファイル */
	public static Path systemTrack(Path recording) {
		return withSuffix(recording, SYSTEM_SUFFIX);
	}

	/** 取り方に応じたファイル */
	public static Path pathFor(Path recording, AudioMode mode) {
		return mode == AudioMode.SYSTEM ? systemTrack(recording) : minecraftTrack(recording);
	}

	/** 3本まとめて（Minecraft / VC / PC全体。順番は固定） */
	public static List<Path> allTracks(Path base) {
		return List.of(minecraftTrack(base), voiceTrack(base), systemTrack(base));
	}

	/**
	 * 何本かを1本にまとめる（**再圧縮しない**）。
	 *
	 * <p>クリップを保存するたびに音声のファイルを分けているので、
	 * 保存したときに「必要なぶん」だけをあとからつなげるのに使う。
	 *
	 * @return まとめられたら true
	 */
	public static boolean concat(List<Path> inputs, Path output, String ffmpegPath) {
		List<Path> files = new ArrayList<>();

		for (Path input : inputs) {
			if (exists(input)) {
				files.add(input);
			}
		}

		if (files.isEmpty()) {
			return false;
		}

		Path list = output.resolveSibling(output.getFileName() + ".parts.txt");

		try {
			StringBuilder text = new StringBuilder();

			for (Path file : files) {
				text.append("file '").append(file.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
			}

			Files.writeString(list, text.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声をつなぐ一覧を作れませんでした", e);
			return false;
		}

		try {
			return run(ffmpegPath, List.of("-y", "-hide_banner", "-loglevel", "warning",
					"-f", "concat", "-safe", "0", "-i", list.toAbsolutePath().toString(),
					"-c", "copy", output.toAbsolutePath().toString())) && exists(output);
		} finally {
			try {
				Files.deleteIfExists(list);
			} catch (IOException ignored) {
				// 残っても一時フォルダの掃除で消える
			}
		}
	}

	private static Path withSuffix(Path recording, String suffix) {
		Path base = base(recording);
		return base.resolveSibling(base.getFileName().toString() + suffix);
	}

	/**
	 * 書き出しで使う音声を選ぶ。
	 *
	 * @param wantVoiceChat VC の声（Simple Voice Chat / Plasmo Voice）を入れたいか
	 * @return 使うファイル（1本または2本）。無ければ null
	 */
	public static @Nullable List<Path> select(Path recording, boolean wantVoiceChat) {
		Path minecraft = minecraftTrack(recording);
		Path voice = voiceTrack(recording);
		Path system = systemTrack(recording);
		boolean hasMinecraft = exists(minecraft);
		boolean hasVoice = exists(voice);
		boolean hasSystem = exists(system);

		if (wantVoiceChat) {
			// PC 全体の音には最初から声が入っている
			if (hasSystem) {
				return List.of(system);
			}

			// 別々に録ってあるなら、書き出しのときに混ぜる
			if (hasMinecraft && hasVoice) {
				return List.of(minecraft, voice);
			}

			if (hasMinecraft) {
				return List.of(minecraft);
			}

			return hasVoice ? List.of(voice) : null;
		}

		// 声を外したい。Minecraft だけの音があればそれだけで足りる
		if (hasMinecraft) {
			return List.of(minecraft);
		}

		// Minecraft だけの音が無い場合は「声だけ」か「PC 全体」しか無いので、音声ごと除く
		return null;
	}

	/** 音声があるか（書き出し画面に「音声を含める」を出すかどうか） */
	public static boolean hasAny(Path recording) {
		return exists(minecraftTrack(recording)) || exists(voiceTrack(recording)) || exists(systemTrack(recording));
	}

	/**
	 * 「VC を外す」を選ぶと音声その物が無くなってしまうか。
	 *
	 * <p>Minecraft だけの音が無く、PC 全体の音（あるいは声だけ）しか無い場合がこれに当たる。
	 * 画面では、そのことを先に説明するために使う。
	 */
	public static boolean losesAudioWithoutVoiceChat(Path recording) {
		return !exists(minecraftTrack(recording)) && (exists(systemTrack(recording)) || exists(voiceTrack(recording)));
	}

	private static boolean exists(@Nullable Path path) {
		return path != null && Files.isRegularFile(path);
	}

	/**
	 * クリップ方式むけ: **いちばん後ろの {@code seconds} 秒だけ** を切り出す。
	 *
	 * <p>音声は区間と違って「止めて録り直す」と音が途切れる（特に Minecraft の音は
	 * 出力機器を開き直す必要がある）ので、ずっと1本で録っておいて、
	 * 保存するときに後ろだけ切り出している。
	 *
	 * @return 切り出せたら true
	 */
	public static boolean tail(Path input, Path output, double seconds, String ffmpegPath) {
		if (!exists(input)) {
			return false;
		}

		String offset = String.format(java.util.Locale.ROOT, "-%.3f", Math.max(0.1, seconds));

		// まずは「そのままコピー」。失敗したら作り直す（Opus なら音質の劣化はほぼ無い）
		if (run(ffmpegPath, List.of("-y", "-sseof", offset, "-i", input.toAbsolutePath().toString(),
				"-c", "copy", output.toAbsolutePath().toString()))) {
			return exists(output);
		}

		return run(ffmpegPath, List.of("-y", "-sseof", offset, "-i", input.toAbsolutePath().toString(),
				"-c:a", "libopus", "-b:a", "96k", output.toAbsolutePath().toString())) && exists(output);
	}

	private static boolean run(String ffmpegPath, List<String> args) {
		List<String> command = new ArrayList<>();
		command.add(ffmpegPath);
		command.addAll(args);

		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			drain(process);

			if (!process.waitFor(120L, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}

			return process.exitValue() == 0;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg を実行できませんでした", e);
			return false;
		}
	}

	/** ffmpeg が詰まらないように、出力を読み捨てる */
	private static void drain(Process process) {
		Thread thread = new Thread(() -> {
			try (InputStream in = process.getInputStream()) {
				byte[] buffer = new byte[4096];

				while (in.read(buffer) > 0) {
					// 読むだけ（ログは出さない。うるさいので）
				}
			} catch (IOException ignored) {
				// 終わっただけで問題ない
			}
		}, "ifuto-replay-audio-drain");

		thread.setDaemon(true);
		thread.start();
	}

	/** 要らない音声ファイルを消す（取れていなかったときなど） */
	public static void discard(Path recording) {
		delete(minecraftTrack(recording));
		delete(voiceTrack(recording));
		delete(systemTrack(recording));
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 消せなくても録画の本体には影響しない
		}
	}
}
