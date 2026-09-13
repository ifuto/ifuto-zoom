package com.ifuto.replay.gui;

import com.ifuto.replay.recording.RecordingManager;
import com.ifuto.replay.recording.RecordingSession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * ポーズメニュー（ESC）の隅に置く、Flashback 風の操作ボタン。
 *
 * <p>キーバインドは使わない。メニューを開いて押すだけ。
 * ボタンは画面の左上に縦に並べる（バニラのメニューは中央のグリッドなので、まず被らない）。
 */
@Environment(EnvType.CLIENT)
public final class PauseMenuButtons {
	private static final int BUTTON_WIDTH = 104;
	private static final int BUTTON_HEIGHT = 20;
	private static final int MARGIN = 8;
	private static final int SPACING = 4;

	private PauseMenuButtons() {
	}

	/** 画面が開いた直後に呼ばれる。ボタンを足して、毎ティック表示を更新する */
	public static void attach(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
		int x = MARGIN;
		int y = MARGIN;

		// 「しおり」ボタンは録画ボタンを押した直後にも状態を更新したいので、配列経由で持つ
		ButtonWidget[] markerHolder = new ButtonWidget[1];

		ButtonWidget recordButton = ButtonWidget.builder(Text.empty(), button -> {
			RecordingManager.INSTANCE.toggle(client);
			refresh(client, button, markerHolder[0]);
		}).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		recordButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.record.tooltip")));

		y += BUTTON_HEIGHT + SPACING;
		ButtonWidget markerButton = ButtonWidget.builder(Text.translatable("ifuto-replay.menu.marker"),
						button -> RecordingManager.INSTANCE.addMarker(client, null))
				.dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		markerButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.marker.tooltip")));
		markerHolder[0] = markerButton;

		y += BUTTON_HEIGHT + SPACING;
		ButtonWidget listButton = ButtonWidget.builder(Text.translatable("ifuto-replay.menu.list"),
						button -> client.setScreen(new RecordingListScreen(screen)))
				.dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		listButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.list.tooltip")));

		y += BUTTON_HEIGHT + SPACING;
		ButtonWidget settingsButton = ButtonWidget.builder(Text.translatable("ifuto-replay.menu.settings"),
						button -> client.setScreen(new ReplayConfigScreen(screen)))
				.dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		settingsButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.settings.tooltip")));

		Screens.getButtons(screen).add(recordButton);
		Screens.getButtons(screen).add(markerButton);
		Screens.getButtons(screen).add(listButton);
		Screens.getButtons(screen).add(settingsButton);

		refresh(client, recordButton, markerButton);

		// 録画中は経過時間をボタンに出すので、毎ティック書き換える
		ScreenEvents.afterTick(screen).register(ignored -> refresh(client, recordButton, markerButton));
	}

	private static void refresh(MinecraftClient client, ButtonWidget recordButton, ButtonWidget markerButton) {
		boolean recording = RecordingManager.INSTANCE.isRecording();

		if (recording) {
			RecordingSession session = RecordingManager.INSTANCE.getSession();
			long elapsed = session == null ? 0L : session.elapsedMillis();
			recordButton.setMessage(Text.translatable("ifuto-replay.menu.stop",
					Text.literal(RecordingManager.formatDuration(elapsed))));
		} else {
			recordButton.setMessage(Text.translatable("ifuto-replay.menu.record"));
		}

		// しおりは録画中だけ意味があるので、それ以外では押せなくする
		markerButton.active = recording;
	}
}
