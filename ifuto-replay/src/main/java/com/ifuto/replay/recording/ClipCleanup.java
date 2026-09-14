package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.audio.AudioTracks;
import com.ifuto.replay.config.ReplayConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 古くなったクリップを自動で片付ける。
 *
 * <p>クリップは「押したときだけ残る」ので数は増えにくいが、そのぶん
 * 消し忘れやすい。設定した時間より古い物を、**音声ファイルごと** 消す。
 * 録画の本体（clip_ から始まらない物）には触らない。
 */
public final class ClipCleanup {
	private static final String PREFIX = "clip_";
	private static final String SUFFIX = ".ifreplay";

	private ClipCleanup() {
	}

	/**
	 * 保存フォルダにある古いクリップを消す（クライアントスレッドから呼ぶ）。
	 *
	 * @param keepHours 残す時間（0 以下なら何もしない）
	 * @return 消した数
	 */
	public static int prune(long keepHours) {
		if (keepHours <= 0L) {
			return 0;
		}

		Path directory = ReplayConfig.getSaveDirectory();
		List<Path> clips = new ArrayList<>();

		try (Stream<Path> files = Files.list(directory)) {
			files.filter(ClipCleanup::isClip).forEach(clips::add);
		} catch (IOException e) {
			// フォルダが無い / 読めないだけ。録画の邪魔はしない
			return 0;
		}

		long deadline = System.currentTimeMillis() - keepHours * 60L * 60L * 1000L;
		int removed = 0;

		for (Path clip : clips) {
			if (lastModified(clip) >= deadline) {
				continue;
			}

			if (delete(clip)) {
				// 本体だけ消すと音声の .ogg が残ってしまうので、一緒に片付ける
				AudioTracks.discard(clip);
				removed++;
			}
		}

		if (removed > 0) {
			IfutoReplayClient.LOGGER.info("[ifuto-replay] {} 時間より古いクリップを {} 個消しました", keepHours, removed);
		}

		return removed;
	}

	private static boolean isClip(Path file) {
		String name = file.getFileName().toString();
		return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
	}

	private static long lastModified(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			// 取れないときは「新しい」扱いにして、うっかり消さないようにする
			return System.currentTimeMillis();
		}
	}

	private static boolean delete(Path file) {
		try {
			Files.deleteIfExists(file);
			return true;
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] {} を消せませんでした", file, e);
			return false;
		}
	}
}
