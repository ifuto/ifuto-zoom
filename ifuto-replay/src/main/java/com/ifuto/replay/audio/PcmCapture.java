package com.ifuto.replay.audio;

import com.ifuto.replay.IfutoReplayClient;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 生の PCM（16bit）を ffmpeg へ流して Opus にしてもらう。
 *
 * <p>Java 側にエンコーダを持たないので、音をファイルにする仕事はいつものように ffmpeg に任せる。
 * 使う側は {@link #write(short[])} を呼ぶだけ。**ゲームの音を止めない** ように、
 * 書き込みで詰まっても一定時間で諦める。
 */
public final class PcmCapture {
	private final Process process;
	private final OutputStream stdin;
	private final ByteBuffer buffer;
	private final Deque<String> tail = new ArrayDeque<>();
	private volatile boolean closed;

	private PcmCapture(Process process, int capacitySamples) {
		this.process = process;
		this.stdin = process.getOutputStream();
		this.buffer = ByteBuffer.allocate(capacitySamples * 2).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** ffmpeg を起動して、PCM を受け取る用意をする */
	public static @Nullable PcmCapture start(String ffmpegPath, Path output, int sampleRate, int channels,
											 int bitrateKbps, int framesPerWrite) {
		List<String> command = List.of(ffmpegPath,
				"-y",
				"-hide_banner",
				"-loglevel", "warning",
				"-f", "s16le",
				"-ar", String.valueOf(sampleRate),
				"-ac", String.valueOf(channels),
				"-i", "-",
				"-c:a", "libopus",
				"-b:a", bitrateKbps + "k",
				"-f", "ogg",
				output.toAbsolutePath().toString());

		try {
			Process process = new ProcessBuilder(command).start();
			PcmCapture capture = new PcmCapture(process, framesPerWrite);
			capture.drain();
			return capture;
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声（PCM）を録れませんでした", e);
			return null;
		}
	}

	/** 1フレームぶんを書き込む（16bit リトルエンディアン） */
	public void write(short[] samples) {
		if (this.closed) {
			return;
		}

		try {
			this.buffer.clear();
			this.buffer.asShortBuffer().put(samples);
			this.stdin.write(this.buffer.array(), 0, samples.length * 2);
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音声（PCM）の書き込みに失敗しました", e);
			this.closed = true;
		}
	}

	/** 入力を閉じて ffmpeg の終了を待つ（パイプを閉じれば最後まで書いて終わってくれる） */
	public void stop() {
		if (this.closed) {
			return;
		}

		this.closed = true;

		try {
			this.stdin.close();
		} catch (IOException ignored) {
			// すでに終わっているだけ
		}

		try {
			if (!this.process.waitFor(5L, TimeUnit.SECONDS)) {
				this.process.destroyForcibly();
				this.process.waitFor(2L, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.process.destroyForcibly();
		}
	}

	public boolean isRunning() {
		return !this.closed && this.process.isAlive();
	}

	/** ffmpeg が残した最後の言葉（こけた理由） */
	public String errorTail() {
		synchronized (this.tail) {
			return String.join(" / ", this.tail);
		}
	}

	/** 標準エラーを読み捨てる（読まないと ffmpeg が詰まる） */
	private void drain() {
		Thread thread = new Thread(() -> {
			try (InputStream in = this.process.getErrorStream();
				 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;

				while ((line = reader.readLine()) != null) {
					synchronized (this.tail) {
						this.tail.addLast(line);

						if (this.tail.size() > 8) {
							this.tail.removeFirst();
						}
					}
				}
			} catch (IOException ignored) {
				// 終わりかけに読めなくなるのは普通
			}
		}, "ifuto-replay-pcm");

		thread.setDaemon(true);
		thread.start();
	}
}
