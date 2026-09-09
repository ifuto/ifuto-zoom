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
 * Simple JSON backed configuration, stored in {@code config/ifuto-zoom.json}.
 */
@Environment(EnvType.CLIENT)
public class ZoomConfig {
	public static final double HARD_MIN_ZOOM = 0.25D;
	public static final double MAX_CONFIGURABLE_ZOOM = 50.0D;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ZoomConfig instance;

	// ---- options -------------------------------------------------------------------------------

	/** Hold the key (default) or toggle it. */
	public ZoomMode mode = ZoomMode.HOLD;

	/** Zoom factor applied when the key is pressed. */
	public double defaultZoom = 3.0D;

	/** Lower bound while scrolling out (upper bound is practically unlimited). */
	public double minZoom = 1.0D;

	/** Multiplier applied per scroll notch. */
	public double scrollStep = 1.15D;

	/** Whether scrolling changes the zoom factor while zooming. */
	public boolean scrollToZoom = true;

	/** Keep the scrolled zoom factor after releasing the key. */
	public boolean keepZoomLevel = false;

	/** Easing curve of the zoom animation. */
	public EasingType easing = EasingType.EASE_OUT;

	/** Duration of the key press / release zoom animation in milliseconds (scroll always uses a ~175 ms smoothing). */
	public int easeDurationMs = 500;

	/** Slow the mouse down while zoomed in. */
	public boolean reduceSensitivity = true;

	/** How strongly the sensitivity follows the zoom (1.0 = fully proportional). */
	public double sensitivityStrength = 1.0D;

	// ---- loading / saving ----------------------------------------------------------------------

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
				IfutoZoomClient.LOGGER.warn("[ifuto-zoom] Could not read {}, falling back to defaults", path, e);
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
			IfutoZoomClient.LOGGER.warn("[ifuto-zoom] Could not write {}", path, e);
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
		this.easeDurationMs = defaults.easeDurationMs;
		this.reduceSensitivity = defaults.reduceSensitivity;
		this.sensitivityStrength = defaults.sensitivityStrength;
	}

	/** Keeps hand edited / outdated config files from breaking the game. */
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

		if (this.easeDurationMs < 0) {
			this.easeDurationMs = 0;
		} else if (this.easeDurationMs > 2000) {
			this.easeDurationMs = 2000;
		}
	}

	private static double clamp(double value, double min, double max, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return fallback;
		}

		return Math.min(max, Math.max(min, value));
	}
}
