package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.FastPlaceModule;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * FastPlace の設定。
 *
 * <p>HTML のモックに寄せて、面取りをやめた角丸パネル + 等倍フォント + 角丸スライダー。
 * バニラの {@code ButtonWidget}（四角いボタン）も使わない。</p>
 */
public final class FastPlaceConfigScreen extends GlassScreen {
	private static final int PANEL_W = 460;
	private static final int HEADER = 56;
	private static final int FOOTER = 40;
	private static final int PAD = 18;
	private static final int ROW_H = 46;
	private static final int TRACK_W = PANEL_W - PAD * 2;
	private static final int PANEL_H = HEADER + ROW_H * 2 + 44 + FOOTER;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;
	private boolean dragging;

	public FastPlaceConfigScreen(Screen parent) {
		super(Text.literal("FastPlace"));
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
			"FastPlace", "右クリックを押している間、人間のテンポで置き続ける");

		toggleRow(context, 0, "モジュールを有効にする", "R キーでも切り替えられます",
			cfg.enabled, "fp:enabled");
		toggleRow(context, 1, "CPS を表示", "連打中だけ画面右上に CPS を出す",
			cfg.hudCps, "fp:cps");

		int rowY = rowY(2);
		double cps = 1000.0 / Math.max(1, cfg.intervalMs);
		Ui.text(context, textRenderer, "設置間隔", panelX + PAD, rowY, VapeWidgets.TEXT, 0.95f);
		Ui.textRight(context, textRenderer,
			cfg.intervalMs + " ms  (" + String.format(java.util.Locale.ROOT, "%.1f", cps) + " CPS)",
			panelX + PANEL_W - PAD, rowY, VapeWidgets.MUTED, 0.9f);
		boolean hover = mouseX >= panelX + PAD && mouseX <= panelX + PANEL_W - PAD
			&& mouseY >= rowY + 8 && mouseY <= rowY + 34;
		VapeWidgets.slider(context, panelX + PAD, rowY + 24, TRACK_W,
			(cfg.intervalMs - FastPlaceConfig.MIN_INTERVAL_MS)
				/ (double) (FastPlaceConfig.MAX_INTERVAL_MS - FastPlaceConfig.MIN_INTERVAL_MS),
			hover || dragging);

		int fy = panelY + PANEL_H - FOOTER;
		Ui.roundRect(context, panelX + PAD, fy, PANEL_W - PAD * 2.0, 1, 0.5, Ui.BORDER);
		Ui.text(context, textRenderer, "置くときはバニラと同じ操作（interactBlock + 腕振り）",
			panelX + PAD, fy + 12, VapeWidgets.DIM, 0.8f);
		Ui.textRight(context, textRenderer, "RShift / ESC で戻る",
			panelX + PANEL_W - PAD, fy + 12, VapeWidgets.DIM, 0.8f);
	}

	private void toggleRow(DrawContext context, int index, String label, String note,
			boolean on, String key) {
		int y = rowY(index);
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
		for (int row = 0; row < 2; row++) {
			int y = rowY(row);
			if (click.x() >= panelX + PAD && click.x() <= panelX + PANEL_W - PAD
				&& click.y() >= y - 6 && click.y() <= y + 22) {
				if (row == 0) {
					FastPlaceModule.setEnabled(!cfg.enabled);
				} else {
					cfg.hudCps = !cfg.hudCps;
					cfg.save();
				}
				return true;
			}
		}
		int y = rowY(2);
		if (click.x() >= panelX + PAD && click.x() <= panelX + PANEL_W - PAD
			&& click.y() >= y + 8 && click.y() <= y + 34) {
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
		double t = Math.max(0.0, Math.min(1.0,
			(mouseX - (panelX + PAD)) / (double) TRACK_W));
		cfg.intervalMs = (int) Math.round(FastPlaceConfig.MIN_INTERVAL_MS
			+ (FastPlaceConfig.MAX_INTERVAL_MS - FastPlaceConfig.MIN_INTERVAL_MS) * t);
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
}
