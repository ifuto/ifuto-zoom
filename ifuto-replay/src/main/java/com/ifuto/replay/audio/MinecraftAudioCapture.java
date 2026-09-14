package com.ifuto.replay.audio;

import com.ifuto.replay.IfutoReplayClient;
import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;

import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * **Minecraft の音だけ** を録る（OpenAL のループバック）。
 *
 * <p>しくみ: Minecraft が音を鳴らす先の装置を「ループバック装置」に差し替える。
 * ループバック装置は音を外へ出さず、**こちらが取り出すまで溜めておく**。
 * 取り出した音はファイルへ書くのと同時に、実機（{@link Speaker}）へ流して
 * ふだんどおり聞こえるようにする。
 *
 * <p>こうすると Minecraft が鳴らした物だけが録れる。VC Mod（Simple Voice Chat /
 * Plasmo Voice）は **別の出力機器を開く** ので入らない（声は別途 {@link VoiceChatCapture} が録る）。
 *
 * <p>安全のために次を守る:
 * <ul>
 *     <li>**録っていないときは何もしない**（装置の差し替えは録り始めたときだけ）</li>
 *     <li>OpenAL がこの仕組みに対応していなければ、そもそも使わない</li>
 *     <li>録り終えたら音の再読み込みをして、かならず元の鳴り方へ戻す</li>
 * </ul>
 */
public final class MinecraftAudioCapture {
	/** OpenAL から取り出す音の周波数 */
	public static final int SAMPLE_RATE = 48000;

	/** 1回に取り出す長さ（20ms） */
	private static final int FRAMES_PER_CHUNK = SAMPLE_RATE / 50;

	private static final int CHANNELS = 2;

	/** 1ティックで取り出す上限（詰まりを防ぐ） */
	private static final int MAX_CHUNKS_PER_TICK = 8;

	/** 1ティックで追いつく上限（ms）。遅れても一気に取り出さない */
	private static final long MAX_CATCHUP_MS = 100L;

	private static final long MIN_BYTES = 4096L;

	private static final MinecraftAudioCapture INSTANCE = new MinecraftAudioCapture();

	/** 装置を差し替える合図（Mixin から見る） */
	private final AtomicBoolean armed = new AtomicBoolean();

	/** 差し替えた先の装置（Mixin が教えてくれる） */
	private volatile long devicePointer;

	private final BlockingQueue<short[]> pending = new LinkedBlockingQueue<>(512);
	private final ShortBuffer renderBuffer = BufferUtils.createShortBuffer(FRAMES_PER_CHUNK * CHANNELS);

	private volatile boolean running;
	private volatile @Nullable PcmCapture capture;
	private volatile @Nullable Speaker speaker;
	private volatile @Nullable Thread writer;
	private volatile long startedAtMs;
	private volatile @Nullable Path output;
	private volatile long renderedFrames;
	private volatile String failure = "";
	private static volatile boolean unsupported;

	private MinecraftAudioCapture() {
	}

	public static MinecraftAudioCapture get() {
		return INSTANCE;
	}

	/**
	 * この環境でループバックが使えるか。
	 *
	 * <p>OpenAL の拡張（`ALC_SOFT_loopback`）が無ければ使えない。一度失敗したら次からは使わない。
	 */
	public static boolean isSupported() {
		if (unsupported) {
			return false;
		}

		try {
			boolean present = ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback");

			if (!present) {
				unsupported = true;
			}

			return present;
		} catch (Throwable t) {
			unsupported = true;
			return false;
		}
	}

	/** いま装置を差し替える段取りか（Mixin から見る） */
	public boolean isArmed() {
		return this.armed.get();
	}

	/** 差し替え先の装置を開けた（Mixin から呼ばれる） */
	public void setDevice(long device) {
		this.devicePointer = device;
	}

	public boolean isRunning() {
		return this.running;
	}

	public String failure() {
		return this.failure;
	}

	/** 失敗の理由を1回だけ取り出す（録画中にダメになったとき用） */
	public String pollFailure() {
		String message = this.failure;

		if (message == null || message.isEmpty()) {
			return "";
		}

		this.failure = "";
		return message;
	}

	/** 音を取り出せているか（装置の差し替えが済んでいるか） */
	public boolean isDeviceReady() {
		return this.devicePointer != 0L;
	}

	/**
	 * 録り始める。
	 *
	 * <p>装置の差し替えは「音の作り直し」（バニラの再読み込み）で行う。
	 * Minecraft が音を鳴らす装置その物を差し替えるので、ここだけが外部に影響する操作。
	 * 失敗したら何も変えずに false を返す（呼び出し側は音声なしで続ける）。
	 */
	public synchronized boolean start(MinecraftClient client, Path output, int bitrateKbps, String ffmpegPath) {
		if (this.running) {
			return false;
		}

		if (client == null) {
			this.failure = "client is not ready";
			return false;
		}

		if (!isSupported()) {
			this.failure = "ALC_SOFT_loopback に対応していません";
			return false;
		}

		Speaker opened = Speaker.open(SAMPLE_RATE);

		if (opened == null) {
			this.failure = "音を鳴らす機器を開けませんでした";
			return false;
		}

		PcmCapture started = PcmCapture.start(ffmpegPath, output, SAMPLE_RATE, CHANNELS, bitrateKbps,
				FRAMES_PER_CHUNK * CHANNELS);

		if (started == null) {
			this.failure = "ffmpeg を起動できませんでした";
			opened.close();
			return false;
		}

		this.failure = "";
		this.capture = started;
		this.speaker = opened;
		this.pending.clear();
		this.renderedFrames = 0L;
		this.startedAtMs = System.currentTimeMillis();
		this.output = output;
		this.devicePointer = 0L;
		this.armed.set(true);
		this.running = true;

		// 装置を差し替えるために、いちど音を作り直してもらう
		reloadSounds(client);

		Thread worker = new Thread(this::writeLoop, "ifuto-replay-mc-audio");
		this.writer = worker;
		worker.setDaemon(true);
		worker.start();
		IfutoReplayClient.LOGGER.info("[ifuto-replay] Minecraft の音をループバックで録ります");
		return true;
	}

	/** 録り終える。音はかならず元の鳴り方へ戻す。 */
	public synchronized void stop(MinecraftClient client) {
		if (!this.running) {
			return;
		}

		this.running = false;
		this.armed.set(false);
		Thread worker = this.writer;
		this.writer = null;

		if (worker != null) {
			worker.interrupt();

			try {
				worker.join(2000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		PcmCapture current = this.capture;
		this.capture = null;

		if (current != null) {
			current.stop();
		}

		Speaker opened = this.speaker;
		this.speaker = null;

		if (opened != null) {
			opened.close();
		}

		this.pending.clear();
		this.devicePointer = 0L;

		Path written = this.output;
		this.output = null;

		if (written != null && !isUsable(written)) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] Minecraft の音が取れていないので {} を消します",
					written.getFileName());

			try {
				Files.deleteIfExists(written);
			} catch (Exception ignored) {
				// 消せなくても録画の本体には影響しない
			}
		}

		// 元の装置で鳴らし直す（これを忘れると音が出なくなる）
		reloadSounds(client);
		IfutoReplayClient.LOGGER.info("[ifuto-replay] Minecraft の音の録音を終えました");
	}

	/**
	 * クライアント tick ごとに呼ぶ。溜まっている音を取り出して列に積む。
	 *
	 * <p>Minecraft の音のコンテキストはゲーム側のスレッドにあるので、取り出しもそこでやる。
	 * 書く仕事は別スレッドへ回す。
	 */
	public void renderTick() {
		if (!this.running) {
			return;
		}

		long device = this.devicePointer;

		if (device == 0L) {
			// 差し替わらないまま時間が過ぎたら、この環境では使えないと判断する
			if (System.currentTimeMillis() - this.startedAtMs > 3000L && this.failure.isEmpty()) {
				this.failure = "Minecraft の音を取り出せませんでした";
				unsupported = true;
			}

			return;
		}

		long expected = (System.currentTimeMillis() - this.startedAtMs) * SAMPLE_RATE / 1000L;
		long missing = expected - this.renderedFrames;

		if (missing <= 0L) {
			return;
		}

		long limit = Math.min(missing, MAX_CATCHUP_MS * SAMPLE_RATE / 1000L);
		int chunks = 0;

		while (limit >= FRAMES_PER_CHUNK && chunks < MAX_CHUNKS_PER_TICK) {
			short[] chunk = new short[FRAMES_PER_CHUNK * CHANNELS];
			this.renderBuffer.clear();
			SOFTLoopback.alcRenderSamplesSOFT(device, this.renderBuffer, FRAMES_PER_CHUNK);
			this.renderBuffer.get(chunk);

			if (!this.pending.offer(chunk)) {
				break;
			}

			this.renderedFrames += FRAMES_PER_CHUNK;
			limit -= FRAMES_PER_CHUNK;
			chunks++;
		}
	}

	/** 溜まった音をファイルと実機へ流す */
	private void writeLoop() {
		Speaker opened = this.speaker;

		if (opened != null) {
			opened.attach();
		}

		while (this.running) {
			short[] chunk;

			try {
				chunk = this.pending.poll(100L, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}

			if (chunk == null) {
				continue;
			}

			PcmCapture current = this.capture;

			if (current != null) {
				current.write(chunk);
			}

			Speaker speaker = this.speaker;

			if (speaker != null) {
				speaker.play(chunk);
			}
		}
	}

	/** バニラのやり方で音を作り直す（装置の差し替えと、元に戻すのに使う） */
	private static void reloadSounds(MinecraftClient client) {
		if (client == null) {
			return;
		}

		try {
			client.getSoundManager().reloadSounds();
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音の再読み込みに失敗しました", t);
		}
	}

	/** 録れた音声が使える大きさか（小さすぎたら捨てる） */
	public static boolean isUsable(@Nullable Path file) {
		if (file == null) {
			return false;
		}

		try {
			return Files.isRegularFile(file) && Files.size(file) >= MIN_BYTES;
		} catch (Exception e) {
			return false;
		}
	}

	/** 無音の1かたまり（実機が途切れないように詰める物） */
	static short[] silenceChunk() {
		short[] chunk = new short[FRAMES_PER_CHUNK * CHANNELS];
		Arrays.fill(chunk, (short) 0);
		return chunk;
	}
}
