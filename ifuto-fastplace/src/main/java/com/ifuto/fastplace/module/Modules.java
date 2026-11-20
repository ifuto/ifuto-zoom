package com.ifuto.fastplace.module;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.FastPlaceMod;
import com.ifuto.fastplace.FastPlaceModule;
import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.anchor.AutoSafeAnchorModule;
import com.ifuto.fastplace.gui.AimAssistSettingsScreen;
import com.ifuto.fastplace.gui.AutoSafeAnchorSettingsScreen;
import com.ifuto.fastplace.gui.AutoStunSlamSettingsScreen;
import com.ifuto.fastplace.gui.AutoToolSettingsScreen;
import com.ifuto.fastplace.gui.ClickCrystalSettingsScreen;
import com.ifuto.fastplace.gui.FastPlaceConfigScreen;
import com.ifuto.fastplace.gui.ModuleScreen;
import com.ifuto.fastplace.gui.ScaffoldSettingsScreen;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import com.ifuto.fastplace.crystal.ClickCrystalModule;
import com.ifuto.fastplace.stun.AutoStunSlamModule;
import com.ifuto.fastplace.tool.AutoToolModule;

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
			"aimassist", "Aim Assist", "クリックした相手に人間の速度で寄る",
			ModuleCategory.COMBAT,
			AimAssistModule::enabled,
			AimAssistModule::setEnabled,
			AimAssistSettingsScreen::new,
			null));
		ALL.add(new Module(
			"clickcrystal", "ClickCrystal", "黒曜石を左クリックでクリスタル連続爆破",
			ModuleCategory.COMBAT,
			ClickCrystalModule::enabled,
			ClickCrystalModule::setEnabled,
			ClickCrystalSettingsScreen::new,
			null));
		ALL.add(new Module(
			"stunslam", "Auto Stun Slam", "メイスで叩く前に斧へ替えて盾を割り、戻して叩く",
			ModuleCategory.COMBAT,
			AutoStunSlamModule::enabled,
			AutoStunSlamModule::setEnabled,
			AutoStunSlamSettingsScreen::new,
			null));
		ALL.add(new Module(
			"safeanchor", "Auto Safe Anchor", "アンカー設置 → 1 チャージ → 手前に壁 → 起爆",
			ModuleCategory.COMBAT,
			AutoSafeAnchorModule::enabled,
			AutoSafeAnchorModule::setEnabled,
			AutoSafeAnchorSettingsScreen::new,
			null));
		ALL.add(new Module(
			"autotool", "Auto Tool", "適正でない道具で掘り始めたら人間の判断で持ち替える",
			ModuleCategory.PLAYER,
			AutoToolModule::enabled,
			AutoToolModule::setEnabled,
			AutoToolSettingsScreen::new,
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
