package com.ifuto.fastplace;

import com.ifuto.fastplace.hud.CpsHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FastPlaceMod implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("ifuto-fastplace");
	public static final String MOD_ID = "ifuto-fastplace";

	@Override
	public void onInitializeClient() {
		FastPlaceConfig.load();
		LOGGER.info("[ifuto-fastplace] loaded (enabled={}, intervalMs={})",
			FastPlaceConfig.get().enabled, FastPlaceConfig.get().intervalMs);

		ClientTickEvents.END_CLIENT_TICK.register(FastPlaceModule::onTick);

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
			Identifier.of(MOD_ID, "cps_counter"), new CpsHud());
	}
}
