package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * オン・オフのスイッチ（右側に小さなトグルを描く）。
 *
 * <p>バニラの ON/OFF ボタンは文字だけなので、見ただけで状態がわかるようにしている。
 */
public class ModernToggle extends ButtonWidget {
	private static final int TRACK_WIDTH = 22;
	private static final int TRACK_HEIGHT = 12;
	private static final int KNOB = 8;

	private boolean value;
	private final Consumer<Boolean> onToggle;

	public ModernToggle(int x, int y, int width, int height, Text label, boolean initial,
						Consumer<Boolean> onToggle) {
		super(x, y, width, height, label, button -> {
		}, DEFAULT_NARRATION_SUPPLIER);
		this.value = initial;
		this.onToggle = onToggle;
	}

	public boolean value() {
		return this.value;
	}

	public void setValue(boolean value) {
		this.value = value;
	}

	@Override
	public void onPress() {
		this.value = !this.value;
		super.onPress();

		if (this.onToggle != null) {
			this.onToggle.accept(this.value);
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer renderer = client.textRenderer;

		if (this.isHovered() && this.active) {
			ReplayTheme.fillRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
					6, 0x14FFFFFF);
		}

		int textY = this.getY() + (this.getHeight() - renderer.fontHeight) / 2 + 1;
		context.drawText(renderer, this.getMessage(), this.getX() + 8, textY,
				this.active ? ReplayTheme.TEXT : ReplayTheme.TEXT_DIM, true);

		int trackX = this.getX() + this.getWidth() - TRACK_WIDTH - 8;
		int trackY = this.getY() + (this.getHeight() - TRACK_HEIGHT) / 2;

		// レール
		ReplayTheme.fillRound(context, trackX, trackY, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2,
				this.value ? ReplayTheme.ACCENT_SOFT : ReplayTheme.SURFACE_INPUT);
		ReplayTheme.strokeRound(context, trackX, trackY, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2,
				this.value ? ReplayTheme.ACCENT : ReplayTheme.BORDER);

		// つまみ
		int knobX = this.value ? trackX + TRACK_WIDTH - KNOB - 2 : trackX + 2;
		ReplayTheme.fillRound(context, knobX, trackY + (TRACK_HEIGHT - KNOB) / 2, KNOB, KNOB, KNOB / 2,
				this.value ? ReplayTheme.ACCENT : ReplayTheme.TEXT_DIM);
	}
}
