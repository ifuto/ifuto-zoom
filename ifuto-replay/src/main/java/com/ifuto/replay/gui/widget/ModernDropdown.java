package com.ifuto.replay.gui.widget;

import com.ifuto.replay.gui.theme.ReplayTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 押すと下に選択肢が出るボタン（項目名は左、いまの値は右）。
 *
 * <p>開いているあいだは画面全体に受け止めを置くので、外を押したら閉じる。
 * 選択肢が多いときはホイールで流せる。
 */
public class ModernDropdown<T> extends ClickableWidget {
	private static final int PADDING = 8;
	private static final int OPTION_HEIGHT = 18;
	private static final int MAX_VISIBLE = 8;
	private static final String ARROW = "▼";

	private final Screen screen;
	private final List<T> values;
	private final Function<T, Text> formatter;
	private final Consumer<T> onChanged;
	private int index;
	private boolean open;
	private int firstVisible;
	private final List<ClickableWidget> popup = new ArrayList<>();
	private final List<OptionRow> options = new ArrayList<>();
	private Panel panel;

	public ModernDropdown(Screen screen, int x, int y, int width, int height, Text label, List<T> values,
			T initial, Function<T, Text> formatter, Consumer<T> onChanged) {
		super(x, y, width, height, label);
		this.screen = screen;
		this.values = values;
		this.formatter = formatter;
		this.onChanged = onChanged;
		this.index = Math.max(0, values.indexOf(initial));
	}

	public T value() {
		return this.values.isEmpty() ? null : this.values.get(this.index);
	}

	public void setValue(T value) {
		int found = this.values.indexOf(value);

		if (found >= 0) {
			this.index = found;
		}
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		this.toggle();
	}

	/** 選んだ状態で Enter / Space を押しても開閉する */
	@Override
	public boolean keyPressed(KeyInput input) {
		if (!this.active || !this.visible || !ModernKeys.isActivation(input)) {
			return false;
		}

		this.toggle();
		return true;
	}

	public void toggle() {
		if (!this.active || this.values.isEmpty()) {
			return;
		}

		if (this.open) {
			this.close();
		} else {
			this.open();
		}
	}

	private void open() {
		this.open = true;
		this.firstVisible = 0;
		this.playDownSound(MinecraftClient.getInstance().getSoundManager());

		// 外を押したら閉じる受け止め（いちばん下に敷く）
		Catcher catcher = new Catcher(this, this.screen.width, this.screen.height);
		this.screen.addDrawableChild(catcher);
		this.popup.add(catcher);

		int shown = Math.min(this.values.size(), MAX_VISIBLE);
		int listHeight = shown * OPTION_HEIGHT;
		int panelHeight = listHeight + 8;
		int px = this.getX();
		int py = this.getY() + this.getHeight() + 2;

		// 下に入らなければ上に出す
		if (py + panelHeight > this.screen.height) {
			py = this.getY() - panelHeight - 2;
		}

		this.panel = new Panel(px, py, this.getWidth(), panelHeight);
		this.screen.addDrawableChild(this.panel);
		this.popup.add(this.panel);

		for (int i = 0; i < this.values.size(); i++) {
			OptionRow row = new OptionRow(this, i, px + 4, py + 4 + i * OPTION_HEIGHT,
					this.getWidth() - 8, OPTION_HEIGHT);
			row.visible = i < shown;
			this.options.add(row);
			this.screen.addDrawableChild(row);
			this.popup.add(row);
		}
	}

	private void close() {
		if (!this.open) {
			return;
		}

		this.open = false;
		this.options.clear();
		this.panel = null;

		for (ClickableWidget widget : this.popup) {
			this.screen.remove(widget);
		}

		this.popup.clear();
	}

	private void select(int which) {
		if (which < 0 || which >= this.values.size()) {
			return;
		}

		this.index = which;
		this.playDownSound(MinecraftClient.getInstance().getSoundManager());
		this.close();

		if (this.onChanged != null) {
			this.onChanged.accept(this.values.get(which));
		}
	}

	/**
	 * 選択肢を流す（ホイール用）。流せたら true。
	 */
	private boolean scrollBy(int amount) {
		if (!this.open || this.values.size() <= MAX_VISIBLE) {
			return false;
		}

		int max = this.values.size() - MAX_VISIBLE;
		int next = Math.max(0, Math.min(max, this.firstVisible + amount));

		if (next == this.firstVisible) {
			return true;
		}

		this.firstVisible = next;

		for (int i = 0; i < this.options.size(); i++) {
			OptionRow row = this.options.get(i);
			boolean shown = i >= next && i < next + MAX_VISIBLE;
			row.visible = shown;

			if (shown && this.panel != null) {
				row.setY(this.panel.getY() + 4 + (i - next) * OPTION_HEIGHT);
			}
		}

		return true;
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer renderer = client.textRenderer;

		ReplayTheme.fillRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 6,
				(this.isHovered() || this.open) && this.active ? 0xF2262E36 : ReplayTheme.SURFACE_RAISED);
		ReplayTheme.strokeRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 6,
				this.open ? ReplayTheme.ACCENT : ReplayTheme.BORDER);

		int textY = this.getY() + (this.getHeight() - renderer.fontHeight) / 2 + 1;

		// 右端: 開く印
		int arrowWidth = renderer.getWidth(ARROW);
		int arrowX = this.getX() + this.getWidth() - PADDING - arrowWidth;
		context.drawText(renderer, ARROW, arrowX, textY,
				this.active ? ReplayTheme.TEXT_DIM : ReplayTheme.withAlpha(ReplayTheme.TEXT_DIM, 0x99), false);

		// 右: いまの値（アクセント色）
		int zoneRight = arrowX - 4;
		Text value = this.formatter.apply(this.value());
		int valueWidth = renderer.getWidth(value);

		if (valueWidth > zoneRight - (this.getX() + PADDING)) {
			// 値だけでいっぱいのときは値を流して全部見せる（項目名は出さない）
			MarqueeText.draw(context, renderer, value, this.getX() + PADDING, textY,
					zoneRight - this.getX() - PADDING,
					this.active ? ReplayTheme.ACCENT : ReplayTheme.TEXT_DIM);
		} else {
			context.drawText(renderer, value, zoneRight - valueWidth, textY,
					this.active ? ReplayTheme.ACCENT : ReplayTheme.TEXT_DIM, false);

			// 左: 項目名（弱い色。値にぶつからない範囲で。入りきらなければ流れる）
			int labelMax = zoneRight - valueWidth - 4 - this.getX() - PADDING;
			MarqueeText.draw(context, renderer, this.getMessage(), this.getX() + PADDING, textY, labelMax,
					this.active ? ReplayTheme.TEXT_DIM : ReplayTheme.withAlpha(ReplayTheme.TEXT_DIM, 0x99));
		}

		if (this.isFocused()) {
			ReplayTheme.strokeRound(context, this.getX() - 1, this.getY() - 1, this.getWidth() + 2,
					this.getHeight() + 2, 7, ReplayTheme.ACCENT);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		this.appendDefaultNarrations(builder);
	}

	/** 画面全体の受け止め（外を押したら閉じる。ホイールは流すか閉じる） */
	private static final class Catcher extends ClickableWidget {
		private final ModernDropdown<?> parent;

		Catcher(ModernDropdown<?> parent, int screenWidth, int screenHeight) {
			super(0, 0, screenWidth, screenHeight, Text.empty());
			this.parent = parent;
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			this.parent.close();
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
				double verticalAmount) {
			if (this.parent.scrollBy(verticalAmount < 0.0 ? 1 : -1)) {
				return true;
			}

			this.parent.close();
			return true;
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			// 何も描かない（透明な受け止め）
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			// 読み上げない
		}
	}

	/** 選択肢の下に敷く板（ただの見た目。押しても何もしない） */
	private static final class Panel extends ClickableWidget {
		Panel(int x, int y, int width, int height) {
			super(x, y, width, height, Text.empty());
			this.active = false;
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			// 何もしない（隙間の押下は受け止めに落ちて閉じる）
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			ReplayTheme.panel(context, this.getX(), this.getY(), this.getWidth(), this.getHeight());
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			// 読み上げない
		}
	}

	/** 選択肢の1行 */
	private final class OptionRow extends ClickableWidget {
		private final ModernDropdown<T> parent;
		private final int which;

		OptionRow(ModernDropdown<T> parent, int which, int x, int y, int width, int height) {
			super(x, y, width, height, parent.formatter.apply(parent.values.get(which)));
			this.parent = parent;
			this.which = which;
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			this.parent.select(this.which);
		}

		@Override
		public boolean keyPressed(KeyInput input) {
			if (!this.active || !this.visible || !ModernKeys.isActivation(input)) {
				return false;
			}

			this.parent.select(this.which);
			return true;
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			if (!this.visible) {
				return;
			}

			boolean selected = this.which == this.parent.index;

			if (this.isHovered() || selected) {
				ReplayTheme.fillRound(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 4,
						selected ? ReplayTheme.ACCENT_SOFT : 0x14FFFFFF);
			}

			TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
			int textY = this.getY() + (this.getHeight() - renderer.fontHeight) / 2 + 1;
			MarqueeText.draw(context, renderer, this.getMessage(), this.getX() + 6, textY,
					this.getWidth() - 12, selected ? ReplayTheme.ACCENT : ReplayTheme.TEXT);
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			this.appendDefaultNarrations(builder);
		}
	}
}
