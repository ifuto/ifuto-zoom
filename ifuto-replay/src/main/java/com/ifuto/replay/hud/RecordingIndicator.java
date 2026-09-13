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

/**
 * 録画中だけ画面の隅に出る小さなインジケータ。
 *
 * <p>録画していないときは「設定を読む」だけですぐ抜けるので、ふだんの負荷はほぼゼロ。
 */
@Environment(EnvType.CLIENT)
public class RecordingIndicator implements HudElement {
	private static final int MARGIN = 4;
	private static final int BACKGROUND = 0x66000000;
	private static final int DOT_ON = 0xFFFF5555;
	private static final int DOT_OFF = 0xFF888888;

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

		boolean blink = System.currentTimeMillis() % 1000L < 600L;
		String text = (blink ? "● " : "○ ")
				+ RecordingManager.formatDuration(session.elapsedMillis())
				+ "  " + RecordingManager.formatSize(session.bytesWritten());

		TextRenderer renderer = client.textRenderer;
		int textWidth = renderer.getWidth(text);
		IndicatorPosition position = config.indicatorPosition;

		int x = switch (position) {
			case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
			case TOP_RIGHT, BOTTOM_RIGHT -> context.getScaledWindowWidth() - textWidth - MARGIN;
		};

		int y = switch (position) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> context.getScaledWindowHeight() - renderer.fontHeight - MARGIN;
		};

		context.fill(x - 3, y - 3, x + textWidth + 3, y + renderer.fontHeight + 1, BACKGROUND);
		context.drawTextWithShadow(renderer, text, x, y, blink ? DOT_ON : DOT_OFF);
	}
}
