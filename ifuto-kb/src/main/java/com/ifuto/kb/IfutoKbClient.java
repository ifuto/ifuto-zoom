package com.ifuto.kb;

import com.ifuto.kb.measure.HitTracker;
import com.ifuto.kb.store.KbStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IfutoKbClient implements ClientModInitializer {
	public static final String MOD_ID = "ifuto-kb";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		KbStore.init(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
		ClientTickEvents.END_CLIENT_TICK.register(HitTracker::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			KbStore.onJoin(client);
			HitTracker.onJoin();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			HitTracker.onLeave();
			KbStore.onLeave();
		});
		LOGGER.info("[ifuto-kb] ready");
	}
}
