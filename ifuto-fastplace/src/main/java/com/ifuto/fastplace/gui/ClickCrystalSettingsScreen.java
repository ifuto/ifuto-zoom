package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * ClickCrystal の設定（The Client. の見た目）。
 */
public final class ClickCrystalSettingsScreen extends Screen {
	private static final int PANEL_W = 420;
	private static final int HEADER = 46;
	private static final int FOOTER = 32;
	private static final int PAD = 16;
	private static final int ROW_H = 44;
	private static final int TRACK_W = PANEL_W - PAD * 2;
	private static final int PANEL_H = HEADER + ROW_H * 4 + FOOTER;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;
	private int dragging = -1;   // 0: 間隔 / 1: 連続時間 / 2: 距離

	public ClickCrystalSettingsScreen(Screen parent) {
		super(Text.literal("ClickCrystal"));
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

		context.fill(panelX + 1, panelY + HEADER - 1, panelX + PANEL_W - 1, panelY + HEADER, VapeWidgets.BORDER);
		VapeWidgets.text(context, textRenderer, "CLICK CRYSTAL", panelX + PAD, panelY + 13, VapeWidgets.TEXT, 1.2f);
		VapeWidgets.text(context, textRenderer, "黒曜石を左クリック → クリスタルに持ち替えて連続爆破",
			panelX + PAD, panelY + 29, VapeWidgets.DIM, 0.8f);

		sliderRow(context, 0, "間隔", VapeWidgets.format(cfg.clickCrystalIntervalMs, "ms"),
			(cfg.clickCrystalIntervalMs - 20) / 280.0, mouseX, mouseY);
		sliderRow(context, 1, "連続時間", VapeWidgets.format(cfg.clickCrystalBurstMs, "ms"),
			(cfg.clickCrystalBurstMs - 100) / 4900.0, mouseX, mouseY);
		sliderRow(context, 2, "届く距離", VapeWidgets.format(cfg.clickCrystalRange, "b"),
			(cfg.clickCrystalRange - 1.0) / 5.0, mouseX, mouseY);

		int toggleY = rowY(3);
		VapeWidgets.text(context, textRenderer, "終わったら元のスロットに戻す", panelX + PAD, toggleY,
			VapeWidgets.TEXT, 0.95f);
		VapeWidgets.pill(context, panelX + PANEL_W - PAD - 30, toggleY - 1, 30, 15, cfg.clickCrystalRestoreSlot);

		context.fill(panelX + 1, panelY + PANEL_H - FOOTER, panelX + PANEL_W - 1, panelY + PANEL_H - FOOTER + 1,
			VapeWidgets.BORDER);
		VapeWidgets.text(context, textRenderer, "対象: 黒曜石 / 岩盤（上が空気）",
			panelX + PAD, panelY + PANEL_H - FOOTER + 11, VapeWidgets.DIM, 0.8f);
		VapeWidgets.text(context, textRenderer, "RShift / ESC で戻る",
			panelX + PANEL_W - PAD - (int) (textRenderer.getWidth("RShift / ESC で戻る") * 0.8f),
			panelY + PANEL_H - FOOTER + 11, VapeWidgets.DIM, 0.8f);
	}

	private void sliderRow(DrawContext context, int row, String label, String value, double t,
			int mouseX, int mouseY) {
		int x = panelX + PAD;
		int y = rowY(row);
		boolean hover = mouseX >= x && mouseX <= x + TRACK_W && mouseY >= y - 4 && mouseY <= y + 30;
		VapeWidgets.text(context, textRenderer, label, x, y, VapeWidgets.TEXT, 0.95f);
		VapeWidgets.text(context, textRenderer, value,
			x + TRACK_W - (int) (textRenderer.getWidth(value) * 0.85f), y, VapeWidgets.MUTED, 0.85f);
		VapeWidgets.slider(context, x, y + 24, TRACK_W, t, hover);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		int x = panelX + PAD;
		for (int row = 0; row < 3; row++) {
			int y = rowY(row);
			if (click.x() >= x && click.x() <= x + TRACK_W && click.y() >= y + 12 && click.y() <= y + 36) {
				dragging = row;
				apply(row, click.x());
				return true;
			}
		}
		int toggleY = rowY(3);
		if (click.x() >= panelX + PANEL_W - PAD - 34 && click.x() <= panelX + PANEL_W - PAD
			&& click.y() >= toggleY - 4 && click.y() <= toggleY + 18) {
			cfg.clickCrystalRestoreSlot = !cfg.clickCrystalRestoreSlot;
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
		switch (row) {
			case 0 -> cfg.clickCrystalIntervalMs = (int) Math.round(20 + 280 * t);
			case 1 -> cfg.clickCrystalBurstMs = (int) Math.round(100 + 4900 * t);
			default -> cfg.clickCrystalRange = Math.round((1.0 + 5.0 * t) * 10.0) / 10.0;
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
		return panelY + HEADER + 10 + row * ROW_H;
	}
}
