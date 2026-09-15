package com.ifuto.replay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.audio.AudioMode;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/ifuto-replay.json に保存する設定。
 *
 * <p>軽さに直結する項目（記録範囲・圧縮・キュー）と、将来の書き出し用の項目をまとめてある。
 */
@Environment(EnvType.CLIENT)
public class ReplayConfig {
	/** 既定の保存フォルダ名（ゲームディレクトリの下） */
	public static final String DEFAULT_SAVE_FOLDER = "ifuto-replay";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ReplayConfig instance;

	// --- いつ録るか ---

	/** ワールド/サーバーに入ったら自動で録画を始める */
	public boolean autoRecord = false;

	/** 自分の操作（C2S パケット）も記録する */
	public boolean recordClientPackets = true;

	/**
	 * クリップ方式（Medal みたいな「さっきの数秒をあとから保存」）。
	 *
	 * <p>入ったらすぐ録り始め、直近 {\@code clipSeconds} 秒ぶんだけを
	 * 保存用の一時ファイルに持ち続ける。「クリップを保存」を押した時点で
	 * そのぶんを1つの .ifreplay にまとめる。押さなければ消えるだけ（残らない）。
	 */
	public boolean clipMode = false;

	/** クリップとして残す長さ（秒）。実際は区間の都合でこれより少し長くなる */
	public int clipSeconds = 7200;

	/** 保存したクリップを残す時間（時間）。これより古い物は自動で消す。0 = 消さない */
	public int clipKeepHours = 24;

	/** クリップの一時ファイルの上限（MB）。0 = 書き込み速度から自動で決める */
	public int clipBufferMb = 0;

	/** KeepAlive / Ping などの通信維持用パケットを除外する */
	public boolean skipKeepAlive = true;

	/**
	 * 動的レジストリ（バイオーム・次元・エンチャントなど）の写しをファイルに残す。
	 *
	 * <p>あとでパケットを復元するには、録ったときとまったく同じレジストリが必要。
	 * これを残しておくと、別の世界に居る状態でも（録画ファイル単体で）再生できる。
	 * 録り始めに1回だけ書くので、録画中の負荷には影響しない。
	 */
	public boolean saveRegistries = true;

	// --- 軽さの調整 ---

	/** 保存時の圧縮 */
	public CompressionMode compression = CompressionMode.MAX;

	/** 書き込み待ちのキューに積めるパケット数（あふれた分は捨てて、ゲーム側は止めない） */
	public int queuePackets = 4096;

	/** 書き込み待ちの合計バイト数の上限（MB）。これを超えたら捨て始める */
	/** メモリに持っていい量（MB）。0 = 環境から自動で決める（推奨） */
	public int queuedMegaBytes = 0;

	/** 一定周期でファイルへ移す間隔（ミリ秒） */
	public int flushIntervalMs = 2000;

	/** 空き容量がこれを切ったら警告（MB）。0 = 監視しない */
	public int lowDiskSpaceMb = 1024;

	/** 空き容量がこれを切ったら録画を保存して強制停止（MB）。0 = 監視しない */
	public int criticalDiskSpaceMb = 256;

	/** 何ミリ秒おきにシーク用の目印を残すか（0 で作らない） */
	public int indexIntervalMs = 5000;

	/**
	 * **クライアントの内側でだけ** 起きたパーティクルも記録する。
	 *
	 * <p>ブロックを崩しているときの破片・足あと・Mod の演出などはパケットにならないので、
	 * これを記録しておかないと再生したときに出てこない。
	 */
	public boolean recordParticles = true;

	/**
	 * 書き出しの速さ（100 = 等倍速、200 = 2倍、400 = 4倍、0 = 最速）。
	 *
	 * <p>等倍速だと、絵と絵のあいだを本番と同じ時間だけ空ける。モーションブラーの
	 * 蓄積やテンポラル系のシェーダーなど「実時間」を見る Mod の効き方が本番と同じになる
	 * （そのぶん時間がかかる。1時間の録画なら1時間）。
	 *
	 * <p>倍率を上げるとそのぶん速く終わるが、それらの演出の効き方が変わる。
	 * 0（最速）は GPU が描き終わりしだい次へ進むので一番速い。
	 */
	public int exportSpeedPercent = 100;

	// --- 自動停止 ---

	/** ファイルサイズの上限（MB）。0 で無制限 */
	public int maxFileSizeMb = 0;

	/** 録画時間の上限（分）。0 で無制限 */
	public int maxDurationMinutes = 0;

	// --- 見た目・お知らせ ---

	/** 画面の隅に録画インジケータを出す */
	public boolean showIndicator = true;

	/** インジケータの位置 */
	public IndicatorPosition indicatorPosition = IndicatorPosition.TOP_LEFT;

	/** 録画の開始/停止をチャットでお知らせする */
	public boolean notifyChat = true;

	/**
	 * サーバーアドレスを * で隠す（プレビューと書き出し）。
	 *
	 * <p>既定は OFF。一度 ON にすると次回以降も引き継がれる（もちろん OFF に戻せる）。
	 */
	public boolean maskServerAddress = false;

	/** 隠すときに表示する文字（長さも中身もわからないようにする） */
	public static final String MASK = "********";

	/** 保存先フォルダ（ゲームディレクトリからの相対パス） */
	public String saveFolder = DEFAULT_SAVE_FOLDER;

	// --- 書き出し（次期実装。パケット再生→任意のFPS/解像度で動画にする工程で使う） ---

	/** 書き出しFPS */
	public int exportFps = 60;

	/** 書き出し解像度（横） */
	public int exportWidth = 1920;

	/** 書き出し解像度（縦） */
	public int exportHeight = 1080;

	/** 書き出しビットレート（kbps） */
	public int exportBitrateKbps = 20000;

	/**
	 * 途中から録り始めたとき、一緒に保存する地形の半径（チャンク）。
	 * 0 にすると保存しない（その場合、途中からの録画は再生できなくなる）
	 */
	public int snapshotRadius = 6;

	/** ffmpeg の実行ファイル（パスが通っていれば "ffmpeg" のままでOK） */
	public String ffmpegPath = "ffmpeg";

	// --- 音声 ---

	/**
	 * 音声をどう録るか。
	 *
	 * <p>音は「いま鳴っている物」をその場で取るしかないので、録画と同時に別ファイルへ書く。
	 * 取れない環境でも録画そのものは必ず残る（音声なしになるだけ）。
	 *
	 * <p>既定は **Minecraft だけ**。取り出せない環境では PC 全体の音へ回す
	 * （まったく録れないよりマシなので。設定で「録らない」にもできる）。
	 */
	public AudioMode audioMode = AudioMode.MINECRAFT;

	/** 音声のビットレート（kbps）。Opus なら 96 もあれば十分 */
	public int audioBitrateKbps = 96;

	/** 音声を取る機器（空欄 = 自動で探す）。Windows は `audio=…` に入る名前、Linux は pactl の名前 */
	public String audioDevice = "";

	/**
	 * VC Mod（Simple Voice Chat）の声も別に録る。
	 *
	 * <p>声だけを別ファイルにしておくと、書き出しのときに「声を入れる / 入れない」を選べる。
	 */
	public boolean recordVoiceChat = true;

	public static ReplayConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	public static Path getPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(IfutoReplayClient.MOD_ID + ".json");
	}

	/** 録画の保存先フォルダ（絶対パス） */
	public static Path getSaveDirectory() {
		ReplayConfig config = get();
		String folder = config.saveFolder;

		if (folder == null || folder.isBlank()) {
			folder = DEFAULT_SAVE_FOLDER;
		}

		return FabricLoader.getInstance().getGameDir().resolve(folder);
	}

	/**
	 * 画面に出すサーバーアドレス。
	 *
	 * <p>隠す設定が ON なら、長さも中身もわからないように全部 * にする
	 * （プレビュー・書き出しの両方でこれを通す）。
	 */
	/**
	 * 環境から「メモリに持っていい量」を決める。
	 *
	 * <p>Minecraft に割り当てられたヒープの 1/16 を目安にする（8〜256MB）。
	 * 少なすぎると書き込みが追いつかずパケットを捨てることになり、
	 * 多すぎるとほかの動作を圧迫するので、どちらにも寄りすぎない値にしている。
	 */
	public static int autoQueueMegaBytes() {
		long heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
		long value = heapMb / 16L;

		if (value < 8L) {
			return 8;
		}

		return (int) Math.min(256L, value);
	}

	/** 実際に使う量（0 のときは自動） */
	public int queueMegaBytes() {
		return this.queuedMegaBytes > 0 ? this.queuedMegaBytes : autoQueueMegaBytes();
	}

	public String displayAddress(@Nullable String address) {
		if (address == null || address.isBlank()) {
			return "-";
		}

		return this.maskServerAddress ? MASK : address;
	}

	public static ReplayConfig load() {
		Path path = getPath();
		ReplayConfig config = new ReplayConfig();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ReplayConfig loaded = GSON.fromJson(reader, ReplayConfig.class);

				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] {} が読めなかったので既定値で続行", path, e);
			}
		}

		config.validate();
		instance = config;
		config.save();
		return config;
	}

	public void save() {
		Path path = getPath();
		this.validate();

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] {} を書き込めなかった", path, e);
		}
	}

	public void resetToDefaults() {
		ReplayConfig defaults = new ReplayConfig();
		this.autoRecord = defaults.autoRecord;
		this.recordClientPackets = defaults.recordClientPackets;
		this.skipKeepAlive = defaults.skipKeepAlive;
		this.compression = defaults.compression;
		this.queuePackets = defaults.queuePackets;
		this.queuedMegaBytes = defaults.queuedMegaBytes;
		this.flushIntervalMs = defaults.flushIntervalMs;
		this.lowDiskSpaceMb = defaults.lowDiskSpaceMb;
		this.criticalDiskSpaceMb = defaults.criticalDiskSpaceMb;
		this.indexIntervalMs = defaults.indexIntervalMs;
		this.recordParticles = defaults.recordParticles;
		this.exportSpeedPercent = defaults.exportSpeedPercent;
		this.maxFileSizeMb = defaults.maxFileSizeMb;
		this.maxDurationMinutes = defaults.maxDurationMinutes;
		this.showIndicator = defaults.showIndicator;
		this.indicatorPosition = defaults.indicatorPosition;
		this.notifyChat = defaults.notifyChat;
		this.saveRegistries = defaults.saveRegistries;
		this.maskServerAddress = defaults.maskServerAddress;
		this.saveFolder = defaults.saveFolder;
		this.exportFps = defaults.exportFps;
		this.exportWidth = defaults.exportWidth;
		this.exportHeight = defaults.exportHeight;
		this.exportBitrateKbps = defaults.exportBitrateKbps;
		this.ffmpegPath = defaults.ffmpegPath;
		this.snapshotRadius = defaults.snapshotRadius;
		this.audioMode = defaults.audioMode;
		this.audioBitrateKbps = defaults.audioBitrateKbps;
		this.audioDevice = defaults.audioDevice;
		this.recordVoiceChat = defaults.recordVoiceChat;
		this.clipMode = defaults.clipMode;
		this.clipSeconds = defaults.clipSeconds;
		this.clipKeepHours = defaults.clipKeepHours;
		this.clipBufferMb = defaults.clipBufferMb;
	}

	/** 手書き編集や古いファイルで壊れていても落ちないように丸める */
	public void validate() {
		if (this.compression == null) {
			this.compression = CompressionMode.OFF;
		}

		if (this.indicatorPosition == null) {
			this.indicatorPosition = IndicatorPosition.TOP_LEFT;
		}

		this.queuePackets = clampStrict(this.queuePackets, 256, 65536, 4096);
		this.queuedMegaBytes = clampStrict(this.queuedMegaBytes, 0, 1024, 0);
		this.flushIntervalMs = clampStrict(this.flushIntervalMs, 100, 60_000, 2000);
		this.lowDiskSpaceMb = clampStrict(this.lowDiskSpaceMb, 0, 1_048_576, 1024);
		this.criticalDiskSpaceMb = clampStrict(this.criticalDiskSpaceMb, 0, 1_048_576, 256);
		this.indexIntervalMs = clamp(this.indexIntervalMs, 0, 600000, 5000);
		this.maxFileSizeMb = clamp(this.maxFileSizeMb, 0, 1_000_000, 0);
		this.maxDurationMinutes = clamp(this.maxDurationMinutes, 0, 100_000, 0);
		this.exportFps = clampStrict(this.exportFps, 1, 480, 60);
		this.exportWidth = clampStrict(this.exportWidth, 16, 16384, 1920);
		this.exportHeight = clampStrict(this.exportHeight, 16, 16384, 1080);
		this.exportBitrateKbps = clampStrict(this.exportBitrateKbps, 100, 2_000_000, 20000);
		this.exportSpeedPercent = clamp(this.exportSpeedPercent, 0, 4000, 100);
		this.snapshotRadius = clampStrict(this.snapshotRadius, 0, 32, 6);

		if (this.saveFolder == null || this.saveFolder.isBlank()) {
			this.saveFolder = DEFAULT_SAVE_FOLDER;
		}

		if (this.ffmpegPath == null) {
			this.ffmpegPath = "ffmpeg";
		}

		if (this.audioMode == null) {
			this.audioMode = AudioMode.OFF;
		}

		this.audioBitrateKbps = clampStrict(this.audioBitrateKbps, 32, 512, 96);
		this.clipSeconds = clampStrict(this.clipSeconds, 5, 600, 30);
		this.clipKeepHours = clampStrict(this.clipKeepHours, 0, 720, 24);
		this.clipBufferMb = clampStrict(this.clipBufferMb, 0, 65536, 0);

		if (this.audioDevice == null) {
			this.audioDevice = "";
		}
	}

	/** 0 は「無制限」「無効」の意味で使う項目が多いので、0 だけはそのまま通す */
	private static int clamp(int value, int min, int max, int fallback) {
		if (value == 0) {
			return 0;
		}

		return clampStrict(value, min, max, fallback);
	}

	/** 0 を認めない版（範囲外は既定値にする） */
	private static int clampStrict(int value, int min, int max, int fallback) {
		if (value < min) {
			return fallback;
		}

		return Math.min(max, value);
	}
}
