package com.ifuto.replay.audio;

import com.ifuto.replay.recording.ReplayFormat;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 録画と一対で作られる音声ファイル（隣に置くだけの別ファイル）。
 *
 * <p>本体（`.ifreplay`）には手を入れないので、音声が無い録画もそのまま再生できるし、
 * 音声だけ消しても壊れない。
 *
 * <p>取り方によってファイルを分けているのは、**あとから VC の音を外せるようにするため**。
 * VC Mod は Minecraft とは別の出力機器を開くので、「Minecraft だけ」で録った音には
 * そもそも VC が入らない。だから「VC を入れる」を選んだときだけ PC 全体の音を使う。
 */
public final class AudioTracks {
	/** Minecraft の音だけ（VC は入らない） */
	public static final String MINECRAFT_SUFFIX = ".audio.ogg";

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

	public static Path pathFor(Path recording, AudioMode mode) {
		Path base = base(recording);
		return base.resolveSibling(base.getFileName().toString() + suffix(mode));
	}

	private static String suffix(AudioMode mode) {
		return mode == AudioMode.SYSTEM ? SYSTEM_SUFFIX : MINECRAFT_SUFFIX;
	}

	/**
	 * 使う音声ファイルを選ぶ。
	 *
	 * @param wantVoiceChat VC の音（Simple Voice Chat / Plasmo Voice）を入れたいか
	 * @return 使うファイル。無ければ null（その場合は音声なしで書き出す）
	 */
	public static @Nullable Path pick(Path recording, boolean wantVoiceChat) {
		Path minecraft = pathFor(recording, AudioMode.MINECRAFT);
		Path system = pathFor(recording, AudioMode.SYSTEM);
		boolean hasMinecraft = exists(minecraft);
		boolean hasSystem = exists(system);

		if (wantVoiceChat) {
			// VC の音が入っているのは PC 全体の音だけ
			if (hasSystem) {
				return system;
			}

			return hasMinecraft ? minecraft : null;
		}

		// VC を外したい。Minecraft だけの音があればそれを使う。
		// PC 全体の音しか無い場合は「分けられない」ので音声ごと除く（呼び出し側で説明する）
		return hasMinecraft ? minecraft : null;
	}

	/** 音声があるか（書き出し画面の「音声を含める」を出すかどうか） */
	public static boolean hasAny(Path recording) {
		return exists(pathFor(recording, AudioMode.MINECRAFT)) || exists(pathFor(recording, AudioMode.SYSTEM));
	}

	/** VC の音が入っている音声しか無いか（これだと VC だけを外せない） */
	public static boolean onlySystem(Path recording) {
		return !exists(pathFor(recording, AudioMode.MINECRAFT)) && exists(pathFor(recording, AudioMode.SYSTEM));
	}

	private static boolean exists(Path path) {
		return path != null && Files.isRegularFile(path);
	}

	/** 要らない音声ファイルを消す（0バイトで終わった時など） */
	public static void discard(Path recording) {
		delete(pathFor(recording, AudioMode.MINECRAFT));
		delete(pathFor(recording, AudioMode.SYSTEM));
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 消せなくても録画の本体には影響しない
		}
	}
}
