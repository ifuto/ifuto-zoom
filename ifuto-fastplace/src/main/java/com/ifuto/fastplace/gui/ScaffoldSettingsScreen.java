package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Scaffold（God Bridge）の設定。 */
public final class ScaffoldSettingsScreen extends GlassScreen {
	private static final int PANEL_W = 470;
	private static final int HEADER = 56;
	private static final int FOOTER = 46;
	private static final int PAD = 18;
	private static final int ROW_H = 42;
	private static final int TRACK_W = PANEL_W - PAD * 2;
	private static final int PANEL_H = HEADER + ROW_H * 4 + 44 + FOOTER;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;
	private boolean dragging;

	public ScaffoldSettingsScreen(Screen parent) {
		super(Text.literal("Scaffold"));
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
			"Scaffold", "ブロックを持って前へ歩くと、足元の続きを自動で置く");

		toggleRow(context, 0, "ジャンプを抑止", "God Bridge 中はスペースを押しても跳ばない",
			cfg.scaffoldBlockJump, "sc:jump");
		toggleRow(context, 1, "視点を前のまま", "体は下を向いたまま、カメラだけ前を向いて歩ける",
			cfg.scaffoldKeepView, "sc:view");
		toggleRow(context, 2, "人間らしさ", "サッケード・手ブレ・設置リズムの揺らぎを入れる",
			cfg.scaffoldHumanize, "sc:human");
		toggleRow(context, 3, "腕を振る", "置いたときに手を振る（バニラと同じ）",
			cfg.scaffoldSwing, "sc:swing");

		int rowY = rowY(4) - 6;
		Ui.text(context, textRenderer, "設置ペース", panelX + PAD, rowY, VapeWidgets.TEXT, 0.95f);
		Ui.textRight(context, textRenderer,
			String.format(Locale.ROOT, "%.2fx", cfg.scaffoldCadence),
			panelX + PANEL_W - PAD, rowY, VapeWidgets.MUTED, 0.9f);
		boolean hover = mouseX >= panelX + PAD && mouseX <= panelX + PANEL_W - PAD
			&& mouseY >= rowY + 6 && mouseY <= rowY + 30;
		VapeWidgets.slider(context, panelX + PAD, rowY + 22, TRACK_W,
			(cfg.scaffoldCadence - 0.6) / 0.8, hover || dragging);

		int fy = panelY + PANEL_H - FOOTER;
		Ui.roundRect(context, panelX + PAD, fy, PANEL_W - PAD * 2.0, 1, 0.5, Ui.BORDER);
		Ui.text(context, textRenderer, "実際の設置間隔は歩く速さで決まります",
			panelX + PAD, fy + 11, VapeWidgets.DIM, 0.8f);
		Ui.text(context, textRenderer, "OFF / ブロックを手放す / 画面を開く と、人の速度で視点が戻ります",
			panelX + PAD, fy + 25, VapeWidgets.DIM, 0.75f);
	}

	private void toggleRow(DrawContext context, int index, String label, String note,
			boolean on, String key) {
		int y = rowY(index);
		if (index > 0) {
			Ui.roundRect(context, panelX + PAD, y - 5, PANEL_W - PAD * 2.0, 1, 0.5, 0x0FFFFFFF);
		}
		Ui.text(context, textRenderer, label, panelX + PAD, y, VapeWidgets.TEXT, 0.95f);
		Ui.text(context, textRenderer, note, panelX + PAD, y + 12, VapeWidgets.DIM, 0.72f);
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
		for (int row = 0; row < 4; row++) {
			int y = rowY(row);
			if (click.x() >= panelX + PAD && click.x() <= panelX + PANEL_W - PAD
				&& click.y() >= y - 6 && click.y() <= y + 22) {
				switch (row) {
					case 0 -> cfg.scaffoldBlockJump = !cfg.scaffoldBlockJump;
					case 1 -> cfg.scaffoldKeepView = !cfg.scaffoldKeepView;
					case 2 -> cfg.scaffoldHumanize = !cfg.scaffoldHumanize;
					default -> cfg.scaffoldSwing = !cfg.scaffoldSwing;
				}
				cfg.save();
				return true;
			}
		}
		int sliderY = rowY(4) - 6 + 22;
		if (click.x() >= panelX + PAD && click.x() <= panelX + PANEL_W - PAD
			&& click.y() >= sliderY - 10 && click.y() <= sliderY + 12) {
			dragging = true;
			apply(click.x());
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging) {
			apply(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging) {
			dragging = false;
			cfg.save();
			return true;
		}
		return super.mouseReleased(click);
	}

	private void apply(double mouseX) {
		double t = Math.max(0.0, Math.min(1.0, (mouseX - (panelX + PAD)) / (double) TRACK_W));
		double value = Math.round((0.6 + t * 0.8) * 20.0) / 20.0;
		if (value != cfg.scaffoldCadence) {
			cfg.scaffoldCadence = value;
			cfg.save();
		}
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
