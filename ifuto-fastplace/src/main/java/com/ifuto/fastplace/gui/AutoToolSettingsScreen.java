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

/**
 * Auto Tool の設定。
 */
public final class AutoToolSettingsScreen extends Screen {
	private static final int PANEL_W = 440;
	private static final int PANEL_H = 250;
	private static final int ACCENT = 0xFF16A37F;
	private static final int PANEL_BG = 0xFF181819;
	private static final int CARD_BG = 0xFF1F1F21;
	private static final int BORDER = 0xFF242426;
	private static final int TRACK_OFF = 0xFF2A2A2E;
	private static final int TEXT_DIM = 0xFF9E9EA4;
	private static final int TEXT_FAINT = 0xFF5E5E62;
	private static final int ROW_H = 34;
	private static final int ROW_GAP = 5;
	private static final int ROW_START = 52;

	private final Screen parent;
	private final FastPlaceConfig cfg;

	private int panelX;
	private int panelY;

	public AutoToolSettingsScreen(Screen parent) {
		super(Text.literal("Auto Tool 設定"));
		this.parent = parent;
		this.cfg = FastPlaceConfig.get();
	}

	@Override
	protected void init() {
		panelX = (width - PANEL_W) / 2;
		panelY = (height - PANEL_H) / 2;
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
			Text.literal("AUTO TOOL").formatted(Formatting.WHITE)
				.append(Text.literal("  Mining").formatted(Formatting.GRAY)),
			panelX + 20, panelY + 13, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer,
			Text.literal("適正でない道具で掘り始めたら、人間の判断時間で持ち替えます"),
			panelX + 20, panelY + 28, TEXT_DIM);

		toggleRow(context, 0, "人間らしさ", "判断時間・練習・たまの取り違えを入れる", cfg.autoToolHumanize);
		toggleRow(context, 1, "掘っている間だけ", "攻撃キーを押している間だけ持ち替える", cfg.autoToolOnlyWhileMining);
		toggleRow(context, 2, "壊れかけを避ける", "残り耐久 10 以下の道具は使わない", cfg.autoToolAvoidBreaking);

		context.drawTextWithShadow(textRenderer,
			Text.literal("15% 以上速くならないなら持ち替えません（掘るのが遅くならないように）"),
			panelX + 22, panelY + PANEL_H - 42, TEXT_FAINT);
		context.drawTextWithShadow(textRenderer,
			Text.literal("RShift / ESC でモジュール一覧へ"),
			panelX + 22, panelY + PANEL_H - 28, TEXT_FAINT);

		super.render(context, mouseX, mouseY, delta);
	}

	private void toggleRow(DrawContext context, int index, String name, String description, boolean on) {
		int x = panelX + 22;
		int w = PANEL_W - 44;
		int y = panelY + ROW_START + index * (ROW_H + ROW_GAP);

		context.fill(x, y, x + w, y + ROW_H, CARD_BG);
		border(context, x, y, w, ROW_H, BORDER);
		context.fill(x, y, x + 3, y + ROW_H, on ? ACCENT : TRACK_OFF);

		context.drawTextWithShadow(textRenderer, Text.literal(name).formatted(Formatting.WHITE),
			x + 14, y + 5, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(description),
			x + 14, y + 18, TEXT_DIM);

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
			for (int i = 0; i < 3; i++) {
				if (hitToggle(mx, my, i)) {
					switch (i) {
						case 0 -> cfg.autoToolHumanize = !cfg.autoToolHumanize;
						case 1 -> cfg.autoToolOnlyWhileMining = !cfg.autoToolOnlyWhileMining;
						default -> cfg.autoToolAvoidBreaking = !cfg.autoToolAvoidBreaking;
					}
					cfg.save();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
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
