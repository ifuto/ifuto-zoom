package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 押すたびに切り替わるボタン（項目名は左、いまの値は右）。
 *
 * <p>バニラの「値つきボタン」は1行の文字に全部入れるので長くなりがち。
 * ここでは **左に名前、右に値** と分けて、見やすくしている。
 */
public class ModernCycling<T> extends ButtonWidget {
	private static final int PADDING = 8;

	private final List<T> values;
	private final Function<T, Text> formatter;
	private final Consumer<T> onChanged;
	private int index;

	public ModernCycling(int x, int y, int width, int height, Text label, List<T> values, T initial,
						 Function<T, Text> formatter, Consumer<T> onChanged) {
		super(x, y, width, height, label, button -> {
		}, DEFAULT_NARRATION_SUPPLIER);
		this.values = values;
		this.formatter = formatter;
		this.onChanged = onChanged;
		this.index = Math.max(0, values.indexOf(initial));
	}

	/** 押すたびに次へ */
	@Override
	public void onPress() {
		if (this.values.isEmpty()) {
			return;
		}

		this.index = (this.index + 1) % this.values.size();
		super.onPress();

		if (this.onChanged != null) {
			this.onChanged.accept(this.values.get(this.index));
		}
	}

	public T value() {
		return this.values.isEmpty() ? null : this.values.get(this.index);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer renderer = client.textRenderer;

		ReplayTheme.fillRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 6,
				this.isHovered() && this.active ? 0xF2262E36 : ReplayTheme.SURFACE_RAISED);
		ReplayTheme.strokeRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 6,
				ReplayTheme.BORDER);

		int textY = this.getY() + (this.getHeight() - renderer.fontHeight) / 2 + 1;

		// 左: 項目名（弱い色）
		context.drawText(renderer, this.getMessage(), this.getX() + PADDING, textY,
				this.active ? ReplayTheme.TEXT_DIM : ReplayTheme.withAlpha(ReplayTheme.TEXT_DIM, 0x99), true);

		// 右: いまの値（アクセント色）
		Text value = this.formatter.apply(this.value());
		int valueWidth = renderer.getWidth(value);
		context.drawText(renderer, value, this.getX() + this.getWidth() - PADDING - valueWidth, textY,
				this.active ? ReplayTheme.ACCENT : ReplayTheme.TEXT_DIM, true);

		if (this.isFocused()) {
			ReplayTheme.strokeRound(context, this.getX() - 1, this.getY() - 1, this.getWidth() + 2,
					this.getHeight() + 2, 7, ReplayTheme.ACCENT);
		}
	}
}
