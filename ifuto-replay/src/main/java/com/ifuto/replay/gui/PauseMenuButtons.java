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
 * ボタンは画面の左上に縦に並べる。GUIサイズを変えると画面の広さ（scaled）が変わるので、
 * 広さに合わせて3段階に縮めて、中央のバニラボタンに食い込まないようにしている。
 * まとめて1枚の板の上に置いて、どれがこの Mod の操作かひと目でわかるようにしている。
 */
@Environment(EnvType.CLIENT)
public final class PauseMenuButtons {
	private static final int PANEL_MARGIN = 4;

	/** ボタンの数（板の大きさの計算に使う） */
	private static final int BUTTON_COUNT = 5;

	/** ツールチップを書き換えた「秒」（毎ティック作り直さないための覚え） */
	private static long lastClipSeconds = -1L;

	private static int panelX = PANEL_MARGIN;
	private static int panelY = PANEL_MARGIN;
	private static int panelWidth;
	private static int panelHeight;

	private PauseMenuButtons() {
	}

	/** 画面が開いた直後に呼ばれる。ボタンを足して、毎ティック表示を更新する */
	public static void attach(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
		// 広さに合わせて3段階（高さが足りないと板が中央のボタンに食い込む）。
		// 幅が足りないときは少し細くする（長い文字はボタン側で「...」に切る）
		int buttonWidth = scaledWidth < 400 ? 96 : 112;
		int buttonHeight;
		int spacing;
		int padding;

		if (scaledHeight >= 340) {
			buttonHeight = 22;
			spacing = 6;
			padding = 5;
		} else if (scaledHeight >= 240) {
			buttonHeight = 18;
			spacing = 4;
			padding = 4;
		} else {
			buttonHeight = 14;
			spacing = 3;
			padding = 3;
		}

		panelX = PANEL_MARGIN;
		panelY = PANEL_MARGIN;
		panelWidth = buttonWidth + padding * 2;
		panelHeight = buttonHeight * BUTTON_COUNT + spacing * (BUTTON_COUNT - 1) + padding * 2;

		int x = panelX + padding;
		int y = panelY + padding;

		// 「しおり」と「クリップ」は録画ボタンを押した直後にも状態を更新したいので、配列経由で持つ
		ModernButton[] markerHolder = new ModernButton[1];
		ModernButton[] clipHolder = new ModernButton[1];

		ModernButton recordButton = new ModernButton(x, y, buttonWidth, buttonHeight, Text.empty(),
				button -> {
					RecordingManager.INSTANCE.toggle(client);
					refresh(client, button, markerHolder[0], clipHolder[0]);
				}, ModernButton.Style.PRIMARY);
		recordButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.record.tooltip")));

		y += buttonHeight + spacing;
		ModernButton markerButton = new ModernButton(x, y, buttonWidth, buttonHeight,
				Text.translatable("ifuto-replay.menu.marker"),
				button -> RecordingManager.INSTANCE.addMarker(client, null), ModernButton.Style.NORMAL);
		markerButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.marker.tooltip")));
		markerHolder[0] = markerButton;

		y += buttonHeight + spacing;
		ModernButton listButton = new ModernButton(x, y, buttonWidth, buttonHeight,
				Text.translatable("ifuto-replay.menu.list"),
				button -> client.setScreen(new RecordingListScreen(screen)), ModernButton.Style.NORMAL);
		listButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.list.tooltip")));

		y += buttonHeight + spacing;
		ModernButton settingsButton = new ModernButton(x, y, buttonWidth, buttonHeight,
				Text.translatable("ifuto-replay.menu.settings"),
				button -> client.setScreen(new ReplayConfigScreen(screen)), ModernButton.Style.NORMAL);
		settingsButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.settings.tooltip")));

		// 「さっきの数秒」をあとから残す（クリップ方式のときだけ押せる）
		y += buttonHeight + spacing;
		ModernButton clipButton = new ModernButton(x, y, buttonWidth, buttonHeight,
				Text.translatable("ifuto-replay.menu.clip"),
				button -> RecordingManager.INSTANCE.saveClip(client), ModernButton.Style.PRIMARY);
		clipButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.clip.tooltip")));
		clipHolder[0] = clipButton;

		Screens.getButtons(screen).add(recordButton);
		Screens.getButtons(screen).add(markerButton);
		Screens.getButtons(screen).add(listButton);
		Screens.getButtons(screen).add(settingsButton);
		Screens.getButtons(screen).add(clipButton);

		refresh(client, recordButton, markerButton, clipButton);

		// ボタンの下に板を敷く（ボタンより先に描く）
		ScreenEvents.beforeRender(screen).register((target, context, mouseX, mouseY, delta) ->
				drawPanel(context));

		// 録画中は経過時間をボタンに出すので、毎ティック書き換える
		ScreenEvents.afterTick(screen).register(ignored -> refresh(client, recordButton, markerButton, clipButton));
	}

	/** 操作ボタンをまとめる板 */
	private static void drawPanel(DrawContext context) {
		ReplayTheme.panel(context, panelX, panelY, panelWidth, panelHeight, 10);
	}

	private static void refresh(MinecraftClient client, ModernButton recordButton, ModernButton markerButton,
								ModernButton clipButton) {
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
		// クリップは「クリップ方式で録っている」ときだけ（まとめている最中は待ってもらう）
		RecordingSession clipSession = RecordingManager.INSTANCE.getSession();
		boolean savingClip = clipSession != null && clipSession.isSavingClip();
		clipButton.active = RecordingManager.INSTANCE.canClip() && !savingClip;
		clipButton.setMessage(Text.translatable(savingClip
				? "ifuto-replay.menu.clip.saving" : "ifuto-replay.menu.clip"));

		// いま何秒ぶん残っているかをツールチップに出す（秒が変わったときだけ作り直す）
		long readySeconds = clipSession == null ? 0L : clipSession.clipBufferedMillis() / 1000L;

		if (readySeconds != lastClipSeconds) {
			lastClipSeconds = readySeconds;
			clipButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.clip.tooltip")
					.append(Text.literal("\n"))
					.append(Text.translatable("ifuto-replay.menu.clip.buffered",
							Text.literal(RecordingManager.formatDuration(readySeconds * 1000L))))));
		}
	}
}
