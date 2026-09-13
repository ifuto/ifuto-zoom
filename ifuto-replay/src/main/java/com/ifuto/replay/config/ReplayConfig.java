package com.ifuto.replay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.replay.IfutoReplayClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

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

	/** KeepAlive / Ping などの通信維持用パケットを除外する */
	public boolean skipKeepAlive = true;

	// --- 軽さの調整 ---

	/** 保存時の圧縮 */
	public CompressionMode compression = CompressionMode.OFF;

	/** 書き込み待ちのキューに積めるパケット数（あふれた分は捨てて、ゲーム側は止めない） */
	public int queuePackets = 4096;

	/** 書き込み待ちの合計バイト数の上限（MB）。これを超えたら捨て始める */
	public int queuedMegaBytes = 32;

	/** 何ミリ秒おきにシーク用の目印を残すか（0 で作らない） */
	public int indexIntervalMs = 5000;

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

	/** ffmpeg の実行ファイル（パスが通っていれば "ffmpeg" のままでOK） */
	public String ffmpegPath = "ffmpeg";

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
		this.indexIntervalMs = defaults.indexIntervalMs;
		this.maxFileSizeMb = defaults.maxFileSizeMb;
		this.maxDurationMinutes = defaults.maxDurationMinutes;
		this.showIndicator = defaults.showIndicator;
		this.indicatorPosition = defaults.indicatorPosition;
		this.notifyChat = defaults.notifyChat;
		this.saveFolder = defaults.saveFolder;
		this.exportFps = defaults.exportFps;
		this.exportWidth = defaults.exportWidth;
		this.exportHeight = defaults.exportHeight;
		this.exportBitrateKbps = defaults.exportBitrateKbps;
		this.ffmpegPath = defaults.ffmpegPath;
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
		this.queuedMegaBytes = clampStrict(this.queuedMegaBytes, 4, 1024, 32);
		this.indexIntervalMs = clamp(this.indexIntervalMs, 0, 600000, 5000);
		this.maxFileSizeMb = clamp(this.maxFileSizeMb, 0, 1_000_000, 0);
		this.maxDurationMinutes = clamp(this.maxDurationMinutes, 0, 100_000, 0);
		this.exportFps = clampStrict(this.exportFps, 1, 480, 60);
		this.exportWidth = clampStrict(this.exportWidth, 16, 16384, 1920);
		this.exportHeight = clampStrict(this.exportHeight, 16, 16384, 1080);
		this.exportBitrateKbps = clampStrict(this.exportBitrateKbps, 100, 2_000_000, 20000);

		if (this.saveFolder == null || this.saveFolder.isBlank()) {
			this.saveFolder = DEFAULT_SAVE_FOLDER;
		}

		if (this.ffmpegPath == null) {
			this.ffmpegPath = "ffmpeg";
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
