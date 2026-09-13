package com.ifuto.replay.export;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.playback.ReplayPlayback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 再生している世界を、好きな FPS / 解像度 / ビットレートで動画にする。
 *
 * <p>画面録画と違うのは「時間をこちらで決める」ところ。1フレームぶんの時刻だけ進めては
 * 描かせ、できた絵を ffmpeg に rawvideo で渡す。だから
 * ・60fps でも 30fps でも、あとから何度でも作り直せる
 * ・ウィンドウの大きさに関係なく 4K で出せる
 * ・倍速でもカクつかない（実時間と関係なく進めるので）
 *
 * <p>絵の取り込みはバニラのスクリーンショットの経路（{@link ScreenshotRecorder}）をそのまま借りる。
 * これなら GL の読み出し方が変わっても追随できるし、上下の反転もバニラがやってくれる。
 */
public final class ReplayExporter {
	public enum State {
		RUNNING,
		DONE,
		CANCELLED,
		FAILED
	}

	private static final int BYTES_PER_PIXEL = 4;

	private final MinecraftClient client;
	private final ReplayPlayback playback;
	private final ExportOptions options;
	private final long startMs;
	private final long frameStepMs;
	private final int totalFrames;
	private final int originalFramebufferWidth;
	private final int originalFramebufferHeight;
	private final boolean hudWasHidden;

	private Process process;
	private OutputStream ffmpegInput;
	private byte[] frameBytes;
	private int frameIndex;
	private boolean captureRequested;

	/** 「いまの時刻の絵」を取り込むのを待っている（取り込むまでは時刻を進めない） */
	private boolean awaitingCapture;
	private State state = State.RUNNING;
	private String failureMessage = "";
	private long startedAtMs;

	/** いま動いている書き出し（なければ null） */
	private static volatile ReplayExporter active;

	public ReplayExporter(MinecraftClient client, ReplayPlayback playback, ExportOptions options) {
		this.client = client;
		this.playback = playback;
		this.options = options;
		this.startMs = Math.max(0L, options.startMs());
		this.frameStepMs = Math.max(1L, Math.round(1000.0 / options.fps()));
		long spanMs = Math.max(0L, options.endMs() - this.startMs);
		this.totalFrames = (int) Math.max(1L, Math.ceil(spanMs / (double) this.frameStepMs));
		this.originalFramebufferWidth = client.getWindow().getFramebufferWidth();
		this.originalFramebufferHeight = client.getWindow().getFramebufferHeight();
		this.hudWasHidden = client.options.hudHidden;
	}

	/** 描画の前にフレーム1回だけ呼ばれる（書き出し中でなければ何もしない） */
	public static void onBeforeRenderFrame() {
		ReplayExporter exporter = active;

		if (exporter != null) {
			exporter.onBeforeFrame();
		}
	}

	/** 描画が終わったあとにフレーム1回だけ呼ばれる */
	public static void onAfterRenderFrame() {
		ReplayExporter exporter = active;

		if (exporter != null) {
			exporter.onFrameRendered();
		}
	}

	/** いま動いている書き出し */
	public static ReplayExporter getActive() {
		return active;
	}

	public State state() {
		return this.state;
	}

	public String failureMessage() {
		return this.failureMessage;
	}

	public int frameIndex() {
		return this.frameIndex;
	}

	public int totalFrames() {
		return this.totalFrames;
	}

	public double fps() {
		return this.options.fps();
	}

	public Path output() {
		return this.options.output();
	}

	/** 経過時間（ms） */
	public long elapsedMs() {
		return System.currentTimeMillis() - this.startedAtMs;
	}

	/**
	 * 書き出しを始める。解像度に合わせてフレームバッファを広げ、ffmpeg を起動する。
	 */
	public void start() throws IOException {
		int width = this.options.width();
		int height = this.options.height();

		Files.createDirectories(this.options.output().getParent());

		List<String> command = new ArrayList<>();
		command.add(this.options.ffmpegPath());
		command.add("-y");
		command.add("-f");
		command.add("rawvideo");
		command.add("-pix_fmt");
		command.add("rgba");
		command.add("-s");
		command.add(width + "x" + height);
		command.add("-r");
		command.add(String.valueOf(this.options.fps()));
		command.add("-i");
		command.add("-");
		command.add("-an");
		command.add("-c:v");
		command.add("libx264");
		command.add("-preset");
		command.add("medium");
		command.add("-pix_fmt");
		command.add("yuv420p");
		command.add("-b:v");
		command.add(this.options.bitrateKbps() + "k");
		command.add(this.options.output().toString());

		this.process = new ProcessBuilder(command).start();
		this.ffmpegInput = this.process.getOutputStream();
		this.frameBytes = new byte[width * height * BYTES_PER_PIXEL];

		// 解像度を切り替える（ウィンドウの大きさは変えない）
		this.client.getWindow().setFramebufferWidth(width);
		this.client.getWindow().setFramebufferHeight(height);
		this.client.onResolutionChanged();

		// HUD は出さない（動画に要らないので）
		this.client.options.hudHidden = true;

		this.startedAtMs = System.currentTimeMillis();
		this.playback.setPaused(false);
		active = this;
	}

	/** 描画の前に呼ぶ。次のフレームの時刻へ進める */
	public void onBeforeFrame() {
		if (this.state != State.RUNNING || this.captureRequested) {
			return;
		}

		// 早送りが終わるまで / いまの絵を取り込むまでは、時刻を進めない
		if (this.playback.isSeeking() || this.awaitingCapture) {
			return;
		}

		if (this.frameIndex >= this.totalFrames) {
			this.finish();
			return;
		}

		long target = this.startMs + this.frameIndex * this.frameStepMs;
		this.playback.jumpTo(target);
		this.frameIndex++;
		this.awaitingCapture = true;
	}

	/** 描画の後に呼ぶ。いまの絵を取り込むよう頼む */
	public void onFrameRendered() {
		if (this.state != State.RUNNING || this.captureRequested || !this.awaitingCapture) {
			return;
		}

		// 時刻が追いついていない絵は捨てる（追いついた次の描画で取り込む）
		if (this.playback.isSeeking()) {
			return;
		}

		this.awaitingCapture = false;
		this.captureRequested = true;

		try {
			ScreenshotRecorder.takeScreenshot(this.client.getFramebuffer(), this::onCaptured);
		} catch (Throwable t) {
			this.captureRequested = false;
			this.fail(t);
		}
	}

	/** 取り込めた絵を ffmpeg へ渡す */
	private void onCaptured(NativeImage image) {
		this.captureRequested = false;

		if (this.state != State.RUNNING) {
			image.close();
			return;
		}

		try {
			this.writeFrame(image);
		} catch (Throwable t) {
			this.fail(t);
		} finally {
			image.close();
		}
	}

	private void writeFrame(NativeImage image) throws IOException {
		int width = image.getWidth();
		int height = image.getHeight();

		// 書き出し中にウィンドウを動かすとフレームバッファの大きさが勝手に戻ってしまう
		if (width != this.options.width() || height != this.options.height()) {
			throw new IOException("解像度が変わってしまいました（" + width + "x" + height + "）"
					+ "書き出し中はウィンドウの大きさを変えないでください");
		}

		int[] pixels = image.copyPixelsAbgr();
		byte[] bytes = this.frameBytes;

		if (bytes == null || bytes.length != width * height * BYTES_PER_PIXEL) {
			bytes = new byte[width * height * BYTES_PER_PIXEL];
			this.frameBytes = bytes;
		}

		// int（ABGR）をそのままメモリに流すと RGBA の並びになる（リトルエンディアン前提）
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);
		this.ffmpegInput.write(bytes);
	}

	/** 正常に終わらせる */
	public void finish() {
		if (this.state != State.RUNNING) {
			return;
		}

		try {
			if (this.ffmpegInput != null) {
				this.ffmpegInput.flush();
				this.ffmpegInput.close();
			}
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg への書き込みを閉じられませんでした", e);
		}

		this.waitForFfmpeg();
		this.restore();
		this.state = State.DONE;
		active = null;
	}

	/** 中断する */
	public void cancel() {
		if (this.state != State.RUNNING) {
			return;
		}

		this.state = State.CANCELLED;
		active = null;

		try {
			if (this.ffmpegInput != null) {
				this.ffmpegInput.close();
			}
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg の入力を閉じられませんでした", e);
		}

		if (this.process != null) {
			this.process.destroy();
		}

		this.restore();
	}

	private void fail(Throwable t) {
		IfutoReplayClient.LOGGER.error("[ifuto-replay] 書き出しに失敗しました", t);
		this.failureMessage = t.getMessage() == null ? t.toString() : t.getMessage();
		this.state = State.FAILED;
		active = null;

		if (this.process != null) {
			this.process.destroy();
		}

		this.restore();
	}

	private void waitForFfmpeg() {
		if (this.process == null) {
			return;
		}

		try {
			boolean ended = this.process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

			if (!ended) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg が終わらないので強制終了します");
				this.process.destroy();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 解像度とHUDを元に戻す */
	private void restore() {
		this.client.getWindow().setFramebufferWidth(this.originalFramebufferWidth);
		this.client.getWindow().setFramebufferHeight(this.originalFramebufferHeight);
		this.client.onResolutionChanged();
		this.client.options.hudHidden = this.hudWasHidden;
	}
}
