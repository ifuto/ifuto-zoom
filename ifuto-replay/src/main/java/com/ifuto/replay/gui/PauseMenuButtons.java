package com.ifuto.replay.gui;

import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
import com.ifuto.replay.recording.RecordingManager;
import com.ifuto.replay.recording.RecordingSession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

/**
 * ポーズメニュー（ESC）の隅に置く、Flashback 風の操作ボタン。
 *
 * <p>キーバインドは使わない。メニューを開いて押すだけ。
 * ボタンは画面の左上に縦に並べる（バニラのメニューは中央のグリッドなので、まず被らない）。
 * まとめて1枚の板の上に置いて、どれがこの Mod の操作かひと目でわかるようにしている。
 */
@Environment(EnvType.CLIENT)
public final class PauseMenuButtons {
	private static final int PANEL_MARGIN = 4;
	private static final int PANEL_PADDING = 5;
	private static final int BUTTON_WIDTH = 112;
	private static final int BUTTON_HEIGHT = 22;
	private static final int SPACING = 6;

	private static int panelX = PANEL_MARGIN;
	private static int panelY = PANEL_MARGIN;
	private static int panelWidth = BUTTON_WIDTH + PANEL_PADDING * 2;
	private static int panelHeight = BUTTON_HEIGHT * 4 + SPACING * 3 + PANEL_PADDING * 2;

	private PauseMenuButtons() {
	}

	/** 画面が開いた直後に呼ばれる。ボタンを足して、毎ティック表示を更新する */
	public static void attach(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
		int x = PANEL_MARGIN + PANEL_PADDING;
		int y = PANEL_MARGIN + PANEL_PADDING;

		// 「しおり」ボタンは録画ボタンを押した直後にも状態を更新したいので、配列経由で持つ
		ModernButton[] markerHolder = new ModernButton[1];

		ModernButton recordButton = new ModernButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Text.empty(),
				button -> {
					RecordingManager.INSTANCE.toggle(client);
					refresh(client, button, markerHolder[0]);
				}, ModernButton.Style.PRIMARY);
		recordButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.record.tooltip")));

		y += BUTTON_HEIGHT + SPACING;
		ModernButton markerButton = new ModernButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
				Text.translatable("ifuto-replay.menu.marker"),
				button -> RecordingManager.INSTANCE.addMarker(client, null), ModernButton.Style.NORMAL);
		markerButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.marker.tooltip")));
		markerHolder[0] = markerButton;

		y += BUTTON_HEIGHT + SPACING;
		ModernButton listButton = new ModernButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
				Text.translatable("ifuto-replay.menu.list"),
				button -> client.setScreen(new RecordingListScreen(screen)), ModernButton.Style.NORMAL);
		listButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.list.tooltip")));

		y += BUTTON_HEIGHT + SPACING;
		ModernButton settingsButton = new ModernButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
				Text.translatable("ifuto-replay.menu.settings"),
				button -> client.setScreen(new ReplayConfigScreen(screen)), ModernButton.Style.NORMAL);
		settingsButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.settings.tooltip")));

		Screens.getButtons(screen).add(recordButton);
		Screens.getButtons(screen).add(markerButton);
		Screens.getButtons(screen).add(listButton);
		Screens.getButtons(screen).add(settingsButton);

		refresh(client, recordButton, markerButton);

		// ボタンの下に板を敷く（ボタンより先に描く）
		ScreenEvents.beforeRender(screen).register((target, context, mouseX, mouseY, delta) ->
				drawPanel(context));

		// 録画中は経過時間をボタンに出すので、毎ティック書き換える
		ScreenEvents.afterTick(screen).register(ignored -> refresh(client, recordButton, markerButton));
	}

	/** 操作ボタンをまとめる板 */
	private static void drawPanel(DrawContext context) {
		ReplayTheme.panel(context, panelX, panelY, panelWidth, panelHeight, 10);
	}

	private static void refresh(MinecraftClient client, ModernButton recordButton, ModernButton markerButton) {
		boolean recording = RecordingManager.INSTANCE.isRecording();

		if (recording) {
			RecordingSession session = RecordingManager.INSTANCE.getSession();
			long elapsed = session == null ? 0L : session.elapsedMillis();
			recordButton.setMessage(Text.translatable("ifuto-replay.menu.stop",
					Text.literal(RecordingManager.formatDuration(elapsed))));
		} else {
			recordButton.setMessage(Text.translatable("ifuto-replay.menu.record"));
		}

		recordButton.setTooltip(Tooltip.of(Text.translatable(recording
				? "ifuto-replay.menu.stop.tooltip" : "ifuto-replay.menu.record.tooltip")));
		// 録っているときは「押すと止まる」のがわかるように赤にする
		recordButton.setStyle(recording ? ModernButton.Style.DANGER : ModernButton.Style.PRIMARY);

		// しおりは録画中だけ意味があるので、それ以外では押せなくする
		markerButton.active = recording;
	}
}
