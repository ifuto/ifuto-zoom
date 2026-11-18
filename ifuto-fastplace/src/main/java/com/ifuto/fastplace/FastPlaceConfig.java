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

	// ---- Aim Assist (melee) ----
	/** モジュール ON/OFF */
	public boolean aimAssistEnabled = false;
	/** アシストの強さ（0.2〜1.0）。速度上限と立ち上がりに効く */
	public double aimAssistStrength = 0.75;
	/** 狙い始める視野（全角）[deg] */
	public double aimAssistFov = 70.0;
	/** 人間モデル（反応時間・振戦・予測）を使う */
	public boolean aimAssistHumanize = true;
	/** 攻撃ボタンを押している間だけ効かせる（既定 OFF: クリックで掴む方式） */
	public boolean aimAssistOnlyWhileAttacking = false;
	/** 剣・斧を持っているときだけ効かせる */
	public boolean aimAssistWeaponsOnly = true;
	/** 相手の速度を先読みして補償する */
	public boolean aimAssistPredict = true;
	/** 左クリックで相手を掴む（1 回のクリックで一定時間追う） */
	public boolean aimAssistClickAcquire = true;
	/** クリックで掴むときの当たり判定の倍率（実際の判定は広がらない） */
	public double aimAssistClickBoxScale = 1.2;
	/** クリックで掴んでから追い続ける時間 [ms] */
	public double aimAssistStickMs = 3000.0;
	/** プレイヤーだけを狙う（OFF なら Mob も） */
	public boolean aimAssistPlayersOnly = true;
	/** 移動方向と逆向きにずれる量 [deg]（人間の自己運動による残差） */
	public double aimAssistStrafeBiasDeg = 1.2;
	/** 追従の滑らかさ（1.0 標準 / 大きいほどゆっくり滑らか） */
	public double aimAssistSmoothing = 1.0;

	// ---- ClickCrystal ----
	/** モジュール ON/OFF */
	public boolean clickCrystalEnabled = false;
	/** 置いてから爆破するまでの間隔 [ms] */
	public int clickCrystalIntervalMs = 45;
	/** 1 回のクリックで連続爆破を続ける時間 [ms] */
	public int clickCrystalBurstMs = 1200;
	/** 終わったら元のスロットに戻す */
	public boolean clickCrystalRestoreSlot = true;
	/** 狙う最大距離 [blocks] */
	public double clickCrystalRange = 4.5;

	// ---- Auto Tool ----
	/** モジュール ON/OFF */
	public boolean autoToolEnabled = false;
	/** 人間モデル（判断時間・練習・たまの取り違え）を使う */
	public boolean autoToolHumanize = true;
	/** 掘っている間だけ持ち替える（OFF なら見ただけで持ち替え） */
	public boolean autoToolOnlyWhileMining = true;
	/** 残り耐久が少ない道具を避ける */
	public boolean autoToolAvoidBreaking = false;

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
		if (Double.isNaN(aimAssistStrength) || aimAssistStrength < 0.2) {
			aimAssistStrength = 0.2;
		}
		if (aimAssistStrength > 1.0) {
			aimAssistStrength = 1.0;
		}
		if (Double.isNaN(aimAssistFov) || aimAssistFov < 15.0) {
			aimAssistFov = 15.0;
		}
		if (aimAssistFov > 170.0) {
			aimAssistFov = 170.0;
		}
		if (Double.isNaN(aimAssistClickBoxScale) || aimAssistClickBoxScale < 1.0) {
			aimAssistClickBoxScale = 1.0;
		}
		if (aimAssistClickBoxScale > 1.6) {
			aimAssistClickBoxScale = 1.6;
		}
		if (Double.isNaN(aimAssistStickMs) || aimAssistStickMs < 500.0) {
			aimAssistStickMs = 500.0;
		}
		if (aimAssistStickMs > 10000.0) {
			aimAssistStickMs = 10000.0;
		}
		if (Double.isNaN(aimAssistStrafeBiasDeg)) {
			aimAssistStrafeBiasDeg = 1.2;
		}
		if (aimAssistStrafeBiasDeg < -4.0) {
			aimAssistStrafeBiasDeg = -4.0;
		}
		if (aimAssistStrafeBiasDeg > 4.0) {
			aimAssistStrafeBiasDeg = 4.0;
		}
		if (Double.isNaN(aimAssistSmoothing) || aimAssistSmoothing < 0.4) {
			aimAssistSmoothing = 0.4;
		}
		if (aimAssistSmoothing > 2.5) {
			aimAssistSmoothing = 2.5;
		}
		clickCrystalIntervalMs = Math.max(20, Math.min(300, clickCrystalIntervalMs));
		clickCrystalBurstMs = Math.max(100, Math.min(5000, clickCrystalBurstMs));
		if (Double.isNaN(clickCrystalRange) || clickCrystalRange < 1.0) {
			clickCrystalRange = 1.0;
		}
		if (clickCrystalRange > 6.0) {
			clickCrystalRange = 6.0;
		}
	}

	public static Path getPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("ifuto-fastplace.json");
	}

	public static Path getSaveDirectory() {
		return getPath().getParent();
	}
}
