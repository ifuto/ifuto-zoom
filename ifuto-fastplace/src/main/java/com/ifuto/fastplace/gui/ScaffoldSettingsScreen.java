package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Scaffold（God Bridge）の設定。
 */
public final class ScaffoldSettingsScreen extends GlassScreen {
	private static final int PANEL_W = 430;
	private static final int PANEL_H = 336;
	private static final int ACCENT = 0xFF16A37F;
	private static final int PANEL_BG = 0xFF181819;
	private static final int CARD_BG = 0xFF1F1F21;
	private static final int BORDER = 0xFF242426;
	private static final int TRACK = 0xFF2C2C36;
	private static final int TRACK_OFF = 0xFF2A2A2E;
	private static final int TEXT_DIM = 0xFF9E9EA4;

	private static final int ROW_H = 38;
	private static final int ROW_GAP = 6;
	private static final int ROW_START = 50;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;
	private int sliderX;
	private int sliderY;
	private int sliderW;
	private boolean dragging;

	public ScaffoldSettingsScreen(Screen parent) {
		super(Text.literal("Scaffold 設定"));
		this.parent = parent;
		this.cfg = FastPlaceConfig.get();
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;
		sliderX = panelX + 22;
		sliderW = PANEL_W - 44;
		sliderY = panelY + 250;

		addDrawableChild(ButtonWidget.builder(Text.literal("完了"), b -> close())
			.dimensions(panelX + PANEL_W - 90, panelY + PANEL_H - 32, 68, 22).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);

		context.fill(panelX + 3, panelY + 4, panelX + PANEL_W + 3, panelY + PANEL_H + 4, 0x55000000);
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
		border(context, panelX, panelY, PANEL_W, PANEL_H, BORDER);
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + 2, ACCENT);

		context.drawTextWithShadow(textRenderer,
			Text.literal("SCAFFOLD").formatted(Formatting.WHITE)
				.append(Text.literal("  God Bridge").formatted(Formatting.GRAY)),
			panelX + 20, panelY + 13, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer,
			Text.literal("ブロックを持って前へ歩くと、足元の続きを自動で置きます"),
			panelX + 20, panelY + 28, TEXT_DIM);

		toggleRow(context, mouseX, mouseY, 0, "ジャンプを抑止",
			"God Bridge 中はスペースを押しても跳ばない", cfg.scaffoldBlockJump);
		toggleRow(context, mouseX, mouseY, 1, "視点を前のまま",
			"体は下を向いたまま、カメラだけ前を向いて歩ける", cfg.scaffoldKeepView);
		toggleRow(context, mouseX, mouseY, 2, "人間らしさ",
			"サッケード・手ブレ・設置リズムの揺らぎを入れる", cfg.scaffoldHumanize);
		toggleRow(context, mouseX, mouseY, 3, "腕を振る",
			"置いたときに手を振る（バニラと同じ）", cfg.scaffoldSwing);

		// 設置ペース
		context.drawTextWithShadow(textRenderer, Text.literal("設置ペース").formatted(Formatting.WHITE),
			panelX + 22, sliderY - 20, 0xFFFFFFFF);
		String value = String.format(Locale.ROOT, "%.2fx", cfg.scaffoldCadence);
		context.drawTextWithShadow(textRenderer, Text.literal(value),
			panelX + PANEL_W - 22 - textRenderer.getWidth(value), sliderY - 20, TEXT_DIM);

		double t = (cfg.scaffoldCadence - 0.6) / 0.8;
		context.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 6, TRACK);
		int fillW = (int) Math.round(sliderW * t);
		context.fill(sliderX, sliderY, sliderX + fillW, sliderY + 6, ACCENT);
		int knobX = sliderX + fillW;
		context.fill(knobX - 5, sliderY - 4, knobX + 5, sliderY + 10, 0xFFFFFFFF);
		context.fill(knobX - 4, sliderY - 3, knobX + 4, sliderY + 9, ACCENT);

		context.drawTextWithShadow(textRenderer,
			Text.literal("実際の設置間隔は歩く速さで決まります（速いほど多く置く）"),
			panelX + 22, panelY + 274, 0xFF5E5E62);
		context.drawTextWithShadow(textRenderer,
			Text.literal("OFF / ブロックを手放す / 画面を開く と、人の速度で視点が戻ります"),
			panelX + 22, panelY + 290, 0xFF5E5E62);

		super.render(context, mouseX, mouseY, delta);
	}

	private void toggleRow(DrawContext context, int mouseX, int mouseY, int index,
			String name, String description, boolean on) {
		int x = panelX + 22;
		int w = PANEL_W - 44;
		int y = panelY + ROW_START + index * (ROW_H + ROW_GAP);

		context.fill(x, y, x + w, y + ROW_H, CARD_BG);
		border(context, x, y, w, ROW_H, BORDER);
		context.fill(x, y, x + 3, y + ROW_H, on ? ACCENT : TRACK_OFF);

		context.drawTextWithShadow(textRenderer, Text.literal(name).formatted(Formatting.WHITE),
			x + 14, y + 6, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(description),
			x + 14, y + 21, TEXT_DIM);

		pill(context, x + w - 52, y + (ROW_H - 18) / 2, on);
	}

	private void pill(DrawContext context, int x, int y, boolean on) {
		int w = 40;
		int h = 18;
		context.fill(x, y, x + w, y + h, on ? ACCENT : TRACK_OFF);
		int knob = h - 6;
		int kx = on ? x + w - knob - 3 : x + 3;
		context.fill(kx, y + 3, kx + knob, y + 3 + knob, 0xFFFFFFFF);
	}

	private boolean hitToggle(double mx, double my, int index) {
		int x = panelX + 22 + (PANEL_W - 44) - 52;
		int y = panelY + ROW_START + index * (ROW_H + ROW_GAP) + (ROW_H - 18) / 2;
		return mx >= x && mx <= x + 40 && my >= y && my <= y + 18;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			double mx = click.x();
			double my = click.y();
			if (hitToggle(mx, my, 0)) {
				cfg.scaffoldBlockJump = !cfg.scaffoldBlockJump;
				cfg.save();
				return true;
			}
			if (hitToggle(mx, my, 1)) {
				cfg.scaffoldKeepView = !cfg.scaffoldKeepView;
				cfg.save();
				return true;
			}
			if (hitToggle(mx, my, 2)) {
				cfg.scaffoldHumanize = !cfg.scaffoldHumanize;
				cfg.save();
				return true;
			}
			if (hitToggle(mx, my, 3)) {
				cfg.scaffoldSwing = !cfg.scaffoldSwing;
				cfg.save();
				return true;
			}
			if (my >= sliderY - 8 && my <= sliderY + 14
				&& mx >= sliderX - 6 && mx <= sliderX + sliderW + 6) {
				dragging = true;
				updateSlider(mx);
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging && click.button() == 0) {
			updateSlider(click.x());
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (click.button() == 0) {
			dragging = false;
		}
		return super.mouseReleased(click);
	}

	private void updateSlider(double mx) {
		double t = (mx - sliderX) / sliderW;
		t = Math.max(0.0, Math.min(1.0, t));
		double value = 0.6 + t * 0.8;
		value = Math.round(value * 20.0) / 20.0;   // 0.05 刻み
		if (value != cfg.scaffoldCadence) {
			cfg.scaffoldCadence = value;
			cfg.save();
		}
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(keyInput);
	}

	@Override
	public void close() {
		cfg.save();
		MinecraftClient.getInstance().setScreen(parent);
	}

	private static void border(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}
}
