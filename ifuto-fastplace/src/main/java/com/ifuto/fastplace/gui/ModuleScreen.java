package com.ifuto.fastplace.gui;

import com.ifuto.fastplace.FastPlaceMod;
import com.ifuto.fastplace.module.Module;
import com.ifuto.fastplace.module.ModuleCategory;
import com.ifuto.fastplace.module.Modules;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RShift で開く Module Select 画面（Vape v4 のモジュール一覧のイメージ）。
 *
 * <p>左がカテゴリ、右がモジュール一覧。行をクリックで ON/OFF、
 * 「…」をクリックでそのモジュールの設定。トグルは少しアニメーションする。</p>
 */
public final class ModuleScreen extends Screen {
	private static final int PANEL_W = 496;
	private static final int PANEL_H = 316;
	private static final int SIDEBAR_W = 148;
	private static final int PAD = 16;
	private static final int ROW_H = 58;
	private static final int ROW_GAP = 8;
	private static final int TOGGLE_W = 44;
	private static final int TOGGLE_H = 20;

	private static final int ACCENT = 0xFF3D8BFF;
	private static final int BG = 0xF0131317;
	private static final int SIDEBAR_BG = 0xFF0D0D10;
	private static final int CARD = 0xFF1A1A20;
	private static final int CARD_HOVER = 0xFF22222B;
	private static final int BORDER = 0xFF2A2A33;
	private static final int CHIP_BG = 0xFF262630;
	private static final int TEXT_MAIN = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFF8B8B96;
	private static final int TEXT_FAINT = 0xFF5C5C66;
	private static final int TRACK_OFF = 0xFF3A3A46;

	private final Screen parent;
	private ModuleCategory tab = ModuleCategory.MOVEMENT;

	private final Map<String, Float> toggles = new HashMap<>();
	private final Map<String, Float> hovers = new HashMap<>();
	private long lastFrameNs;

	private int panelX;
	private int panelY;

	public ModuleScreen(Screen parent) {
		super(Text.literal("Modules"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);

		float frame = frameDelta();

		// 影 + パネル
		context.fill(panelX + 4, panelY + 5, panelX + PANEL_W + 4, panelY + PANEL_H + 5, 0x55000000);
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BG);
		border(context, panelX, panelY, PANEL_W, PANEL_H, BORDER);
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + 2, ACCENT);

		// サイドバー
		context.fill(panelX, panelY + 2, panelX + SIDEBAR_W, panelY + PANEL_H - 1, SIDEBAR_BG);
		context.fill(panelX + SIDEBAR_W, panelY + 2, panelX + SIDEBAR_W + 1, panelY + PANEL_H - 1, BORDER);
		drawSidebar(context, mouseX, mouseY);

		drawContent(context, mouseX, mouseY, frame);
		drawFooter(context);

		super.render(context, mouseX, mouseY, delta);
	}

	private void drawSidebar(DrawContext context, int mouseX, int mouseY) {
		int x = panelX + PAD;
		context.fill(x, panelY + 16, x + 7, panelY + 23, ACCENT);
		context.drawTextWithShadow(textRenderer, Text.literal("IFUTO").formatted(Formatting.WHITE),
			x + 12, panelY + 15, TEXT_MAIN);
		context.drawTextWithShadow(textRenderer, Text.literal("MODULE SELECT"),
			x, panelY + 30, TEXT_FAINT);

		int y = panelY + 58;
		for (ModuleCategory category : ModuleCategory.values()) {
			boolean active = tab == category;
			boolean hover = mouseX >= panelX + 8 && mouseX <= panelX + SIDEBAR_W - 8
				&& mouseY >= y && mouseY <= y + 28;
			if (active) {
				context.fill(panelX + 8, y, panelX + SIDEBAR_W - 8, y + 28, 0xFF1C1C24);
				context.fill(panelX + 8, y, panelX + 11, y + 28, ACCENT);
			} else if (hover) {
				context.fill(panelX + 8, y, panelX + SIDEBAR_W - 8, y + 28, 0xFF15151B);
			}
			context.drawTextWithShadow(textRenderer,
				Text.literal(category.label),
				panelX + 20, y + 10,
				active ? TEXT_MAIN : (hover ? TEXT_DIM : 0xFF75757F));

			String count = String.valueOf(Modules.count(category));
			int cw = textRenderer.getWidth(count);
			context.drawTextWithShadow(textRenderer, Text.literal(count),
				panelX + SIDEBAR_W - 16 - cw, y + 10, active ? 0xFF9CBDFF : TEXT_FAINT);
			y += 32;
		}

		// サイドバー下部のヒント
		int bottom = panelY + PANEL_H - 44;
		context.fill(panelX + PAD, bottom - 8, panelX + SIDEBAR_W - PAD, bottom - 7, BORDER);
		context.drawTextWithShadow(textRenderer, Text.literal("RShift / ESC"),
			panelX + PAD, bottom, TEXT_FAINT);
		context.drawTextWithShadow(textRenderer, Text.literal("で閉じる"),
			panelX + PAD, bottom + 11, TEXT_FAINT);
	}

	private void drawContent(DrawContext context, int mouseX, int mouseY, float frame) {
		int x = contentX() + PAD;
		int w = contentWidth() - PAD * 2;

		context.drawTextWithShadow(textRenderer, Text.literal(tab.label).formatted(Formatting.WHITE),
			x, panelY + 14, TEXT_MAIN);
		context.drawTextWithShadow(textRenderer, Text.literal(tab.hint),
			x, panelY + 28, TEXT_DIM);

		List<Module> modules = Modules.inCategory(tab);
		String count = modules.size() + " MODULE" + (modules.size() == 1 ? "" : "S");
		context.drawTextWithShadow(textRenderer, Text.literal(count),
			x + w - textRenderer.getWidth(count), panelY + 16, TEXT_FAINT);

		int rowY = panelY + 56;
		for (Module module : modules) {
			drawRow(context, module, x, rowY, w, mouseX, mouseY, frame);
			rowY += ROW_H + ROW_GAP;
		}
	}

	private void drawRow(DrawContext context, Module module, int x, int y, int w,
			int mouseX, int mouseY, float frame) {
		boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + ROW_H;
		float hover = animate(hovers, module.id(), hovered ? 1.0f : 0.0f, frame, 16.0f);
		float on = animate(toggles, module.id(), module.isEnabled() ? 1.0f : 0.0f, frame, 15.0f);

		int bg = blend(CARD, CARD_HOVER, hover);
		context.fill(x, y, x + w, y + ROW_H, bg);
		border(context, x, y, w, ROW_H, blend(BORDER, 0xFF3A4A63, hover * 0.8f));
		if (on > 0.02f) {
			context.fill(x, y, x + 3, y + ROW_H, blend(bg, ACCENT, on));
		}

		context.drawTextWithShadow(textRenderer, Text.literal(module.name()),
			x + 14, y + 11, TEXT_MAIN);

		// 設定（…）: ホバー時だけ、名前の右
		if (module.hasSettings() && hover > 0.25f) {
			int dotsX = x + 16 + textRenderer.getWidth(module.name());
			int alpha = (int) (Math.min(1.0f, hover) * 255.0f) << 24;
			int color = alpha | 0x8B8B96;
			context.fill(dotsX, y + 16, dotsX + 2, y + 18, color);
			context.fill(dotsX + 4, y + 16, dotsX + 6, y + 18, color);
			context.fill(dotsX + 8, y + 16, dotsX + 10, y + 18, color);
		}

		// キーバインドのチップ
		int toggleX = x + w - TOGGLE_W - 12;
		int toggleY = y + (ROW_H - TOGGLE_H) / 2;
		Text key = module.keyText();
		int descWidth = toggleX - (x + 14) - 12;
		if (key != null) {
			int chipW = Math.max(24, textRenderer.getWidth(key.getString()) + 12);
			int chipX = toggleX - 8 - chipW;
			int chipY = y + (ROW_H - 18) / 2;
			context.fill(chipX, chipY, chipX + chipW, chipY + 18, CHIP_BG);
			border(context, chipX, chipY, chipW, 18, BORDER);
			context.drawTextWithShadow(textRenderer, key,
				chipX + 6, chipY + 5, TEXT_DIM);
			descWidth = chipX - (x + 14) - 10;
		}

		String description = textRenderer.trimToWidth(module.description(), Math.max(20, descWidth));
		context.drawTextWithShadow(textRenderer, Text.literal(description),
			x + 14, y + 30, TEXT_DIM);

		drawToggle(context, toggleX, toggleY, on);
	}

	private void drawToggle(DrawContext context, int x, int y, float on) {
		int track = blend(TRACK_OFF, ACCENT, on);
		context.fill(x, y, x + TOGGLE_W, y + TOGGLE_H, track);
		context.fill(x, y, x + TOGGLE_W, y + 1, 0x33000000);
		int knob = TOGGLE_H - 6;
		int travel = TOGGLE_W - knob - 6;
		int kx = x + 3 + Math.round(travel * on);
		context.fill(kx, y + 3, kx + knob, y + 3 + knob, 0xFFFFFFFF);
		String label = on > 0.5f ? "ON" : "OFF";
		int color = on > 0.5f ? 0xFFFFFFFF : 0xFFB9B9C4;
		int lx = on > 0.5f ? x + 6 : x + TOGGLE_W - 6 - textRenderer.getWidth(label);
		context.drawTextWithShadow(textRenderer, Text.literal(label), lx, y + 6, color);
	}

	private void drawFooter(DrawContext context) {
		context.fill(panelX + SIDEBAR_W + 1, panelY + PANEL_H - 26, panelX + PANEL_W - 1,
			panelY + PANEL_H - 25, BORDER);
		context.drawTextWithShadow(textRenderer,
			Text.literal("クリックで ON/OFF ・ … で設定 ・ キーは 操作設定 から"),
			panelX + SIDEBAR_W + PAD, panelY + PANEL_H - 18, TEXT_FAINT);
		String version = "v" + FastPlaceMod.VERSION;
		context.drawTextWithShadow(textRenderer, Text.literal(version),
			panelX + PANEL_W - PAD - textRenderer.getWidth(version), panelY + PANEL_H - 18, TEXT_FAINT);
	}

	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			double mx = click.x();
			double my = click.y();

			int categoryY = panelY + 58;
			for (ModuleCategory category : ModuleCategory.values()) {
				if (mx >= panelX + 8 && mx <= panelX + SIDEBAR_W - 8
					&& my >= categoryY && my <= categoryY + 28) {
					if (tab != category) {
						tab = category;
					}
					return true;
				}
				categoryY += 32;
			}

			int x = contentX() + PAD;
			int w = contentWidth() - PAD * 2;
			int rowY = panelY + 56;
			for (Module module : Modules.inCategory(tab)) {
				if (mx >= x && mx <= x + w && my >= rowY && my <= rowY + ROW_H) {
					if (module.hasSettings() && hitDots(mx, my, module, x, rowY)) {
						Screen settings = module.createSettings(this);
						if (settings != null) {
							client.setScreen(settings);
						}
					} else {
						module.toggle();
					}
					return true;
				}
				rowY += ROW_H + ROW_GAP;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	private boolean hitDots(double mx, double my, Module module, int x, int rowY) {
		int dotsX = x + 16 + textRenderer.getWidth(module.name());
		return mx >= dotsX - 4 && mx <= dotsX + 14 && my >= rowY + 9 && my <= rowY + 27;
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (keyInput.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			close();
			return true;
		}
		return super.keyPressed(keyInput);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		} else {
			super.close();
		}
	}

	// ------------------------------------------------------------------

	private int contentX() {
		return panelX + SIDEBAR_W + 1;
	}

	private int contentWidth() {
		return PANEL_W - SIDEBAR_W - 1;
	}

	private float frameDelta() {
		long now = System.nanoTime();
		float dt = lastFrameNs == 0L ? 0.016f : (now - lastFrameNs) / 1.0e9f;
		lastFrameNs = now;
		return Math.max(0.0f, Math.min(0.1f, dt));
	}

	private static float animate(Map<String, Float> map, String id, float target, float dt, float speed) {
		float current = map.getOrDefault(id, target);
		current += (target - current) * Math.min(1.0f, dt * speed);
		map.put(id, current);
		return current;
	}

	private static int blend(int from, int to, float t) {
		float v = Math.max(0.0f, Math.min(1.0f, t));
		int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * v);
		int r = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * v);
		int g = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * v);
		int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * v);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static void border(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}
}
