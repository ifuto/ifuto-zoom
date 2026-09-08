package com.ifuto.zoom.compat;

import com.ifuto.zoom.gui.ZoomConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Adds the "Config" button to the ifuto-zoom entry inside Mod Menu.
 */
@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ZoomConfigScreen::new;
	}
}
