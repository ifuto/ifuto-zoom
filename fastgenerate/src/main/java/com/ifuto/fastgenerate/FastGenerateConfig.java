package com.ifuto.fastgenerate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FastGenerate の設定。config/ifuto-fastgenerate.json に保存される。
 *
 * <p>生成を速くするのは「先に作っておく」ことなので、どこまで先読みするか・
 * 同時にいくつ走らせるかを、重いデータパックでも壊れないように全部ここで調整できる。</p>
 */
public final class FastGenerateConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FastGenerateConfig instance;

	// ---- 基本 ----
	/** モジュール ON/OFF */
	public boolean enabled = true;
	/** ビューディスタンスに加えて先読みするチャンク数（0 で無効） */
	public int leadChunks = 6;
	/** 進行方向をどれだけ優先するか（0 = 方向を見ない同心円） */
	public double directionBias = 1.0;

	// ---- 並列度 ----
	/** 同時に走らせる生成の下限 */
	public int minWindow = 2;
	/** 同時に走らせる生成の上限（コア数に応じて自動で下がる） */
	public int maxWindow = 12;
	/** 1 tick に発行する要求の数（多いほどスパイクしやすい） */
	public int issuePerTick = 2;
	/** 同時に保持するチケット数の上限（メモリ保護） */
	public int maxTickets = 4096;

	// ---- 制御 ----
	/** これを超えたら生成を絞る（サーバーの 1 tick 作業時間）[ms] */
	public double targetMspt = 45.0;
	/** これを超えたら絞る（クライアントのフレーム時間）[ms] */
	public double targetFrameMs = 30.0;

	// ---- 起動時 ----
	/** max.bg.threads を CPU に合わせて調整する */
	public boolean tuneThreads = true;

	// ---- 表示 ----
	public boolean debug = false;

	public static FastGenerateConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				FastGenerateConfig loaded = GSON.fromJson(reader, FastGenerateConfig.class);
				instance = loaded == null ? new FastGenerateConfig() : loaded;
			} catch (IOException | RuntimeException e) {
				instance = new FastGenerateConfig();
			}
		} else {
			instance = new FastGenerateConfig();
		}
		instance.clamp();
		instance.save();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("ifuto-fastgenerate.json");
	}

	public void save() {
		clamp();
		try {
			Path path = path();
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException ignored) {
			// 設定が書けないだけで動作を止めない
		}
	}

	private void clamp() {
		leadChunks = Math.max(0, Math.min(24, leadChunks));
		directionBias = Math.max(0.0, Math.min(2.0, directionBias));
		minWindow = Math.max(1, Math.min(8, minWindow));
		maxWindow = Math.max(minWindow, Math.min(64, maxWindow));
		issuePerTick = Math.max(1, Math.min(8, issuePerTick));
		maxTickets = Math.max(256, Math.min(65536, maxTickets));
		targetMspt = Math.max(15.0, Math.min(200.0, targetMspt));
		targetFrameMs = Math.max(10.0, Math.min(200.0, targetFrameMs));
	}
}
