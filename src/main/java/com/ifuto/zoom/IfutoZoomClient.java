package com.ifuto.zoom;

import com.ifuto.zoom.config.ZoomConfig;
import com.ifuto.zoom.config.ZoomMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint of ifuto-zoom.
 *
 * <p>Registers the zoom key binding (default: C) and drives {@link ZoomState} every client tick.</p>
 */
@Environment(EnvType.CLIENT)
public class IfutoZoomClient implements ClientModInitializer {
	public static final String MOD_ID = "ifuto-zoom";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Own controls category; its label key is {@code key.category.ifuto-zoom.zoom}. */
	public static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "zoom"));

	private static KeyBinding zoomKey;

	public static KeyBinding getZoomKey() {
		return zoomKey;
	}

	@Override
	public void onInitializeClient() {
		// Make sure the config exists on disk / is loaded before the first frame.
		ZoomConfig.get();

		zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ifuto-zoom.zoom",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_C,
				KEY_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (zoomKey == null) {
				return;
			}

			ZoomConfig config = ZoomConfig.get();

			if (config.mode == ZoomMode.TOGGLE) {
				boolean toggled = false;

				while (zoomKey.wasPressed()) {
					toggled = !toggled;
				}

				if (toggled) {
					ZoomState.setActive(!ZoomState.isActive());
				}
			} else {
				// Hold mode: the key state itself is the zoom state.
				while (zoomKey.wasPressed()) {
					// Consume queued presses so they do not leak into a later toggle.
				}

				ZoomState.setActive(zoomKey.isPressed());
			}
		});

		LOGGER.info("[ifuto-zoom] initialized");
	}
}
