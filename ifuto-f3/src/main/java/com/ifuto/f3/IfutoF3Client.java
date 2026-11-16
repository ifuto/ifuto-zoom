package com.ifuto.f3;

import com.ifuto.f3.gui.ModuleScreen;
import com.ifuto.f3.module.ScaffoldModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IfutoF3Client implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("ifuto-f3");

	private static final KeyBinding.Category MODULES_CATEGORY =
		KeyBinding.Category.create(Identifier.of("ifuto-f3", "modules"));
	private KeyBinding modulesKey;
	private boolean fastPlacePresent;

	@Override
	public void onInitializeClient() {
		F3Config.load();
		LOGGER.info("[ifuto-f3] loaded (master={}, fps={}, coords={}, scaffold={})",
			F3Config.get().masterEnabled, F3Config.get().fpsMode, F3Config.get().coordsMode,
			F3Config.get().scaffoldEnabled);

		// FastPlace (0.2.0+) は同じ RShift のモジュール画面と、より人間らしい Scaffold を持つ。
		// 画面やブリッジが二重にならないよう、入っているときはこちら側は休む。
		fastPlacePresent = FabricLoader.getInstance().isModLoaded("ifuto-fastplace");
		if (fastPlacePresent) {
			if (ScaffoldModule.enabled()) {
				ScaffoldModule.setEnabled(false);
			}
			LOGGER.info("[ifuto-f3] ifuto-fastplace を検出: モジュール画面と Scaffold は FastPlace 側を使います");
		}

		modulesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.ifuto-f3.modules", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, MODULES_CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (modulesKey.wasPressed()) {
				if (!fastPlacePresent && client.currentScreen == null) {
					client.setScreen(new ModuleScreen(null));
				}
			}
			if (!fastPlacePresent) {
				ScaffoldModule.tick(client);
			}
		});
	}
}
