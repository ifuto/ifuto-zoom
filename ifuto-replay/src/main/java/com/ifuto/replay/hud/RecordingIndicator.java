package com.ifuto.replay.hud;

import com.ifuto.replay.config.IndicatorPosition;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.recording.RecordingManager;
import com.ifuto.replay.recording.RecordingSession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import com.ifuto.replay.gui.theme.ReplayTheme;

/**
 * 録画中だけ画面の隅に出る小さなインジケータ。
 *
 * <p>録画していないときは「設定を読む」だけですぐ抜けるので、ふだんの負荷はほぼゼロ。
 */
@Environment(EnvType.CLIENT)
public class RecordingIndicator implements HudElement {
	private static final int MARGIN = 5;
	private static final int PADDING_X = 8;
	private static final int DOT_SIZE = 6;
	private static final int DOT_GAP = 6;

	@Override
	public void render(DrawContext context, RenderTickCounter tickCounter) {
		ReplayConfig config = ReplayConfig.get();

		if (!config.showIndicator) {
			return;
		}

		RecordingSession session = RecordingManager.INSTANCE.getSession();

		if (session == null) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();

		if (client.options.hudHidden) {
			return;
		}

		// 点滅（1秒ごと。録っていることがひと目でわかるように）
		boolean blink = System.currentTimeMillis() % 1000L < 600L;
		String text = RecordingManager.formatDuration(session.elapsedMillis())
				+ "  " + RecordingManager.formatSize(session.bytesWritten());

		TextRenderer renderer = client.textRenderer;
		int textWidth = renderer.getWidth(text);
		int boxWidth = PADDING_X + DOT_SIZE + DOT_GAP + textWidth + PADDING_X;
		int boxHeight = renderer.fontHeight + 8;
		IndicatorPosition position = config.indicatorPosition;

		int x = switch (position) {
			case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
			case TOP_RIGHT, BOTTOM_RIGHT -> context.getScaledWindowWidth() - boxWidth - MARGIN;
		};

		int y = switch (position) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> context.getScaledWindowHeight() - boxHeight - MARGIN;
		};

		// 角丸の「札」（影つき）
		ReplayTheme.fillRound(context, x + 1, y + 2, boxWidth, boxHeight, boxHeight / 2, ReplayTheme.SHADOW);
		ReplayTheme.fillRound(context, x, y, boxWidth, boxHeight, boxHeight / 2, ReplayTheme.SURFACE);
		ReplayTheme.strokeRound(context, x, y, boxWidth, boxHeight, boxHeight / 2,
				ReplayTheme.withAlpha(ReplayTheme.RECORD, blink ? 0x66 : 0x33));

		// 赤い丸（点滅）
		int dotX = x + PADDING_X;
		int dotY = y + (boxHeight - DOT_SIZE) / 2;
		ReplayTheme.fillRound(context, dotX, dotY, DOT_SIZE, DOT_SIZE, DOT_SIZE / 2,
				blink ? ReplayTheme.RECORD : ReplayTheme.withAlpha(ReplayTheme.RECORD, 0x55));

		int textX = dotX + DOT_SIZE + DOT_GAP;
		int textY = y + (boxHeight - renderer.fontHeight) / 2;
		context.drawText(renderer, text, textX, textY, ReplayTheme.TEXT, true);
	}
}
