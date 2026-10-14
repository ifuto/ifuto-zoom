package com.ifuto.f3;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** F3 Faker の入口。設定を読むだけ。 */
public final class IfutoF3Client implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("ifuto-f3");

	@Override
	public void onInitializeClient() {
		F3Config.load();
		LOGGER.info("[ifuto-f3] loaded (master={}, fps={}, coords={})",
			F3Config.get().masterEnabled, F3Config.get().fpsMode, F3Config.get().coordsMode);
	}
}
