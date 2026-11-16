package com.ifuto.fastplace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FastPlace / Scaffold の設定。config/ifuto-fastplace.json に保存される。
 *
 * <p>モジュールはすべてここに状態を持つ（GUI から直接いじる）。</p>
 */
public final class FastPlaceConfig {
	public static final int MIN_INTERVAL_MS = 20;
	public static final int MAX_INTERVAL_MS = 500;
	public static final int DEFAULT_INTERVAL_MS = 55;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FastPlaceConfig instance;

	// ---- FastPlace ----
	public boolean enabled = true;
	public int intervalMs = DEFAULT_INTERVAL_MS;
	public boolean hudCps = true;

	// ---- Scaffold (God Bridge) ----
	/** モジュール ON/OFF。ブロックを持っていない / 画面を開いている間は自動で休む */
	public boolean scaffoldEnabled = false;
	/** ブリッジ中はジャンプ入力を落とす（God Bridge はジャンプしない） */
	public boolean scaffoldBlockJump = true;
	/** カメラだけ前を向いたままにする（OFF にすると視点も一緒に下を向く） */
	public boolean scaffoldKeepView = true;
	/** 人間モデル（サッケード・振戦・設置リズム・たまの外れ値）を使う */
	public boolean scaffoldHumanize = true;
	/** 設置時に腕を振る */
	public boolean scaffoldSwing = true;
	/** 設置ペースの倍率（0.6 = ゆっくり / 1.4 = 速め）。実際の間隔は距離で決まる */
	public double scaffoldCadence = 1.0;

	public static FastPlaceConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		Path path = getPath();
		FastPlaceConfig cfg = new FastPlaceConfig();
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					cfg = GSON.fromJson(reader, FastPlaceConfig.class);
				}
			}
		} catch (Exception e) {
			FastPlaceMod.LOGGER.warn("[ifuto-fastplace] 設定の読み込みに失敗。既定値で続行します", e);
			cfg = new FastPlaceConfig();
		}
		if (cfg == null) {
			cfg = new FastPlaceConfig();
		}
		cfg.clamp();
		instance = cfg;
	}

	public void save() {
		Path path = getPath();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			FastPlaceMod.LOGGER.warn("[ifuto-fastplace] 設定の保存に失敗しました", e);
		}
	}

	private void clamp() {
		if (intervalMs < MIN_INTERVAL_MS) {
			intervalMs = MIN_INTERVAL_MS;
		}
		if (intervalMs > MAX_INTERVAL_MS) {
			intervalMs = MAX_INTERVAL_MS;
		}
		if (Double.isNaN(scaffoldCadence) || scaffoldCadence < 0.6) {
			scaffoldCadence = 0.6;
		}
		if (scaffoldCadence > 1.4) {
			scaffoldCadence = 1.4;
		}
	}

	public static Path getPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("ifuto-fastplace.json");
	}

	public static Path getSaveDirectory() {
		return getPath().getParent();
	}
}
