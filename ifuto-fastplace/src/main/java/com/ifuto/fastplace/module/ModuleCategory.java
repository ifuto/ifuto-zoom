package com.ifuto.fastplace.module;

/** モジュール画面の左側に並ぶカテゴリ。 */
public enum ModuleCategory {
	MOVEMENT("Movement", "移動とブリッジ"),
	PLAYER("Player", "プレイヤー操作"),
	RENDER("Render", "画面表示");

	public final String label;
	public final String hint;

	ModuleCategory(String label, String hint) {
		this.label = label;
		this.hint = hint;
	}
}
