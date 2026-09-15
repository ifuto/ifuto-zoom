package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * オン・オフのスイッチ（右側に小さなトグルを描く）。
 *
 * <p>バニラの ON/OFF ボタンは文字だけなので、見ただけで状態がわかるようにしている。
 */
public class ModernToggle extends ClickableWidget {
	private static final int TRACK_WIDTH = 22;
	private static final int TRACK_HEIGHT = 12;
	private static final int KNOB = 8;

	private boolean value;
	private final Consumer<Boolean> onToggle;

	public ModernToggle(int x, int y, int width, int height, Text label, boolean initial,
						Consumer<Boolean> onToggle) {
		super(x, y, width, height, label);
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
	public void onClick(Click click, boolean doubled) {
		this.press();
	}

	/** 選んだ状態で Enter / Space を押しても切り替わる */
	@Override
	public boolean keyPressed(KeyInput input) {
		if (!this.active || !this.visible || !ModernKeys.isActivation(input)) {
			return false;
		}

		this.press();
		return true;
	}

	public void press() {
		if (!this.active) {
			return;
		}

		this.value = !this.value;
		this.playDownSound(MinecraftClient.getInstance().getSoundManager());

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
		int labelMax = this.getWidth() - TRACK_WIDTH - 22;
		context.drawText(renderer, trim(renderer, this.getMessage(), labelMax), this.getX() + 8, textY,
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

		if (this.isFocused()) {
			ReplayTheme.strokeRound(context, this.getX() - 1, this.getY() - 1, this.getWidth() + 2,
					this.getHeight() + 2, 7, ReplayTheme.ACCENT);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		this.appendDefaultNarrations(builder);
	}

	/** 入りきらない文字は「…」で切る（切れない長さのときはそのまま） */
	private static Text trim(TextRenderer renderer, Text text, int maxWidth) {
		if (renderer.getWidth(text) <= maxWidth || maxWidth <= renderer.getWidth("…")) {
			return text;
		}

		return Text.literal(renderer.trimToWidth(text.getString(), maxWidth - renderer.getWidth("…")) + "…");
	}
}
