package com.ifuto.replay.export;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.mixin.NativeImageAccessor;
import com.ifuto.replay.playback.ReplayPlayback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

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
		/** 絵は出し切った。ffmpeg がまとめ終わるのを別スレッドで待っている */
		FINISHING,
		DONE,
		CANCELLED,
		FAILED
	}

	private static final int BYTES_PER_PIXEL = 4;

	/** バニラの FPS 上限スライダーの最大値（260）。書き出し中はここまで上げる */
	private static final int MAX_FPS_CAP = 260;

	/** 書き出し前の表示設定（終わったら必ず戻す） */
	private int savedMaxFps;
	private boolean savedVsync;
	private boolean displayUncapped;
	private boolean soundMuted;
	private double savedMasterVolume = 1.0;

	private final MinecraftClient client;
	private final ReplayPlayback playback;
	private final ExportOptions options;
	private final long startMs;
	private final long frameStepMs;
	private final int totalFrames;
	private final int originalFramebufferWidth;
	private final int originalFramebufferHeight;

	private Process process;
	private OutputStream ffmpegInput;
	private String encoderName = "libx264";
	private int frameSize;
	/**
	 * 使い回す置き場2枚（描画側と書き込み側で受け渡す）。無くなったら
	 * 描画側が少し待つ（= ため込まない。2枚ぶんより遅れない）。
	 */
	private final BlockingQueue<byte[]> emptyStages = new ArrayBlockingQueue<>(2);
	private final BlockingQueue<Frame> filledStages = new ArrayBlockingQueue<>(2);
	/** 書き込み係への「おしまい」の合図（絵なし） */
	private static final Frame END_OF_STREAM = new Frame(null, new byte[0]);

	/**
	 * 書き込み係への受け渡し1枚（絵 + 置き場）。
	 *
	 * <p>画素の複写も書き込み係がやる。描画側は「置き場を取って絵と一緒に渡す」
	 * だけなので、数十MBの複写で描画が止まらない。絵は最大2枚しか
	 * ためない（置き場がなければ描画側が待つため）。
	 */
	private record Frame(@Nullable NativeImage image, byte[] stage) {
	}
	private Thread writerThread;
	/** 書き込み係が詰まった理由（なければ null。描画側が拾って止める） */
	private volatile String writerError;
	private int frameIndex;
	private boolean captureRequested;

	/** 「いまの時刻の絵」を取り込むのを待っている（取り込むまでは時刻を進めない） */
	private boolean awaitingCapture;
	// 終了待ちは別スレッドから変えるので volatile
	private volatile State state = State.RUNNING;
	private volatile String failureMessage = "";
	private long startedAtMs;
	private volatile long lastProgressMs;

	/** どこに時間がかかっているか（描画スレッドだけが触る。単位ns） */
	private long pumpNs;
	private long tickNs;
	private long renderNs;
	private long captureNs;
	private long blockNs;
	private long lastAdvanceEndNs;
	private int capturedFrames;

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

	/**
	 * 書き出し中に、**ゲームの時計を1フレームぶん進ませたい長さ**（ミリ秒）。
	 * 書き出しをしていないとき / 等倍速のときは 0（= 何もしない）。
	 *
	 * <p>書き出しを速くすると、実時間で進む量が「録画の時間」より少なくなる。
	 * するとモーションブラーの蓄積や・時間で減衰する演出・シェーダーの時刻など
	 * 「前のフレームからどれだけ経ったか」を見る物が、本番よりゆっくり進んでしまう。
	 * そこで書き出し中だけ、ゲームに渡す経過時間を **録画のフレーム間隔** に
	 * 固定する（= 蓄積の速度を上げる）。こうすると何倍速で出しても、
	 * 「録画の1秒ぶん」に進む量が本番と同じになる。
	 */
	public static long desiredFrameMillis() {
		ReplayExporter exporter = active;

		if (exporter == null || exporter.state != State.RUNNING) {
			return 0L;
		}

		// 等倍速のときは実時間と同じなので、わざわざ差し替えない
		if (exporter.options.speedPercent() == 100) {
			return 0L;
		}

		return exporter.frameStepMs;
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

	/**
	 * 進捗が止まりっぱなしなら中断する（世界が消えた／絵が返ってこないときの保険）。
	 * シェーダーの組み直しで最初の1枚が遅くなることがあるので、猶予は長めに見る。
	 */
	public void checkStalled(long timeoutMs) {
		if (this.state != State.RUNNING) {
			return;
		}

		if (System.currentTimeMillis() - this.lastProgressMs > timeoutMs) {
			this.failWith(Text.translatable("ifuto-replay.export.error_stalled").getString());
		}
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
		// パイプ入力の受け口を広げる（4K60 などで詰まらせない。入力ごとに付ける物）
		command.add("-thread_queue_size");
		command.add("512");
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

		// 音声は「録っているときに録った別ファイル」。実時間より先に進むので、
		// 書き出す範囲のぶんだけ切り出して、絵と同じ長さにする
		List<Path> audio = this.audioInputs();
		double startSec = this.startMs / 1000.0;
		double lengthSec = this.totalFrames * this.frameStepMs / 1000.0;

		for (Path track : audio) {
			command.add("-ss");
			command.add(seconds(startSec));
			command.add("-t");
			command.add(seconds(lengthSec));
			command.add("-i");
			command.add(track.toAbsolutePath().toString());
		}

		// GPU が使えれば GPU で（x264 medium とほぼ同じ画質で数倍速い）。無ければ CPU
		String encoder = "libx264";
		boolean hardware = false;

		if (this.options.hardwareAccel()) {
			String found = EncoderProbe.select(this.options.ffmpegPath());

			if (found != null) {
				encoder = found;
				hardware = true;
			}
		}

		this.encoderName = encoder;

		IfutoReplayClient.LOGGER.info("[ifuto-replay] 書き出しのエンコーダー: {}（{}）",
				encoder, hardware ? "GPU" : "CPU");

		// NVENC + 対応 ffmpeg なら、ピクセル変換も GPU 側でやる（CPU の swscale 1〜3ms/枚を消す）
		boolean cudaScale = hardware && EncoderProbe.supportsCudaScale(this.options.ffmpegPath(), encoder);

		if (cudaScale) {
			command.add("-vf");
			command.add("hwupload_cuda,scale_cuda=format=yuv420p");
		}

		command.add("-c:v");
		command.add(encoder);

		if (!hardware) {
			command.add("-preset");
			command.add(this.options.x264Preset());
		}

		if (!cudaScale) {
			command.add("-pix_fmt");
			command.add("yuv420p");
		}
		command.add("-b:v");
		command.add(this.options.bitrateKbps() + "k");

		if (audio.size() == 1) {
			command.add("-map");
			command.add("0:v:0");
			command.add("-map");
			command.add("1:a:0");
			command.add("-c:a");
			command.add("aac");
			command.add("-b:a");
			command.add("192k");
		} else if (audio.size() >= 2) {
			// 2本（Minecraft の音 + VC の声）を混ぜる。どちらも同じ時刻から始まっている
			command.add("-filter_complex");
			command.add("[1:a][2:a]amix=inputs=2:duration=longest:normalize=0[a]");
			command.add("-map");
			command.add("0:v:0");
			command.add("-map");
			command.add("[a]");
			command.add("-c:a");
			command.add("aac");
			command.add("-b:a");
			command.add("192k");
		} else {
			command.add("-an");
		}

		if (!audio.isEmpty()) {
			// 絵が先に終わったらそこで切る（音だけ長く残さない）
			command.add("-shortest");
		}

		// 目次を先頭に置く（ブラウザ等ですぐ再生・シークできる。画質・速度は変わらない）
		command.add("-movflags");
		command.add("+faststart");

		command.add(this.options.output().toString());

		this.process = new ProcessBuilder(command).start();
		this.ffmpegInput = this.process.getOutputStream();
		this.frameSize = width * height * BYTES_PER_PIXEL;
		this.emptyStages.clear();
		this.filledStages.clear();
		this.emptyStages.offer(new byte[this.frameSize]);
		this.emptyStages.offer(new byte[this.frameSize]);
		this.writerThread = new Thread(this::writeLoop, "ifuto-replay-export-writer");
		this.writerThread.setDaemon(true);
		this.writerThread.start();

		// 解像度を切り替える（ウィンドウの大きさは変えない）
		this.client.getWindow().setFramebufferWidth(width);
		this.client.getWindow().setFramebufferHeight(height);
		this.client.onResolutionChanged();

		this.startedAtMs = System.currentTimeMillis();
		this.lastProgressMs = this.startedAtMs;
		this.playback.setPaused(false);
		this.uncapDisplay();
		active = this;
	}

	/**
	 * 書き出し中だけ、描画の足かせを外す（終わったら {@link #restore()} で戻す）。
	 *
	 * <p>書き出しは「描画1回 = 動画1枚」で進むので、描画が速いほど速く終わる。
	 * ふだんの FPS 上限や垂直同期はここでは足かせにしかならないので、書き出し中だけ
	 * 上限いっぱい（260）・垂直同期なしにする。swap interval も直接切る
	 * （ドライバ側の強制 vsync まではどうにもならない）。
	 */
	private void uncapDisplay() {
		try {
			GameOptions options = this.client.options;
			this.savedMaxFps = options.getMaxFps().getValue();
			this.savedVsync = options.getEnableVsync().getValue();
			options.getMaxFps().setValue(MAX_FPS_CAP);
			options.getEnableVsync().setValue(false);
			GLFW.glfwSwapInterval(0);
			this.displayUncapped = true;

			// 等倍速以外は音が密になりすぎるので黙らせる（等倍速は見ながら聞ける）。
			// 主音量を 0 にする（止めるだけだと新しく鳴る音が漏れる）
			if (this.options.speedPercent() != 100) {
				this.savedMasterVolume = this.client.options.getSoundVolumeOption(SoundCategory.MASTER).getValue();
				this.client.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(0.0);
				this.client.getSoundManager().stopAll();
				this.soundMuted = true;
			}
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 表示設定の一時変更に失敗しました（書き出しは続けます）", t);
		}
	}

	/** 一緒に詰める音声（1本または2本。無ければ空） */
	private List<Path> audioInputs() {
		List<Path> audio = this.options.audio();

		if (audio == null) {
			return List.of();
		}

		List<Path> usable = new ArrayList<>();

		for (Path track : audio) {
			if (track != null && Files.isRegularFile(track) && !usable.contains(track)) {
				usable.add(track);
			}
		}

		return usable;
	}

	/** ffmpeg に渡す秒（小数点以下3けた。小数点を落とすとずれるので） */
	private static String seconds(double value) {
		return String.format(Locale.ROOT, "%.3f", Math.max(0.0, value));
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

		// 「時間に依存する演出」を正しくするために、待つことがある（下の説明を見てください）
		this.pace();

		long target = this.startMs + this.frameIndex * this.frameStepMs;
		long t0 = System.nanoTime();
		// その場で追いつく（tick 待ちだと1秒20枚しか進めない）
		this.playback.advanceTo(target);
		long t1 = System.nanoTime();
		// 世界も1コマ進める（実体・パーティクル・時刻。1コマ1tickは普段と同じ比率）
		this.client.tick();
		long t2 = System.nanoTime();
		this.pumpNs += t1 - t0;
		this.tickNs += t2 - t1;
		this.frameIndex++;
		this.awaitingCapture = true;
		this.lastAdvanceEndNs = System.nanoTime();
	}

	/**
	 * 書き出しの速さを **設定どおり** に保つ（必要なぶんだけ待つ）。100% なら等倍速、
	 * 200% なら2倍の速さ、0（最速）なら何も待たない。
	 *
	 * <p>ふつうの書き出しは「GPU が描き終わりしだい次の時刻へ」進むので、絵と絵のあいだの
	 * **実時間** がバラバラになる。すると「直前の絵との差」や「実時間の経過」を見る類の
	 * 演出（モーションブラーの蓄積、テンポラル系のシェーダー、時間で減衰する演出など）が
	 * 本番と違う効き方をしてしまう。
	 *
	 * <p>ここで待つと、描かれる間隔が本来のフレーム間隔と同じになるので、それらの Mod も
	 * 本番どおりに動く。遅いGPUでは待ち時間がゼロになるだけ（比率は自動で落ちる）なので、
	 * そのときは「速い書き出し」と同じになる。
	 */
	private void pace() {
		int speed = this.options.speedPercent();

		if (speed <= 0) {
			// 最速: 待たない（GPU が描き終わりしだい次へ進む）
			return;
		}

		long ideal = this.startedAtMs + this.frameIndex * this.frameStepMs * 100L / speed;
		long wait = ideal - System.currentTimeMillis();

		if (wait <= 0L) {
			// 遅れているので待たない（借金は次のフレームに持ち越さない）
			return;
		}

		try {
			Thread.sleep(Math.min(wait, 500L));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 描画の後に呼ぶ。いまの絵を取り込むよう頼む */
	public void onFrameRendered() {
		if (this.state == State.RUNNING && !this.playback.isSeeking()) {
			this.lastProgressMs = System.currentTimeMillis();
		}

		if (this.state != State.RUNNING || this.captureRequested || !this.awaitingCapture) {
			return;
		}

		// 時刻が追いついていない絵は捨てる（追いついた次の描画で取り込む）
		if (this.playback.isSeeking()) {
			return;
		}

		this.awaitingCapture = false;
		this.captureRequested = true;

		long r0 = System.nanoTime();

		if (this.lastAdvanceEndNs != 0L) {
			this.renderNs += r0 - this.lastAdvanceEndNs;
			this.lastAdvanceEndNs = 0L;
		}

		try {
			ScreenshotRecorder.takeScreenshot(this.client.getFramebuffer(), this::onCaptured);
		} catch (Throwable t) {
			this.captureRequested = false;
			this.fail(t);
		} finally {
			this.captureNs += System.nanoTime() - r0;
		}
	}

	/** 取り込めた絵を ffmpeg へ渡す */
	private void onCaptured(NativeImage image) {
		this.captureRequested = false;
		this.lastProgressMs = System.currentTimeMillis();

		if (this.state != State.RUNNING) {
			image.close();
			return;
		}

		// 絵の始末は writeFrame の先（渡せたら書き込み係、渡せなければ writeFrame）
		try {
			this.writeFrame(image);
			this.capturedFrames++;

			if (this.capturedFrames % 600 == 0) {
				this.logProgress("書き出し中");
			}
		} catch (Throwable t) {
			this.fail(t);
		}
	}

	/**
	 * 進み具合と内訳を出す（pump=追いつき tick=世界 render=描画 cap=取込
	 * wait=書込待ち。wait が大きいときはエンコードが追いついていない）。
	 */
	private void logProgress(String prefix) {
		int done = Math.max(1, this.capturedFrames);
		long elapsedMs = Math.max(1L, System.currentTimeMillis() - this.startedAtMs);
		double fps = done * 1000.0 / elapsedMs;
		IfutoReplayClient.LOGGER.info("[ifuto-replay] {}",
				String.format(Locale.ROOT, "%s %d/%d (%.1ffps, pump %.2fms tick %.2fms render %.2fms cap %.2fms wait %.2fms)",
						prefix, done, this.totalFrames, fps,
						this.pumpNs / 1_000_000.0 / done,
						this.tickNs / 1_000_000.0 / done,
						this.renderNs / 1_000_000.0 / done,
						this.captureNs / 1_000_000.0 / done,
						this.blockNs / 1_000_000.0 / done));
	}

	/**
	 * 取り込めた絵を、書き込み係へ渡す。
	 *
	 * <p>ここでは置き場を取って絵と一緒に渡すだけ。画素の複写は
	 * 書き込み係がやる（数十MBの複写で描画を止めないため）。
	 * 絵の始末は、渡せたら書き込み係、渡せなければここ。
	 */
	private void writeFrame(NativeImage image) throws IOException {
		int width = image.getWidth();
		int height = image.getHeight();

		// 書き出し中にウィンドウを動かすとフレームバッファの大きさが勝手に戻ってしまう
		if (width != this.options.width() || height != this.options.height()) {
			image.close();
			throw new IOException("解像度が変わってしまいました（" + width + "x" + height + "）"
					+ "書き出し中はウィンドウの大きさを変えないでください");
		}

		if (width * height * BYTES_PER_PIXEL != this.frameSize) {
			image.close();
			throw new IOException("絵の大きさが合いません");
		}

		byte[] stage;

		try {
			long w0 = System.nanoTime();
			stage = this.emptyStages.take();
			this.blockNs += System.nanoTime() - w0;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			image.close();
			throw new IOException("書き出しを中断しました", e);
		}

		try {
			this.filledStages.put(new Frame(image, stage));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			image.close();
			this.emptyStages.offer(stage);
			throw new IOException("書き出しを中断しました", e);
		}
	}

	/**
	 * 画素を番地から直接読む。読めたら {@code true}。
	 *
	 * <p>スクリーンショットの絵は RGBA（メモリの並びも R・G・B・A）なので、
	 * そのまま流すだけで ffmpeg の {@code rgba} 入力になる。変換も確保も無い。
	 */
	private boolean readPixelsFast(NativeImage image, byte[] stage) {
		try {
			if (image.getFormat() != NativeImage.Format.RGBA) {
				return false;
			}

			// NativeImage は final なので Object を経由する（実行時は mixin が口を付ける）
			long pointer = ((NativeImageAccessor) (Object) image).ifutoReplay$getPointer();

			if (pointer == 0L) {
				return false;
			}

			MemoryUtil.memByteBuffer(pointer, stage.length).get(stage);
			return true;
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 画素の直読みに失敗したので複写に切り替えます", t);
			return false;
		}
	}

	/** ふだん通らない形の絵用の複写経路（1枚だけ int 配列を作る） */
	private void readPixelsSlow(NativeImage image, byte[] stage) {
		int[] pixels = image.copyPixelsAbgr();
		// int（ABGR）をそのままメモリに流すと RGBA の並びになる（リトルエンディアン前提）
		ByteBuffer.wrap(stage).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);
	}

	/**
	 * 書き込み係（別スレッド）。描画側は絵ができたら置き場と一緒に渡すだけ。
	 *
	 * <p>画素の複写とパイプへの書き込みはここでやる。どちらも止まることがある
	 * （数十MBの複写・エンコーダーの都合）。描画側で待つとそのまま固まった
	 * ように見えるので、待つのはここだけ。置き場は2枚しかないので、
	 * 遅れてもため込まない。
	 */
	private void writeLoop() {
		try {
			while (true) {
				Frame frame = this.filledStages.take();

				if (frame.image() == null) {
					return;
				}

				NativeImage image = frame.image();
				byte[] stage = frame.stage();

				try {
					if (!this.readPixelsFast(image, stage)) {
						this.readPixelsSlow(image, stage);
					}

					this.ffmpegInput.write(stage);
				} finally {
					image.close();
					this.emptyStages.offer(stage);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			// ここで直接止めると描画に触ることになるので、理由だけ置いて描画側に任せる
			this.writerError = t.getMessage() == null ? t.toString() : t.getMessage();
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 書き込み係が止まりました", t);
		} finally {
			try {
				this.ffmpegInput.flush();
				this.ffmpegInput.close();
			} catch (IOException e) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg への書き込みを閉じられませんでした", e);
			}
		}
	}

	/** 書き込み係が詰まっていたら、描画側で止める（描画に触るのはここだけ） */
	private void checkWriter() {
		String error = this.writerError;

		if (error != null && this.state == State.RUNNING) {
			this.failWith(error);
		}
	}

	/** 正常に終わらせる（ffmpeg の終了待ちは別スレッドで。描画を止めないため） */
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

		this.logProgress("書き出し完了");
		this.restore();
		this.state = State.FINISHING;
		active = null;

		Thread waiter = new Thread(this::waitForFfmpeg, "ifuto-replay-export-finish");
		waiter.setDaemon(true);
		waiter.start();
	}

	/** 中断する（仕上げ待ちのあいだも止められる） */
	public void cancel() {
		if (this.state != State.RUNNING && this.state != State.FINISHING) {
			return;
		}

		boolean running = this.state == State.RUNNING;
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

		if (running) {
			this.restore();
		}
	}

	private void failWith(String message) {
		IfutoReplayClient.LOGGER.error("[ifuto-replay] 書き出しを中断しました: {}", message);
		this.failureMessage = message;
		this.state = State.FAILED;
		active = null;

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

	/**
	 * ffmpeg が書き終わるのを待って、終わり方を見て DONE / FAILED を決める（別スレッド）。
	 *
	 * <p>ここでは描画に触れない（解像度は {@link #finish()} ですでに戻してある）。
	 * 中断されていたら何もしない（画面はすでに閉じている）。
	 */
	private void waitForFfmpeg() {
		Process process = this.process;

		if (this.state != State.FINISHING) {
			return;
		}

		if (process == null) {
			this.state = State.DONE;
			return;
		}

		try {
			boolean ended = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

			if (this.state != State.FINISHING) {
				return;
			}

			if (!ended) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg が終わらないので強制終了します");
				process.destroy();
				this.failureMessage = Text.translatable("ifuto-replay.export.error_stalled").getString();
				this.state = State.FAILED;
				return;
			}

			int exit = process.exitValue();

			if (exit != 0) {
				// 終了コードを見ないと、壊れた動画を「できた」と言ってしまう
				IfutoReplayClient.LOGGER.error("[ifuto-replay] ffmpeg が異常終了しました (exit {}, encoder {})",
						exit, this.encoderName);
				this.failureMessage = Text.translatable("ifuto-replay.export.error_ffmpeg", exit).getString()
						+ Text.translatable("ifuto-replay.export.error_encoder", this.encoderName).getString();
				this.state = State.FAILED;
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.failureMessage = Text.translatable("ifuto-replay.export.error_unknown").getString();
			this.state = State.FAILED;
			return;
		}

		this.state = State.DONE;
	}

	/** 解像度と表示設定を元に戻す */
	private void restore() {
		this.client.getWindow().setFramebufferWidth(this.originalFramebufferWidth);
		this.client.getWindow().setFramebufferHeight(this.originalFramebufferHeight);
		this.client.onResolutionChanged();

		if (this.displayUncapped) {
			this.displayUncapped = false;

			try {
				GameOptions options = this.client.options;
				options.getMaxFps().setValue(this.savedMaxFps);
				options.getEnableVsync().setValue(this.savedVsync);
				GLFW.glfwSwapInterval(this.savedVsync ? 1 : 0);
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 表示設定を元に戻せませんでした", t);
			}
		}

		if (this.soundMuted) {
			this.soundMuted = false;

			try {
				this.client.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(this.savedMasterVolume);
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音量を元に戻せませんでした", t);
			}
		}
	}
}
