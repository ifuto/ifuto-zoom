package com.ifuto.replay.gui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * 中身が何もない画面。
 *
 * <p>バニラの {@code MinecraftClient#disconnect} は「次に開く画面」を必ず受け取る。
 * 再生の作り直しで一瞬だけ世界が無くなる間、これを渡して余計な画面が出ないようにする。
 */
public class BlankScreen extends Screen {
	public BlankScreen() {
		super(Text.empty());
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
