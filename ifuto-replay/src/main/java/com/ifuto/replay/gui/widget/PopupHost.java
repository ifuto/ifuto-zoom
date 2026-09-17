package com.ifuto.replay.gui.widget;

import net.minecraft.client.gui.widget.ClickableWidget;

/**
 * ドロップダウンの飛び出しを置かせてくれる画面。
 *
 * <p>{@code Screen} の子足し・子消しは protected のため、
 * 部品側からは直接呼べない。使う画面がこの口を用意する。
 */
public interface PopupHost {
	void replay$addPopup(ClickableWidget widget);

	void replay$removePopup(ClickableWidget widget);
}
