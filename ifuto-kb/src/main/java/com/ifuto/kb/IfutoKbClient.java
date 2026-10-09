package com.ifuto.kb;

import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IfutoKbClient implements ClientModInitializer {
	public static final String MOD_ID = "ifuto-kb";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		try {
			Files.createDirectories(dir.resolve("servers"));
		} catch (Exception e) {
			LOGGER.warn("[ifuto-kb] config dir init failed", e);
		}
		LOGGER.info("[ifuto-kb] ready");
	}
}
