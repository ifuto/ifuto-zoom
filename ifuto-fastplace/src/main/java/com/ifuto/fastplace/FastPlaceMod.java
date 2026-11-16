package com.ifuto.fastplace;

import com.ifuto.fastplace.gui.ModuleScreen;
import com.ifuto.fastplace.hud.CpsHud;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FastPlaceMod implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("ifuto-fastplace");
	public static final String MOD_ID = "ifuto-fastplace";

	public static final String VERSION = FabricLoader.getInstance()
		.getModContainer(MOD_ID)
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("dev");

	private static final KeyBinding.Category MODULE_CATEGORY =
		KeyBinding.Category.create(Identifier.of(MOD_ID, "modules"));

	private static KeyBinding modulesKey;
	private static KeyBinding fastPlaceKey;

	/** モジュール画面のキー表示用 */
	public static Text fastPlaceBindText() {
		return fastPlaceKey == null ? Text.literal("--") : fastPlaceKey.getBoundKeyLocalizedText();
	}

	@Override
	public void onInitializeClient() {
		FastPlaceConfig.load();
		LOGGER.info("[ifuto-fastplace] loaded (fastplace={}, intervalMs={}, scaffold={})",
			FastPlaceConfig.get().enabled,
			FastPlaceConfig.get().intervalMs,
			FastPlaceConfig.get().scaffoldEnabled);

		modulesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.ifuto-fastplace.modules",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_SHIFT,
			MODULE_CATEGORY));

		fastPlaceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.ifuto-fastplace.toggle_fastplace",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			MODULE_CATEGORY));

		// 入力 → モジュール → Scaffold の順で 1 tick の先頭に処理する。
		// Scaffold がエンティティの向きを書くのはプレイヤーの tick（= 移動パケット送信）より前。
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			while (modulesKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new ModuleScreen(null));
				}
			}
			while (fastPlaceKey.wasPressed()) {
				FastPlaceModule.toggle();
			}
			ScaffoldModule.tick(client);
		});

		ClientTickEvents.END_CLIENT_TICK.register(FastPlaceModule::onTick);

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
			Identifier.of(MOD_ID, "cps_counter"), new CpsHud());
	}
}
