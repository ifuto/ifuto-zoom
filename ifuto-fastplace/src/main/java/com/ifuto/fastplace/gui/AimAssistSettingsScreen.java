package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Aim Assist の設定（The Client. の見た目）。
 *
 * <p>掴み方（クリックで掴む / 押している間だけ）、判定の倍率、掴む時間、
 * 進行方向のズレ、滑らかさなど、挙動を決める数値を全部ここから動かせる。</p>
 */
public final class AimAssistSettingsScreen extends GlassScreen {
	private static final int PANEL_W = 520;
	private static final int HEADER = 46;
	private static final int FOOTER = 32;
	private static final int PAD = 16;
	private static final int COL_GAP = 16;
	private static final int ROW_H = 44;
	private static final int COL_W = (PANEL_W - PAD * 2 - COL_GAP) / 2;
	private static final int PANEL_H = HEADER + ROW_H * 6 + FOOTER;

	private final Screen parent;
	private final FastPlaceConfig cfg;
	private final List<Control> controls = new ArrayList<>();

	private int panelX;
	private int panelY;
	private Control dragging;

	public AimAssistSettingsScreen(Screen parent) {
		super(Text.literal("Aim Assist"));
		this.parent = parent;
		this.cfg = FastPlaceConfig.get();
		buildControls();
	}

	private void buildControls() {
		addToggle("クリックで掴む", 0, 0, () -> cfg.aimAssistClickAcquire, v -> cfg.aimAssistClickAcquire = v);
		addSlider("掴む時間", 0.5, 10.0, 0.1, "s",
			() -> cfg.aimAssistStickMs / 1000.0, v -> cfg.aimAssistStickMs = v * 1000.0, 0, 1);
		addSlider("判定の倍率", 1.0, 1.6, 0.05, "x",
			() -> cfg.aimAssistClickBoxScale, v -> cfg.aimAssistClickBoxScale = v, 0, 2);
		addToggle("プレイヤーだけ", 0, 3, () -> cfg.aimAssistPlayersOnly, v -> cfg.aimAssistPlayersOnly = v);
		addToggle("人間らしさ", 0, 4, () -> cfg.aimAssistHumanize, v -> cfg.aimAssistHumanize = v);
		addToggle("剣・斧のみ", 0, 5, () -> cfg.aimAssistWeaponsOnly, v -> cfg.aimAssistWeaponsOnly = v);

		addSlider("強さ", 0.2, 1.0, 0.01, "",
			() -> cfg.aimAssistStrength, v -> cfg.aimAssistStrength = v, 1, 0);
		addSlider("視野", 15.0, 170.0, 1.0, "°",
			() -> cfg.aimAssistFov, v -> cfg.aimAssistFov = v, 1, 1);
		addSlider("滑らかさ", 0.4, 2.5, 0.05, "",
			() -> cfg.aimAssistSmoothing, v -> cfg.aimAssistSmoothing = v, 1, 2);
		addSlider("進行方向のズレ", -4.0, 4.0, 0.1, "°",
			() -> cfg.aimAssistStrafeBiasDeg, v -> cfg.aimAssistStrafeBiasDeg = v, 1, 3);
		addToggle("先読み", 1, 4, () -> cfg.aimAssistPredict, v -> cfg.aimAssistPredict = v);
		addToggle("押している間だけ", 1, 5, () -> cfg.aimAssistOnlyWhileAttacking,
			v -> cfg.aimAssistOnlyWhileAttacking = v);
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

		Ui.roundRect(context, panelX + PAD, panelY + HEADER - 1, PANEL_W - PAD * 2.0, 1, 0.5, Ui.BORDER);
		VapeWidgets.title(context, textRenderer, panelX + PAD, panelY + 14,
			"Aim Assist", "クリックした先の相手に、人間の速度で寄る");

		for (Control control : controls) {
			renderControl(context, control, mouseX, mouseY);
		}

		Ui.roundRect(context, panelX + PAD, panelY + PANEL_H - FOOTER, PANEL_W - PAD * 2.0, 1, 0.5,
			Ui.BORDER);
		VapeWidgets.text(context, textRenderer, "マウスは常に効きます（視点は奪いません）",
			panelX + PAD, panelY + PANEL_H - FOOTER + 11, VapeWidgets.DIM, 0.8f);
		Ui.textRight(context, textRenderer, "RShift / ESC で戻る",
			panelX + PANEL_W - PAD, panelY + PANEL_H - FOOTER + 11, VapeWidgets.DIM, 0.8f);
	}

	private void renderControl(DrawContext context, Control control, int mouseX, int mouseY) {
		int x = colX(control.column);
		int y = rowY(control.row);
		boolean hover = mouseX >= x && mouseX <= x + COL_W && mouseY >= y - 4 && mouseY <= y + 30;
		if (hover) {
			VapeWidgets.chamfer(context, x - 4, y - 6, COL_W + 8, 38, 3, VapeWidgets.HOVER);
		}
		VapeWidgets.text(context, textRenderer, control.label, x, y, VapeWidgets.TEXT, 0.95f);
		if (control.isToggle()) {
			boolean on = control.get() >= 0.5;
			VapeWidgets.pill(context, x + COL_W - 32, y - 1, 30, 15, on);
		} else {
			String value = VapeWidgets.format(control.get(), control.unit);
			VapeWidgets.text(context, textRenderer, value,
				x + COL_W - (int) (textRenderer.getWidth(value) * 0.85f), y, VapeWidgets.MUTED, 0.85f);
			double t = (control.get() - control.min) / (control.max - control.min);
			VapeWidgets.slider(context, x, y + 24, COL_W, t, hover);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		for (Control control : controls) {
			int x = colX(control.column);
			int y = rowY(control.row);
			if (click.x() < x || click.x() > x + COL_W || click.y() < y - 6 || click.y() > y + 32) {
				continue;
			}
			if (control.isToggle()) {
				control.set(control.get() >= 0.5 ? 0.0 : 1.0);
			} else {
				dragging = control;
				control.setValueAt(click.x());
			}
			cfg.save();
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging != null) {
			dragging.setValueAt(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			cfg.save();
			return true;
		}
		return super.mouseReleased(click);
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

	private int colX(int column) {
		return panelX + PAD + column * (COL_W + COL_GAP);
	}

	private int rowY(int row) {
		return panelY + HEADER + 10 + row * ROW_H;
	}

	// ------------------------------------------------------------------

	private void addSlider(String label, double min, double max, double step, String unit,
			DoubleSupplier getter, DoubleConsumer setter, int column, int row) {
		controls.add(new Control(label, false, min, max, step, unit, getter, setter, column, row));
	}

	private void addToggle(String label, int column, int row, BooleanSupplier getter, Consumer<Boolean> setter) {
		controls.add(new Control(label, true, 0, 1, 1, "",
			() -> getter.getAsBoolean() ? 1.0 : 0.0,
			v -> setter.accept(v >= 0.5), column, row));
	}

	private final class Control {
		final String label;
		final boolean toggle;
		final double min;
		final double max;
		final double step;
		final String unit;
		final DoubleSupplier getter;
		final DoubleConsumer setter;
		final int column;
		final int row;

		Control(String label, boolean toggle, double min, double max, double step, String unit,
				DoubleSupplier getter, DoubleConsumer setter, int column, int row) {
			this.label = label;
			this.toggle = toggle;
			this.min = min;
			this.max = max;
			this.step = step;
			this.unit = unit;
			this.getter = getter;
			this.setter = setter;
			this.column = column;
			this.row = row;
		}

		boolean isToggle() {
			return toggle;
		}

		double get() {
			return getter.getAsDouble();
		}

		void set(double value) {
			setter.accept(value);
		}

		/** トラック上の x から値を決める（step に丸める） */
		void setValueAt(double mouseX) {
			int x = colX(column);
			double t = Math.max(0.0, Math.min(1.0, (mouseX - x) / COL_W));
			double raw = min + (max - min) * t;
			double snapped = step <= 1.0e-9 ? raw : Math.round(raw / step) * step;
			set(Math.max(min, Math.min(max, snapped)));
		}
	}
}
