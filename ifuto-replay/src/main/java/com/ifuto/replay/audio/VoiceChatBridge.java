package com.ifuto.replay.audio;

import java.nio.file.Path;

/**
 * VC Mod（Simple Voice Chat）とのつなぎ目。
 *
 * <p>このクラスは **VC Mod のクラスを一切持たない**。VC Mod 側のプラグイン（入っているときだけ
 * 読み込まれる）が {@link #offer(short[])} を呼ぶだけなので、VC Mod が無い環境でもこの Mod は普通に動く。
 */
public final class VoiceChatBridge {
	private static final VoiceChatBridge INSTANCE = new VoiceChatBridge();

	/** VC Mod が入っていて、音声を渡してもらえる状態か */
	private static volatile boolean available;

	private final VoiceChatCapture capture = new VoiceChatCapture();

	private VoiceChatBridge() {
	}

	public static VoiceChatBridge get() {
		return INSTANCE;
	}

	/** VC Mod 側のプラグインが読み込まれたときに呼ばれる */
	public static void markAvailable() {
		available = true;
	}

	public static boolean isAvailable() {
		return available;
	}

	public boolean start(Path output, int bitrateKbps, String ffmpegPath) {
		return available && this.capture.start(output, bitrateKbps, ffmpegPath);
	}

	public void stop() {
		this.capture.stop();
	}

	/** 録り続けたまま、書き出し先を次へ移す */
	public boolean rotate(Path output, int bitrateKbps, String ffmpegPath) {
		return available && this.capture.rotate(output, bitrateKbps, ffmpegPath);
	}

	public boolean isRunning() {
		return this.capture.isRunning();
	}

	/** VC Mod から届いた声（48kHz モノラル 20ms） */
	public void offer(short[] frame) {
		this.capture.offer(frame);
	}
}
