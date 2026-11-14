package com.ifuto.f3.gui;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.module.ScaffoldModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * RShift で開くモジュール画面。
 * Render タブ = Fake F3（詐称のON/OFFと詳細設定）、Combat タブ = scaffold。
 */
public final class ModuleScreen extends Screen {
	private enum Tab {
		RENDER("Render", "描画をいじるモジュール"),
		COMBAT("Combat", "戦闘を助けるモジュール");

		final String label;
		final String hint;

		Tab(String label, String hint) {
			this.label = label;
			this.hint = hint;
		}
	}

	private static final int PANEL_W = 440;
	private static final int PANEL_H = 300;
	private static final int ACCENT = 0xFFE5484D;
	private static final int PANEL_BG = 0xE0141418;
	private static final int CARD_BG = 0xFF1F1F26;
	private static final int CARD_BORDER = 0xFF2C2C36;
	private static final int TRACK_OFF = 0xFF3A3A46;

	private final Screen parent;
	private final F3Config cfg;
	private Tab tab = Tab.RENDER;
	private int panelX;
	private int panelY;

	public ModuleScreen(Screen parent) {
		super(Text.literal("モジュール"));
		this.parent = parent;
		this.cfg = F3Config.get();
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;

		addDrawableChild(ButtonWidget.builder(Text.literal("閉じる"), b -> close())
			.dimensions(panelX + PANEL_W - 76, panelY + PANEL_H - 34, 66, 22).build());
		if (tab == Tab.RENDER) {
			addDrawableChild(ButtonWidget.builder(Text.literal("詳細設定…"), b ->
				client.setScreen(new F3ConfigScreen(this)))
				.dimensions(panelX + 16, panelY + tabContentY() + cardHeight() + 10, 90, 20).build());
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);

		// パネル
		context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
		border(context, panelX, panelY, PANEL_W, PANEL_H, CARD_BORDER);

		// ヘッダ
		context.drawTextWithShadow(textRenderer, Text.literal("IFUTO MODULES").formatted(Formatting.WHITE)
				.append(Text.literal("  v1").formatted(Formatting.GRAY)),
			panelX + 16, panelY + 14, 0xFFFFFFFF);

		// タブ
		int tx = panelX + 14;
		for (Tab t : Tab.values()) {
			int tw = textRenderer.getWidth(t.label) + 24;
			boolean active = tab == t;
			context.fill(tx, panelY + 36, tx + tw, panelY + 58, active ? CARD_BG : PANEL_BG);
			if (active) {
				context.fill(tx, panelY + 56, tx + tw, panelY + 58, ACCENT);
			}
			context.drawTextWithShadow(textRenderer, Text.literal(t.label)
					.formatted(active ? Formatting.WHITE : Formatting.GRAY), tx + 12, panelY + 43,
				active ? 0xFFFFFFFF : 0xFF9A9AA5);
			tx += tw + 4;
		}

		// ヒント
		context.drawTextWithShadow(textRenderer, Text.literal(tab.hint).formatted(Formatting.GRAY),
			panelX + 16, panelY + 66, 0xFF9A9AA5);

		// カード
		switch (tab) {
			case RENDER -> card(context, mouseX, mouseY, "Fake F3",
				"F3 デバッグ画面の文字・数値を詐称する", cfg.masterEnabled);
			case COMBAT -> card(context, mouseX, mouseY, "Scaffold",
				"歩くだけでノーシフトブリッジ。視点は自動で動く（ON中はジャンプ禁止）", cfg.scaffoldEnabled);
		}

		// フッター
		context.drawTextWithShadow(textRenderer, Text.literal("RShift / ESC で閉じる").formatted(Formatting.GRAY),
			panelX + 16, panelY + PANEL_H - 28, 0xFF6E6E78);

		super.render(context, mouseX, mouseY, delta);
	}

	private int cardX() {
		return panelX + 16;
	}

	private int tabContentY() {
		return panelY + 82;
	}

	private int cardHeight() {
		return 58;
	}

	private int cardWidth() {
		return PANEL_W - 32;
	}

	private void card(DrawContext context, int mouseX, int mouseY, String name, String desc, boolean on) {
		int x = cardX();
		int y = tabContentY();
		int w = cardWidth();
		int h = cardHeight();

		context.fill(x, y, x + w, y + h, CARD_BG);
		border(context, x, y, w, h, CARD_BORDER);

		context.fill(x, y, x + 3, y + h, on ? ACCENT : CARD_BORDER);
		context.drawTextWithShadow(textRenderer, Text.literal(name).formatted(Formatting.WHITE),
			x + 14, y + 10, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(desc).formatted(Formatting.GRAY),
			x + 14, y + 30, 0xFF9A9AA5);

		pill(context, pillX(), y + (h - 20) / 2, on);
	}

	private int pillX() {
		return cardX() + cardWidth() - 58;
	}

	private int pillY() {
		return tabContentY() + (cardHeight() - 20) / 2;
	}

	private void pill(DrawContext context, int x, int y, boolean on) {
		int w = 44;
		int h = 20;
		context.fill(x, y, x + w, y + h, on ? ACCENT : TRACK_OFF);
		int knob = h - 6;
		int kx = on ? x + w - knob - 3 : x + 3;
		context.fill(kx, y + 3, kx + knob, y + 3 + knob, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer,
			Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.WHITE : Formatting.GRAY),
			on ? x + 6 : x + w - textRenderer.getWidth(on ? "ON" : "OFF") - 6, y + 6,
			0xFFFFFFFF);
	}

	private static void border(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			double mouseX = click.x();
			double mouseY = click.y();
			// タブ
			int tx = panelX + 14;
			for (Tab t : Tab.values()) {
				int tw = textRenderer.getWidth(t.label) + 24;
				if (mouseX >= tx && mouseX <= tx + tw && mouseY >= panelY + 36 && mouseY <= panelY + 58) {
					if (tab != t) {
						tab = t;
						clearAndInit();
					}
					return true;
				}
				tx += tw + 4;
			}
			// ピル（ON/OFF）
			if (mouseX >= pillX() && mouseX <= pillX() + 44 && mouseY >= pillY() && mouseY <= pillY() + 20) {
				toggleModule();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	private void toggleModule() {
		switch (tab) {
			case RENDER -> {
				cfg.masterEnabled = !cfg.masterEnabled;
				cfg.save();
			}
			case COMBAT -> ScaffoldModule.setEnabled(!cfg.scaffoldEnabled);
		}
	}

	@Override
	public boolean keyPressed(KeyInput keyInput) {
		if (keyInput.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) {
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
}
