package com.ifuto.zoom.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.zoom.IfutoZoomClient;
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
 * config/ifuto-zoom.json に保存する設定。
 */
@Environment(EnvType.CLIENT)
public class ZoomConfig {
	public static final double HARD_MIN_ZOOM = 0.25D;
	public static final double MAX_CONFIGURABLE_ZOOM = 50.0D;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ZoomConfig instance;

	/** キーを押している間だけ or 押すたびに切り替わる（既定は前者） */
	public ZoomMode mode = ZoomMode.HOLD;

	/** キーを押したときの倍率 */
	public double defaultZoom = 3.0D;

	/** スクロールで縮小できる下限（上限は実質なし） */
	public double minZoom = 1.0D;

	/** スクロール1ノッチあたりの倍率 */
	public double scrollStep = 1.15D;

	/** ズーム中のスクロールを倍率変更に使うか */
	public boolean scrollToZoom = true;

	/** ズームを終えてもスクロールした倍率を維持するか */
	public boolean keepZoomLevel = false;

	/** ズームの速度カーブ */
	public EasingType easing = EasingType.EASE_OUT;

	/** 「カーブの強さ」用の指数。1=リニア、2=二次、3=三次… */
	public double easingPower = 2.0D;

	/** キーを押す/離したときのアニメーション時間（ミリ秒） */
	public int easeDurationMs = 500;

	/** スクロール追従がだいたい収まるまでの時間（ミリ秒）。0 で追従なし */
	public int scrollSmoothMs = 175;

	/** ズーム中はマウス感度を落とす */
	public boolean reduceSensitivity = true;

	/** 感度の落とし具合（1.0 で倍率比例） */
	public double sensitivityStrength = 1.0D;

	public static ZoomConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	public static Path getPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(IfutoZoomClient.MOD_ID + ".json");
	}

	public static ZoomConfig load() {
		Path path = getPath();
		ZoomConfig config = new ZoomConfig();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ZoomConfig loaded = GSON.fromJson(reader, ZoomConfig.class);

				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				IfutoZoomClient.LOGGER.warn("[ifuto-zoom] {} が読めなかったので既定値で続行", path, e);
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
			IfutoZoomClient.LOGGER.warn("[ifuto-zoom] {} を書き込めなかった", path, e);
		}
	}

	public void resetToDefaults() {
		ZoomConfig defaults = new ZoomConfig();
		this.mode = defaults.mode;
		this.defaultZoom = defaults.defaultZoom;
		this.minZoom = defaults.minZoom;
		this.scrollStep = defaults.scrollStep;
		this.scrollToZoom = defaults.scrollToZoom;
		this.keepZoomLevel = defaults.keepZoomLevel;
		this.easing = defaults.easing;
		this.easingPower = defaults.easingPower;
		this.easeDurationMs = defaults.easeDurationMs;
		this.scrollSmoothMs = defaults.scrollSmoothMs;
		this.reduceSensitivity = defaults.reduceSensitivity;
		this.sensitivityStrength = defaults.sensitivityStrength;
	}

	/** 手書き編集や古いファイルで壊れていても落ちないように丸める */
	public void validate() {
		if (this.mode == null) {
			this.mode = ZoomMode.HOLD;
		}

		if (this.easing == null) {
			this.easing = EasingType.EASE_OUT;
		}

		this.minZoom = clamp(this.minZoom, HARD_MIN_ZOOM, 10.0D, 1.0D);
		this.defaultZoom = clamp(this.defaultZoom, this.minZoom, MAX_CONFIGURABLE_ZOOM, 3.0D);
		this.scrollStep = clamp(this.scrollStep, 1.01D, 2.0D, 1.15D);
		this.sensitivityStrength = clamp(this.sensitivityStrength, 0.0D, 1.0D, 1.0D);
		this.easingPower = clamp(this.easingPower, EasingType.MIN_POWER, EasingType.MAX_POWER, 2.0D);
		this.easeDurationMs = (int) clamp(this.easeDurationMs, 0, 2000, 500);
		this.scrollSmoothMs = (int) clamp(this.scrollSmoothMs, 0, 2000, 175);
	}

	private static double clamp(double value, double min, double max, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return fallback;
		}

		return Math.min(max, Math.max(min, value));
	}
}
