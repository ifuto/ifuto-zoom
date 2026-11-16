package com.ifuto.fastplace.module;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * モジュール画面に 1 行として出るもの。
 * 実体（Scaffold / FastPlace / HUD）はそれぞれのクラスが持っていて、
 * ここは状態の読み書きと表示だけを仲介する。
 */
public final class Module {
	private final String id;
	private final String name;
	private final String description;
	private final ModuleCategory category;
	private final BooleanSupplier state;
	private final Consumer<Boolean> setter;
	private final Function<Screen, Screen> settings;
	private final Supplier<Text> keyText;

	public Module(String id, String name, String description, ModuleCategory category,
			BooleanSupplier state, Consumer<Boolean> setter,
			Function<Screen, Screen> settings, Supplier<Text> keyText) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.category = category;
		this.state = state;
		this.setter = setter;
		this.settings = settings;
		this.keyText = keyText;
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public ModuleCategory category() {
		return category;
	}

	public boolean isEnabled() {
		return state.getAsBoolean();
	}

	public void setEnabled(boolean enabled) {
		setter.accept(enabled);
	}

	public void toggle() {
		setEnabled(!isEnabled());
	}

	public boolean hasSettings() {
		return settings != null;
	}

	public Screen createSettings(Screen parent) {
		return settings == null ? null : settings.apply(parent);
	}

	/** キーバインド表示（無ければ null） */
	public Text keyText() {
		return keyText == null ? null : keyText.get();
	}
}
