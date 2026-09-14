package com.ifuto.replay.audio;

import com.ifuto.replay.IfutoReplayClient;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC11;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 取り出した音を **実際に鳴らす** 口。
 *
 * <p>Minecraft の音をループバック装置へ回すと、そのままでは何も聞こえなくなる。
 * そこで別に実機（ふだんの出力機器）を開き、取り出した音をそのまま流して聞こえるようにする。
 *
 * <p>OpenAL のコンテキストは「スレッドごと」なので、{@link #open()} はゲーム側のスレッドで作り、
 * 鳴らす直前に {@link #attach()} を（書き込みスレッドで）呼んで切り替える。
 */
public final class Speaker {
	private static final int BUFFER_COUNT = 4;

	private final long device;
	private final long context;
	private final int source;
	private final int[] buffers;
	private final Deque<Integer> free = new ArrayDeque<>();

	private Speaker(long device, long context, int source, int[] buffers) {
		this.device = device;
		this.context = context;
		this.source = source;
		this.buffers = buffers;
	}

	/** 実機を開いて、鳴らす用意をする（この時点ではまだこのスレッドに結びつけない） */
	public static @Nullable Speaker open(int sampleRate) {
		long device = ALC11.alcOpenDevice((ByteBuffer) null);

		if (device == 0L) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音を鳴らす機器を開けませんでした");
			return null;
		}

		long context = ALC11.alcCreateContext(device, (IntBuffer) null);

		if (context == 0L) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 音を鳴らすためのコンテキストを作れませんでした");
			ALC11.alcCloseDevice(device);
			return null;
		}

		ALC11.alcMakeContextCurrent(context);
		int source = AL11.alGenSources();
		int[] buffers = new int[BUFFER_COUNT];
		AL11.alGenBuffers(buffers);
		Speaker speaker = new Speaker(device, context, source, buffers);

		// 無音を詰めて先に並べておく（途切れさせないため）
		for (int buffer : buffers) {
			speaker.fill(buffer, new short[0]);
		}

		AL11.alSourceQueueBuffers(source, buffers);
		AL11.alSourcePlay(source);

		// コンテキストは「作ったスレッド」から離しておく（あとで鳴らすスレッドが使う）
		ALC11.alcMakeContextCurrent(0L);
		return speaker;
	}

	/** このスレッドで鳴らす（書き込みスレッドの最初に呼ぶ） */
	public void attach() {
		ALC11.alcMakeContextCurrent(this.context);
	}

	/**
	 * 1かたまり（20ms）を鳴らす。
	 *
	 * <p>追いつかないときは古い物から捨てる（遅れてズレていくよりマシ）。
	 */
	public void play(short[] chunk) {
		int processed = AL11.alGetSourcei(this.source, AL11.AL_BUFFERS_PROCESSED);

		if (processed > 0) {
			int[] done = new int[processed];
			AL11.alSourceUnqueueBuffers(this.source, done);

			for (int buffer : done) {
				this.free.add(buffer);
			}
		}

		Integer buffer = this.free.poll();

		if (buffer == null) {
			return;
		}

		this.fill(buffer, chunk);
		AL11.alSourceQueueBuffers(this.source, new int[] {buffer});

		if (AL11.alGetSourcei(this.source, AL11.AL_SOURCE_STATE) != AL11.AL_PLAYING) {
			AL11.alSourcePlay(this.source);
		}
	}

	private void fill(int buffer, short[] chunk) {
		if (chunk.length == 0) {
			AL11.alBufferData(buffer, AL11.AL_FORMAT_STEREO16, new short[2], MinecraftAudioCapture.SAMPLE_RATE);
			return;
		}

		AL11.alBufferData(buffer, AL11.AL_FORMAT_STEREO16, chunk, MinecraftAudioCapture.SAMPLE_RATE);
	}

	/** 片づける（元のコンテキストはゲーム側が持っているので、ここでは触らない） */
	public void close() {
		AL11.alSourceStop(this.source);
		AL11.alDeleteSources(this.source);
		AL11.alDeleteBuffers(this.buffers);
		ALC11.alcMakeContextCurrent(0L);
		ALC11.alcDestroyContext(this.context);
		ALC11.alcCloseDevice(this.device);
	}
}
