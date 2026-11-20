package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Auto Tool の設定。 */
public final class AutoToolSettingsScreen extends GlassScreen {
	private static final int PANEL_W = 460;
	private static final int HEADER = 56;
	private static final int FOOTER = 40;
	private static final int PAD = 18;
	private static final int ROW_H = 46;
	private static final int PANEL_H = HEADER + ROW_H * 3 + FOOTER;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;

	public AutoToolSettingsScreen(Screen parent) {
		super(Text.literal("Auto Tool"));
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
		VapeWidgets.title(context, textRenderer, panelX + PAD, panelY + 14,
			"Auto Tool", "適正でない道具で掘り始めたら、人間の判断時間で持ち替える");

		toggleRow(context, 0, "人間らしさ", "判断時間・練習・たまの取り違えを入れる",
			cfg.autoToolHumanize, "at:human");
		toggleRow(context, 1, "掘っている間だけ", "攻撃キーを押している間だけ持ち替える",
			cfg.autoToolOnlyWhileMining, "at:mining");
		toggleRow(context, 2, "壊れかけを避ける", "残り耐久 10 以下の道具は使わない",
			cfg.autoToolAvoidBreaking, "at:avoid");

		int fy = panelY + PANEL_H - FOOTER;
		Ui.roundRect(context, panelX + PAD, fy, PANEL_W - PAD * 2.0, 1, 0.5, Ui.BORDER);
		Ui.text(context, textRenderer, "15% 以上速くならないなら持ち替えません",
			panelX + PAD, fy + 12, VapeWidgets.DIM, 0.8f);
		Ui.textRight(context, textRenderer, "RShift / ESC で戻る",
			panelX + PANEL_W - PAD, fy + 12, VapeWidgets.DIM, 0.8f);
	}

	private void toggleRow(DrawContext context, int index, String label, String note,
			boolean on, String key) {
		int y = rowY(index);
		if (index > 0) {
			Ui.roundRect(context, panelX + PAD, y - 6, PANEL_W - PAD * 2.0, 1, 0.5, 0x0FFFFFFF);
		}
		Ui.text(context, textRenderer, label, panelX + PAD, y, VapeWidgets.TEXT, 0.95f);
		Ui.text(context, textRenderer, note, panelX + PAD, y + 13, VapeWidgets.DIM, 0.72f);
		Ui.toggle(context, panelX + PANEL_W - PAD - 32, y - 1, 32, 16, Ui.anim(key, on));
	}

	private int rowY(int row) {
		return panelY + HEADER + 6 + row * ROW_H;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		for (int row = 0; row < 3; row++) {
			int y = rowY(row);
			if (click.x() >= panelX + PAD && click.x() <= panelX + PANEL_W - PAD
				&& click.y() >= y - 6 && click.y() <= y + 22) {
				switch (row) {
					case 0 -> cfg.autoToolHumanize = !cfg.autoToolHumanize;
					case 1 -> cfg.autoToolOnlyWhileMining = !cfg.autoToolOnlyWhileMining;
					default -> cfg.autoToolAvoidBreaking = !cfg.autoToolAvoidBreaking;
				}
				cfg.save();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
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
}
