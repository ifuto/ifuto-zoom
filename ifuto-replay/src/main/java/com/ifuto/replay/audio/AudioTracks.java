package com.ifuto.replay.audio;

import com.ifuto.replay.recording.ReplayFormat;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
