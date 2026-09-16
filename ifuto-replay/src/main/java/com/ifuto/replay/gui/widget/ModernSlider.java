package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/**
 * 今っぽい見た目のスライダー（細い線 + 丸いつまみ）。
 *
 * <p>バニラのスライダーは「部品の絵」をそのまま使うので、ここでも描き方だけ差し替えている。
 * 文字は上、線は下に置いて、見た目を軽くしている。
 */
public class ModernSlider extends SliderWidget {
	private static final int TRACK_HEIGHT = 3;
	private static final int KNOB = 8;
	private static final int PADDING = 6;

	private final String labelKey;
	private final int min;
	private final int max;
	private final ValueFormatter formatter;
	private final IntConsumer setter;

	public ModernSlider(int x, int y, int width, int height, String labelKey, int min, int max, int initial,
						ValueFormatter formatter, IntConsumer setter) {
		super(x, y, width, height, Text.empty(),
				max == min ? 0.0 : (double) (initial - min) / (double) (max - min));
		this.labelKey = labelKey;
		this.min = min;
		this.max = max;
		this.formatter = formatter;
		this.setter = setter;
		this.updateMessage();
	}

	public int intValue() {
		return this.min + (int) Math.round((this.max - this.min) * this.value);
	}

	@Override
	protected void updateMessage() {
		this.setMessage(Text.translatable(this.labelKey, Text.literal(this.formatter.format(this.intValue()))));
	}

	@Override
	protected void applyValue() {
		this.setter.accept(this.intValue());
	}

	@Override
	public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer renderer = client.textRenderer;
		boolean active = this.active;

		int textY = this.getY() + 2;
		context.drawText(renderer, this.getMessage(), this.getX() + PADDING, textY,
				active ? ReplayTheme.TEXT : ReplayTheme.TEXT_DIM, false);

		int trackY = this.getY() + this.getHeight() - TRACK_HEIGHT - 2;
		int trackX = this.getX() + PADDING;
		int trackWidth = this.getWidth() - PADDING * 2;

		// レール
		ReplayTheme.fillRound(context, trackX, trackY + 1, trackWidth, TRACK_HEIGHT - 1, 1,
				ReplayTheme.SURFACE_INPUT);

		// 進んだぶん
		int filled = (int) Math.round(trackWidth * this.value);

		if (filled > 0) {
			ReplayTheme.fillRound(context, trackX, trackY + 1, Math.max(2, filled), TRACK_HEIGHT - 1, 1,
					active ? ReplayTheme.ACCENT : ReplayTheme.TEXT_DIM);
		}

		// つまみ
		int knobX = trackX + filled - KNOB / 2;
		knobX = Math.max(trackX - 1, Math.min(trackX + trackWidth - KNOB + 1, knobX));
		int knobY = trackY + (TRACK_HEIGHT - KNOB) / 2;
		ReplayTheme.fillRound(context, knobX, knobY, KNOB, KNOB, KNOB / 2,
				active ? ReplayTheme.TEXT : ReplayTheme.TEXT_DIM);

		if (this.isFocused()) {
			ReplayTheme.strokeRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
					4, ReplayTheme.ACCENT);
		}
	}

	public interface ValueFormatter {
		String format(int value);
	}
}
