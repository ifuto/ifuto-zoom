package com.ifuto.fastplace.hud;

import com.ifuto.fastplace.FastPlaceModule;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * 連打中だけ出る小さな CPS 表示（整数にならない・毎 tick 変動）。
 */
public final class CpsHud implements HudElement {
	private static final int MARGIN = 5;
	private static final int PADDING_X = 6;

	@Override
	public void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		long now = System.currentTimeMillis();
		if (!FastPlaceModule.hudVisible(now)) {
			return;
		}
		if (client.options.hudHidden) {
			return;
		}

		String text = String.format(java.util.Locale.ROOT, "%.1f CPS", FastPlaceModule.displayCps(now));
		TextRenderer renderer = client.textRenderer;
		int textWidth = renderer.getWidth(text);
		int boxWidth = PADDING_X * 2 + textWidth;
		int boxHeight = renderer.fontHeight + 6;

		int x = context.getScaledWindowWidth() - boxWidth - MARGIN;
		int y = MARGIN;

		context.fill(x + 1, y + 2, x + boxWidth + 1, y + boxHeight + 2, 0x60000000);
		context.fill(x, y, x + boxWidth, y + boxHeight, 0xCC14141A);
		context.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xFFFFB020);

		context.drawText(renderer, text, x + PADDING_X, y + 3, 0xFFFFFFFF, false);
	}
}
