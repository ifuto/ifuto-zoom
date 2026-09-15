package com.ifuto.replay.audio;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 録画と同時に音声を録る（外の ffmpeg に任せる）。
 *
 * <p>音は「いま鳴っている物」をその場で取るしかない。書き出しのときにはもう存在しないので、
 * **録画している間に別ファイルへ書いておく**。本体の録画とは別のファイルなので、
 * 音声が取れなくても録画そのものは必ず残る。
 *
 * <p>ffmpeg が無い / 機器が無い / こけた、のどれでも **録画は止めない**。
 * そのときは理由をログとチャットに出して、音声なしで続ける。
 */
public final class AudioRecorder {
	/** これより小さいファイルは「取れていない」とみなして捨てる */
	private static final long MIN_BYTES = 4096L;

	private volatile @Nullable Process process;
	private volatile @Nullable Path output;
	private volatile String failure = "";
	private volatile boolean stopping;

	public AudioRecorder() {
	}

	/**
	 * 録音を始める。
	 *
	 * @return 録音を始められたら true（設定が「録らない」のときも false。失敗とは区別して {@link #failure()} を見る）
	 */
	public synchronized boolean start(Path target) {
		if (this.process != null) {
			return false;
		}

		ReplayConfig config = ReplayConfig.get();
		AudioMode mode = config.audioMode == null ? AudioMode.OFF : config.audioMode;

		if (!mode.records()) {
			return false;
		}

		if (!mode.supported()) {
			this.failure = "not supported yet: " + mode.id();
			return false;
		}

		this.failure = "";
		this.stopping = false;
		this.output = target;

		String device = config.audioDevice;

		if (device == null || device.isBlank()) {
			device = SystemAudioCapture.autoDevice(config.ffmpegPath);
		}

		List<String> command = SystemAudioCapture.command(config.ffmpegPath, config.audioBitrateKbps,
				device, target);
		IfutoReplayClient.LOGGER.info("[ifuto-replay] 音声を録ります: {}", String.join(" ", command));

		try {
			Process started = new ProcessBuilder(command).start();
			this.process = started;
			this.drain(started);

			// 起動直後に死んでいないか少しだけ待つ（機器名まちがいはすぐ落ちる）
			Thread.sleep(300L);

			if (!started.isAlive()) {
				int code = started.exitValue();
				this.failure = "ffmpeg exited with " + code;
				this.process = null;
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声を録れませんでした（{}）", this.failure);
				deleteEmpty(target);
				return false;
			}

			return true;
		} catch (IOException e) {
			this.failure = String.valueOf(e.getMessage());
			this.process = null;
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声を録れませんでした（ffmpeg を起動できません）", e);
			deleteEmpty(target);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.process = null;
			return false;
		}
	}

	/**
	 * 書き出し先を次のファイルへ移す（PC 全体の音むけ）。
	 *
	 * <p>こちらは ffmpeg が機器を直接読んでいるので、**入れ替えの瞬間だけ音が途切れる**
	 * （Minecraft の音のように「開いたまま出し先だけ変える」ことはできない）。
	 * 途切れるのは保存した瞬間の一瞬だけで、それ以前の音はファイルに残る。
	 */
	public synchronized boolean rotate(Path target) {
		if (this.process == null) {
			return false;
		}

		this.stop();
		return this.start(target);
	}

	/** 録音を止めて、取れた音声を残す（取れていなければ捨てる） */
	public synchronized void stop() {
		Process current = this.process;
		this.process = null;

		if (current == null) {
			return;
		}

		this.stopping = true;

		try {
			// 'q' を渡すと ffmpeg は最後まで書いてから終わる
			try (OutputStream stdin = current.getOutputStream()) {
				stdin.write('q');
				stdin.flush();
			} catch (IOException ignored) {
				// すでに終わっているだけ
			}

			if (!current.waitFor(5L, TimeUnit.SECONDS)) {
				current.destroyForcibly();
				current.waitFor(2L, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			current.destroyForcibly();
		}

		Path file = this.output;
		this.output = null;

		if (file != null) {
			if (!isUsable(file)) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声が取れていないので {} を消します", file.getFileName());
				deleteEmpty(file);
			} else {
				IfutoReplayClient.LOGGER.info("[ifuto-replay] 音声を {} に保存しました", file.getFileName());
			}
		}
	}

	public boolean isRunning() {
		Process current = this.process;
		return current != null && current.isAlive();
	}

	/** 失敗の理由（無ければ空） */
	public String failure() {
		return this.failure;
	}

	/** 失敗の理由を1回だけ取り出す（録画中に ffmpeg が落ちたとき用） */
	public String pollFailure() {
		String message = this.failure;

		if (message == null || message.isEmpty()) {
			return "";
		}

		this.failure = "";
		return message;
	}

	/** 使える音声ファイルか（あとで ffmpeg に渡して大丈夫か） */
	private static boolean isUsable(Path file) {
		try {
			return Files.isRegularFile(file) && Files.size(file) >= MIN_BYTES;
		} catch (IOException e) {
			return false;
		}
	}

	private static void deleteEmpty(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// 消せなくても録画の本体には影響しない
		}
	}

	/**
	 * ffmpeg の標準エラーを読み捨てる。
	 *
	 * <p>読まないと ffmpeg が詰まって止まるので必須。最後の数行だけ覚えておいて、
	 * 異常終了したときの理由にする。
	 */
	private void drain(Process process) {
		Thread thread = new Thread(() -> {
			Deque<String> tail = new ArrayDeque<>();

			try (InputStream in = process.getErrorStream();
				 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;

				while ((line = reader.readLine()) != null) {
					tail.addLast(line);

					if (tail.size() > 8) {
						tail.removeFirst();
					}
				}
			} catch (IOException ignored) {
				// 終わりかけに読めなくなるのは普通
			}

			try {
				int code = process.waitFor();

				if (code != 0 && !this.stopping) {
					String reason = String.join(" / ", tail);
					this.failure = reason.isEmpty() ? "ffmpeg exited with " + code : reason;
					IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声の録音が止まりました: {}", this.failure);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "ifuto-replay-audio");

		thread.setDaemon(true);
		thread.start();
	}
}
