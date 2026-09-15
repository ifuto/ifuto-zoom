package com.ifuto.replay.gui.widget;

import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 「押した」とみなすキー（Enter / Space / テンキーの Enter）。
 *
 * <p>自前の部品はバニラのボタンを継承していないので、キーでの決定をここで受けている。
 * 1.21.11 の `KeyInput` は中身がまだ名前付きでないため、`InputUtil` 経由でコードを見る。
 */
final class ModernKeys {
	private ModernKeys() {
	}

	static boolean isActivation(KeyInput input) {
		int code = InputUtil.fromKeyCode(input).getCode();
		return code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER || code == GLFW.GLFW_KEY_SPACE;
	}
}
