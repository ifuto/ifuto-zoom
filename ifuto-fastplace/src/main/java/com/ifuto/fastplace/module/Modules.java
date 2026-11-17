package com.ifuto.fastplace.module;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.FastPlaceMod;
import com.ifuto.fastplace.FastPlaceModule;
import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.gui.AimAssistSettingsScreen;
import com.ifuto.fastplace.gui.FastPlaceConfigScreen;
import com.ifuto.fastplace.gui.ModuleScreen;
import com.ifuto.fastplace.gui.ScaffoldSettingsScreen;
import com.ifuto.fastplace.scaffold.ScaffoldModule;

import java.util.ArrayList;
import java.util.List;

/** モジュール画面に出す一覧。 */
public final class Modules {
	private static final List<Module> ALL = new ArrayList<>();

	static {
		ALL.add(new Module(
			"scaffold", "Scaffold", "God Bridge。前を見たまま、足元へ自動設置",
			ModuleCategory.MOVEMENT,
			ScaffoldModule::enabled,
			ScaffoldModule::setEnabled,
			ScaffoldSettingsScreen::new,
			null));
		ALL.add(new Module(
			"fastplace", "FastPlace", "右クリック長押しを人間の速度で連打",
			ModuleCategory.PLAYER,
			FastPlaceModule::enabled,
			FastPlaceModule::setEnabled,
			FastPlaceConfigScreen::new,
			FastPlaceMod::fastPlaceBindText));
		ALL.add(new Module(
			"aimassist", "Aim Assist", "剣の間合いで、人間の速度とクセで相手に寄る",
			ModuleCategory.PLAYER,
			AimAssistModule::enabled,
			AimAssistModule::setEnabled,
			AimAssistSettingsScreen::new,
			null));
		ALL.add(new Module(
			"cps", "CPS Counter", "連打中だけ CPS を画面右上に表示",
			ModuleCategory.RENDER,
			() -> FastPlaceConfig.get().hudCps,
			on -> {
				FastPlaceConfig.get().hudCps = on;
				FastPlaceConfig.get().save();
			},
			null,
			null));
	}

	private Modules() {
	}

	public static List<Module> all() {
		return ALL;
	}

	public static List<Module> inCategory(ModuleCategory category) {
		List<Module> list = new ArrayList<>();
		for (Module module : ALL) {
			if (module.category() == category) {
				list.add(module);
			}
		}
		return list;
	}

	public static ModuleScreen screen() {
		return new ModuleScreen(null);
	}

	public static int count(ModuleCategory category) {
		return inCategory(category).size();
	}
}
