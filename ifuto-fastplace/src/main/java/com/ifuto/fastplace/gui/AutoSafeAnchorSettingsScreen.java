package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Auto Safe Anchor の設定。 */
public final class AutoSafeAnchorSettingsScreen extends GlassScreen {
	private static final int PANEL_W = 470;
	private static final int HEADER = 56;
	private static final int FOOTER = 34;
	private static final int PAD = 18;
	private static final int ROW_H = 46;
	private static final int TRACK_W = PANEL_W - PAD * 2;
	private static final int PANEL_H = HEADER + ROW_H * 2 + 44 + FOOTER;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;
	private int dragging = -1;

	public AutoSafeAnchorSettingsScreen(Screen parent) {
		super(Text.literal("Auto Safe Anchor"));
		this.parent = parent;
		this.cfg = FastPlaceConfig.get();
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		VapeWidgets.panel(context, panelX, panelY, PANEL_W, PANEL_H);
		VapeWidgets.title(context, textRenderer, panelX + PAD, panelY + 16,
			"Auto Safe Anchor", "置いた瞬間にチャージ → 壁 → 起爆まで");

		sliderRow(context, 0, "振り向く速さ", VapeWidgets.format(cfg.safeAnchorAimSpeed, "°/s"),
			(cfg.safeAnchorAimSpeed - 60.0) / 840.0, mouseX, mouseY);
		sliderRow(context, 1, "操作どうしの間隔", cfg.safeAnchorStepDelayMs + " ms",
			(cfg.safeAnchorStepDelayMs - 50) / 350.0, mouseX, mouseY);

		int toggleY = rowY(2);
		Ui.text(context, textRenderer, "終わったら元のスロットに戻す",
			panelX + PAD, toggleY, VapeWidgets.TEXT, 0.95f);
		Ui.toggle(context, panelX + PANEL_W - PAD - 32, toggleY - 2, 32, 16,
			Ui.anim("sa:restore", cfg.safeAnchorRestoreSlot));

		Ui.roundRect(context, panelX + PAD, panelY + PANEL_H - FOOTER,
			PANEL_W - PAD * 2.0, 1, 0.5, Ui.BORDER);
		Ui.text(context, textRenderer, "手前に 1 ブロック置ける空間があるときだけ動きます",
			panelX + PAD, panelY + PANEL_H - FOOTER + 12, VapeWidgets.DIM, 0.8f);
		Ui.textRight(context, textRenderer, "RShift / ESC で戻る",
			panelX + PANEL_W - PAD, panelY + PANEL_H - FOOTER + 12, VapeWidgets.DIM, 0.8f);
	}

	private void sliderRow(DrawContext context, int row, String label, String value, double t,
			int mouseX, int mouseY) {
		int x = panelX + PAD;
		int y = rowY(row);
		boolean hover = mouseX >= x && mouseX <= x + TRACK_W && mouseY >= y - 4 && mouseY <= y + 30;
		Ui.text(context, textRenderer, label, x, y, VapeWidgets.TEXT, 0.95f);
		Ui.textRight(context, textRenderer, value, x + TRACK_W, y, VapeWidgets.MUTED, 0.9f);
		VapeWidgets.slider(context, x, y + 26, TRACK_W, t, hover);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		int x = panelX + PAD;
		for (int row = 0; row < 2; row++) {
			int y = rowY(row);
			if (click.x() >= x && click.x() <= x + TRACK_W
				&& click.y() >= y + 14 && click.y() <= y + 38) {
				dragging = row;
				apply(row, click.x());
				return true;
			}
		}
		int toggleY = rowY(2);
		if (click.x() >= panelX + PANEL_W - PAD - 36 && click.x() <= panelX + PANEL_W - PAD
			&& click.y() >= toggleY - 5 && click.y() <= toggleY + 20) {
			cfg.safeAnchorRestoreSlot = !cfg.safeAnchorRestoreSlot;
			cfg.save();
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging >= 0) {
			apply(dragging, click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging >= 0) {
			dragging = -1;
			cfg.save();
			return true;
		}
		return super.mouseReleased(click);
	}

	private void apply(int row, double mouseX) {
		int x = panelX + PAD;
		double t = Math.max(0.0, Math.min(1.0, (mouseX - x) / TRACK_W));
		if (row == 0) {
			cfg.safeAnchorAimSpeed = Math.round((60 + 840 * t) / 10.0) * 10.0;
		} else {
			cfg.safeAnchorStepDelayMs = (int) Math.round(50 + 350 * t);
		}
		cfg.save();
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE || keyInput.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			close();
			return true;
		}
		return super.keyPressed(keyInput);
	}

	@Override
	public void close() {
		cfg.save();
		client.setScreen(parent);
	}

	private int rowY(int row) {
		return panelY + HEADER + 8 + row * ROW_H;
	}
}
