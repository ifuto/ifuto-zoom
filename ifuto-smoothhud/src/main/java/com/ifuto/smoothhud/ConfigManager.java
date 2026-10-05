package com.ifuto.smoothhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 設定の読み書き（元 Mod と同じ。置き場所だけ別）。
 */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final File CONFIG_FILE = new File("config/ifuto-smoothhud.json");
	private static Config config;
	public static final Logger LOGGER = LoggerFactory.getLogger("ifuto-smoothhud");

	private ConfigManager() {
	}

	public static Config getConfig() {
		if (config == null) {
			loadConfig();
		}

		return config;
	}

	public static void loadConfig() {
		if (CONFIG_FILE.exists()) {
			try (FileReader reader = new FileReader(CONFIG_FILE)) {
				config = GSON.fromJson(reader, Config.class);
				return;
			} catch (IOException e) {
				LOGGER.error("failed to load config file: {}", e.toString());
			}
		}

		config = new Config();
		saveConfig();
	}

	public static void saveConfig() {
		try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			LOGGER.error("failed to save config file: {}", e.toString());
		}
	}
}
