package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 角丸の入力欄。
 *
 * <p>バニラの入力欄は「背景の絵」を必ず描くので、`setDrawsBackground(false)` で
 * 背景だけ消し、文字・カーソル・選択範囲はバニラのままにしている（安全で崩れない）。
 */
public class ModernTextField extends TextFieldWidget {
	public ModernTextField(TextRenderer textRenderer, int x, int y, int width, int height, Text text) {
		super(textRenderer, x, y, width, height, text);
		this.setDrawsBackground(false);
	}

	@Override
	public void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int radius = Math.min(6, this.getHeight() / 2);
		ReplayTheme.fillRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), radius,
				ReplayTheme.SURFACE_INPUT);
		ReplayTheme.strokeRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), radius,
				this.isFocused() ? ReplayTheme.ACCENT : ReplayTheme.BORDER);
		super.renderWidget(context, mouseX, mouseY, deltaTicks);
	}
}
