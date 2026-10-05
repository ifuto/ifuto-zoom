package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 角丸の入力欄。
 *
 * <p>バニラの入力欄は「背景の絵」を必ず描くので、`setDrawsBackground(false)` で
 * 背景だけ消し、文字・カーソル・選択範囲はバニラのままにしている（安全で崩れない）。
 * 背景なしだと文字が左端ぴったりに出て枠の角丸に食い込むので、文字だけ右にずらす。
 */
public class ModernTextField extends TextFieldWidget {
	/** 文字を右にずらす量（バニラの背景ありと同じ4） */
	private static final int TEXT_INSET = 4;

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
		// 枠はそのまま、文字だけ右にずらして描く（終わったら必ず戻す）
		this.setX(this.getX() + TEXT_INSET);

		try {
			super.renderWidget(context, mouseX, mouseY, deltaTicks);
		} finally {
			this.setX(this.getX() - TEXT_INSET);
		}
	}

	/**
	 * クリック位置の判定も文字に合わせる。
	 *
	 * <p>ずらさないと、押した所より左にカーソルが出る。
	 */
	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		this.setX(this.getX() + TEXT_INSET);

		try {
			return super.mouseClicked(click, doubled);
		} finally {
			this.setX(this.getX() - TEXT_INSET);
		}
	}
}
