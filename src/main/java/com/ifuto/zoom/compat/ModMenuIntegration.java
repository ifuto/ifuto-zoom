package com.ifuto.zoom.compat;

import com.ifuto.zoom.gui.ZoomConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Mod Menu の一覧から設定画面を開けるようにする。
 */
@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ZoomConfigScreen::new;
	}
}
