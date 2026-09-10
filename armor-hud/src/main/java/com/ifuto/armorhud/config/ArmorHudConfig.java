package com.ifuto.armorhud.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.armorhud.IfutoArmorHudClient;
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
 * config/ifuto-armor-hud.json に保存する設定。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ArmorHudConfig instance;

	/** HUD 自体の表示/非表示（V キーでも切り替わる） */
	public boolean showHud = true;

	/** 画面のどの隅に置くか */
	public HudPosition position = HudPosition.BOTTOM_LEFT;

	/** アイコンの並べ方 */
	public HudLayout layout = HudLayout.VERTICAL;

	/** 耐久値の出し方 */
	public DurabilityStyle durability = DurabilityStyle.BAR;

	/** 装備していないスロットを出すか（false なら薄い枠だけ描く） */
	public boolean hideEmptySlots = true;

	/** 隅からの微調整（+が右/下方向） */
	public int offsetX = 0;
	public int offsetY = 0;

	public static ArmorHudConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	public static Path getPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(IfutoArmorHudClient.MOD_ID + ".json");
	}

	public static ArmorHudConfig load() {
		Path path = getPath();
		ArmorHudConfig config = new ArmorHudConfig();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ArmorHudConfig loaded = GSON.fromJson(reader, ArmorHudConfig.class);

				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				IfutoArmorHudClient.LOGGER.warn("[ifuto-armor-hud] {} が読めなかったので既定値で続行", path, e);
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
			IfutoArmorHudClient.LOGGER.warn("[ifuto-armor-hud] {} を書き込めなかった", path, e);
		}
	}

	public void resetToDefaults() {
		ArmorHudConfig defaults = new ArmorHudConfig();
		this.showHud = defaults.showHud;
		this.position = defaults.position;
		this.layout = defaults.layout;
		this.durability = defaults.durability;
		this.hideEmptySlots = defaults.hideEmptySlots;
		this.offsetX = defaults.offsetX;
		this.offsetY = defaults.offsetY;
	}

	/** 手書き編集や古いファイルで壊れていても落ちないように丸める */
	public void validate() {
		if (this.position == null) {
			this.position = HudPosition.BOTTOM_LEFT;
		}

		if (this.layout == null) {
			this.layout = HudLayout.VERTICAL;
		}

		if (this.durability == null) {
			this.durability = DurabilityStyle.BAR;
		}

		this.offsetX = clamp(this.offsetX, -500, 500);
		this.offsetY = clamp(this.offsetY, -500, 500);
	}

	private static int clamp(int value, int min, int max) {
		return Math.min(max, Math.max(min, value));
	}
}
