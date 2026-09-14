package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 今っぽい見た目のボタン（角丸・ふわっとしたホバー・はっきりした色）。
 *
 * <p>バニラの {@link ButtonWidget} をそのまま継承しているので、置き場所や押したときの扱いは
 * バニラとまったく同じ。**描き方だけ** 差し替えている（だから壊れにくい）。
 */
public class ModernButton extends ButtonWidget {
	public enum Style {
		/** ふつう */
		NORMAL,
		/** いちばん目立たせる（決定など） */
		PRIMARY,
		/** 注意（停止・削除など） */
		DANGER,
		/** 背景なし（文字だけ） */
		GHOST
	}

	private final Style style;
	private final int radius;
	private float hover;

	public ModernButton(int x, int y, int width, int height, Text message, PressAction onPress, Style style) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
		this.style = style;
		this.radius = Math.min(8, height / 2);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer renderer = client.textRenderer;
		float target = this.isHovered() && this.active ? 1F : 0F;

		// ふわっと変わる（1フレームで一気に切り替えない）
		this.hover += (target - this.hover) * 0.25F;

		int fill = ReplayTheme.mix(this.baseColor(), this.hoverColor(), this.hover);

		if (!this.active) {
			fill = ReplayTheme.withAlpha(fill, 0x66);
		}

		if (this.style != Style.GHOST || this.hover > 0.01F) {
			ReplayTheme.fillRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
					this.radius, fill);
			ReplayTheme.strokeRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
					this.radius, this.borderColor());
		}

		if (this.isFocused()) {
			ReplayTheme.strokeRound(context, this.getX() - 1, this.getY() - 1, this.getWidth() + 2,
					this.getHeight() + 2, this.radius + 1, ReplayTheme.ACCENT);
		}

		Text message = this.getMessage();
		int maxWidth = this.getWidth() - 8;
		int textWidth = renderer.getWidth(message);

		if (textWidth > maxWidth && maxWidth > renderer.getWidth("...")) {
			// 入りきらないときは「...」で切る（バニラと同じ考え方）
			message = Text.literal(renderer.trimToWidth(message.getString(),
					maxWidth - renderer.getWidth("...")) + "...");
			textWidth = renderer.getWidth(message);
		}

		int textX = this.getX() + (this.getWidth() - textWidth) / 2;
		int textY = this.getY() + (this.getHeight() - renderer.fontHeight) / 2 + 1;
		context.drawText(renderer, message, textX, textY, this.textColor(), true);
	}

	private int baseColor() {
		return switch (this.style) {
			case PRIMARY -> ReplayTheme.ACCENT;
			case DANGER -> ReplayTheme.RECORD;
			case GHOST -> ReplayTheme.withAlpha(ReplayTheme.SURFACE_RAISED, 0x00);
			default -> ReplayTheme.SURFACE_RAISED;
		};
	}

	private int hoverColor() {
		return switch (this.style) {
			case PRIMARY -> 0xFF5CCBFA;
			case DANGER -> 0xFFFF6B7E;
			case GHOST -> ReplayTheme.withAlpha(ReplayTheme.SURFACE_RAISED, 0x2A);
			default -> 0xF2262E36;
		};
	}

	private int borderColor() {
		if (this.style == Style.PRIMARY || this.style == Style.DANGER) {
			return ReplayTheme.withAlpha(0xFFFFFFFF, 0x30);
		}

		return ReplayTheme.BORDER;
	}

	private int textColor() {
		if (!this.active) {
			return ReplayTheme.TEXT_DIM;
		}

		if (this.style == Style.PRIMARY || this.style == Style.DANGER) {
			return 0xFF0B1016;
		}

		return ReplayTheme.TEXT;
	}
}
