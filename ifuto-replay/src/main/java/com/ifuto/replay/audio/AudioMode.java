package com.ifuto.replay.audio;

/**
 * 音声をどう録るか。
 *
 * <p>音は「録っているときにしか存在しない」ので、再生や書き出しのときに取り直すことはできない。
 * だから録画と同時に別ファイルへ書いておき、書き出しのときに一緒に詰める。
 */
public enum AudioMode {
	/** 音は録らない（いままでどおり） */
	OFF("off", true),

	/**
	 * Minecraft の音だけ。
	 *
	 * <p>Minecraft が鳴らしている音を OpenAL のループバックで受け取るので、
	 * ほかのアプリの音は入らない。VC Mod（Simple Voice Chat / Plasmo Voice）は
	 * **それぞれ別の出力機器を開く** ので、こちらには入らない。
	 */
	MINECRAFT("minecraft", false),

	/**
	 * PC 全体の音。
	 *
	 * <p>OS の「いま鳴っている音」を ffmpeg で取る。
	 * Minecraft 以外の音も、VC Mod の音も入る（Windows ならステレオミキサー、
	 * Linux ならモニター、macOS なら仮想デバイスが必要）。
	 */
	SYSTEM("system", true);

	private final String id;
	private final boolean supported;

	AudioMode(String id, boolean supported) {
		this.id = id;
		this.supported = supported;
	}

	public String id() {
		return this.id;
	}

	/** 音声を録る設定か */
	public boolean records() {
		return this != OFF;
	}

	/** この版で実際に使えるか（未対応の物は設定に出さない） */
	public boolean supported() {
		return this.supported;
	}

	public String translationKey() {
		return "ifuto-replay.config.audio_mode." + this.id;
	}

	public AudioMode next() {
		AudioMode[] values = available();
		int index = 0;

		for (int i = 0; i < values.length; i++) {
			if (values[i] == this) {
				index = i;
				break;
			}
		}

		return values[(index + 1) % values.length];
	}

	/** 設定画面に並べる物（未対応を除く） */
	public static AudioMode[] available() {
		AudioMode[] all = values();
		java.util.List<AudioMode> list = new java.util.ArrayList<>();

		for (AudioMode mode : all) {
			if (mode.supported()) {
				list.add(mode);
			}
		}

		return list.toArray(new AudioMode[0]);
	}
}
