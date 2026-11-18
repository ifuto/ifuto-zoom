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
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RShift で開く ClickGUI（The Client.）。
 *
 * <p>2 ペイン構成: 左がカテゴリ（Combat / Movement / Player / Render）と MISC、
 * 右がそのカテゴリのモジュール一覧。行クリックで ON/OFF、`⋮` で設定画面。
 * ヘッダーをドラッグすると画面ごと動く。</p>
 */
public final class ModuleScreen extends GlassScreen {
	private static final int PANEL_W = 270;
	private static final int GAP = 12;
	private static final int TOP = 34;
	private static final int HEADER_L = 48;
	private static final int HEADER_R = 46;
	private static final int ROW_H = 40;
	private static final int DIVIDER_H = 28;
	private static final int FOOTER_H = 40;
	private static final int PILL_W = 28;
	private static final int PILL_H = 14;

	private static final int WINDOW = 0xFF181819;
	private static final int BORDER = 0xFF242426;
	private static final int HOVER = 0xFF1F1F21;
	private static final int SECTION = 0xFF151516;
	private static final int ACCENT = 0xFF16A37F;
	private static final int TEXT = 0xFFD4D4D8;
	private static final int MUTED = 0xFF9E9EA4;
	private static final int DIM = 0xFF5E5E62;
	private static final int CHIP = 0xFF232326;
	private static final int CHIP_BORDER = 0xFF2D2D30;
	private static final int TRACK_OFF = 0xFF2A2A2E;
	private static final int SHADOW = 0x44000000;

	private final Screen parent;
	private ModuleCategory tab = ModuleCategory.COMBAT;
	private boolean collapsed;

	private int leftX;
	private int leftY;
	private int rightX;
	private int rightY;
	private int dragX;
	private int dragY;
	private boolean dragging;
	private int dragOffsetX;
	private int dragOffsetY;

	private final Map<String, Float> hovers = new HashMap<>();
	private String hint = "";
	private long hintUntil;

	public ModuleScreen(Screen parent) {
		super(Text.literal("The Client"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int totalW = PANEL_W * 2 + GAP;
		leftX = (width - totalW) / 2;
		leftY = TOP;
		rightX = leftX + PANEL_W + GAP;
		rightY = TOP;
		dragX = leftX;
		dragY = leftY;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		hovers.clear();

		renderLeft(context, mouseX, mouseY);
		renderRight(context, mouseX, mouseY);
	}

	// ------------------------------------------------------------------
	// 左パネル
	// ------------------------------------------------------------------

	private int leftHeight() {
		int categories = ModuleCategory.values().length;
		return HEADER_L + categories * ROW_H + DIVIDER_H + 2 * ROW_H + FOOTER_H;
	}

	private void renderLeft(DrawContext context, int mouseX, int mouseY) {
		int h = leftHeight();
		int x = leftX;
		int y = leftY;

		chamfer(context, x, y + 4, PANEL_W, h, 3, SHADOW);
		panel(context, x, y, PANEL_W, h, WINDOW, BORDER);

		// ヘッダー（ドラッグ用）
		context.fill(x + 1, y + HEADER_L - 1, x + PANEL_W - 1, y + HEADER_L, BORDER);
		text(context, "The Client.", x + 14, y + 15, 0xFFFFFFFF, 1.35f);
		badge(context, x + 14 + (int) (textRenderer.getWidth("The Client.") * 1.35f) + 6, y + 19,
			"v" + FastPlaceMod.VERSION);
		gear(context, x + PANEL_W - 26, y + 16, isHovered(mouseX, mouseY, x + PANEL_W - 28, y + 14, 18, 18));

		// カテゴリ
		int rowY = y + HEADER_L;
		for (ModuleCategory category : ModuleCategory.values()) {
			boolean active = category == tab;
			boolean hover = isHovered(mouseX, mouseY, x, rowY, PANEL_W, ROW_H);
			if (hover && !active) {
				context.fill(x + 1, rowY, x + PANEL_W - 1, rowY + ROW_H, HOVER);
			}
			if (active) {
				context.fill(x + 1, rowY, x + PANEL_W - 1, rowY + ROW_H, HOVER);
				context.fill(x + 1, rowY, x + 3, rowY + ROW_H, ACCENT);
			}
			icon(context, category, x + 16, rowY + 14, active ? ACCENT : DIM);
			text(context, category.label, x + 34, rowY + 15, active ? TEXT : MUTED, 1.0f);
			chevron(context, x + PANEL_W - 20, rowY + 17, active ? ACCENT : DIM);
			rowY += ROW_H;
		}

		// MISC
		context.fill(x + 1, rowY, x + PANEL_W - 1, rowY + DIVIDER_H, SECTION);
		context.fill(x + 1, rowY, x + PANEL_W - 1, rowY + 1, BORDER);
		context.fill(x + 1, rowY + DIVIDER_H - 1, x + PANEL_W - 1, rowY + DIVIDER_H, BORDER);
		text(context, "MISC", x + 16, rowY + 10, DIM, 0.85f);
		rowY += DIVIDER_H;

		miscRow(context, "Keybinds", "RShift / R", x, rowY, mouseX, mouseY);
		rowY += ROW_H;
		miscRow(context, "Config", "", x, rowY, mouseX, mouseY);
		rowY += ROW_H;

		// フッター
		context.fill(x + 1, rowY, x + PANEL_W - 1, rowY + 1, BORDER);
		String footer = System.currentTimeMillis() < hintUntil ? hint : "The Client. " + FastPlaceMod.VERSION;
		text(context, footer, x + 14, rowY + 15, DIM, 0.85f);
		text(context, "RShift", x + PANEL_W - 14 - (int) (textRenderer.getWidth("RShift") * 0.85f),
			rowY + 15, DIM, 0.85f);
	}

	private void miscRow(DrawContext context, String label, String value,
			int x, int y, int mouseX, int mouseY) {
		if (isHovered(mouseX, mouseY, x, y, PANEL_W, ROW_H)) {
			context.fill(x + 1, y, x + PANEL_W - 1, y + ROW_H, HOVER);
		}
		text(context, label, x + 16, y + 15, MUTED, 1.0f);
		if (!value.isEmpty()) {
			text(context, value, x + PANEL_W - 20 - (int) (textRenderer.getWidth(value) * 0.8f),
				y + 16, DIM, 0.8f);
		}
		chevron(context, x + PANEL_W - 16, y + 17, DIM);
	}

	// ------------------------------------------------------------------
	// 右パネル
	// ------------------------------------------------------------------

	private List<Module> modules() {
		return Modules.inCategory(tab);
	}

	private int rightHeight() {
		return HEADER_R + (collapsed ? 0 : Math.max(1, modules().size()) * ROW_H);
	}

	private void renderRight(DrawContext context, int mouseX, int mouseY) {
		int h = rightHeight();
		int x = rightX;
		int y = rightY;

		chamfer(context, x, y + 4, PANEL_W, h, 3, SHADOW);
		panel(context, x, y, PANEL_W, h, WINDOW, BORDER);

		context.fill(x + 1, y + HEADER_R - 1, x + PANEL_W - 1, y + HEADER_R, BORDER);
		icon(context, tab, x + 16, y + 16, MUTED);
		text(context, tab.label, x + 34, y + 16, TEXT, 1.05f);
		chevron(context, x + PANEL_W - 22, y + 18, collapsed ? DIM : MUTED);

		if (collapsed) {
			return;
		}

		List<Module> list = modules();
		int rowY = y + HEADER_R;
		if (list.isEmpty()) {
			text(context, "まだモジュールがありません", x + 16, rowY + 15, DIM, 0.9f);
			return;
		}
		for (Module module : list) {
			boolean hover = isHovered(mouseX, mouseY, x, rowY, PANEL_W, ROW_H);
			if (hover) {
				context.fill(x + 1, rowY, x + PANEL_W - 1, rowY + ROW_H, HOVER);
			}
			// 下線（最後以外）
			context.fill(x + 1, rowY + ROW_H - 1, x + PANEL_W - 1, rowY + ROW_H, 0x331E1E20);

			boolean on = module.isEnabled();
			text(context, module.name(), x + 16, rowY + 15, on ? TEXT : MUTED, 1.0f);

			Text key = module.keyText();
			int chipRight = x + PANEL_W - 74;
			if (key != null) {
				String label = key.getString();
				int w = (int) (textRenderer.getWidth(label) * 0.8f) + 10;
				int cx = chipRight - w;
				chamfer(context, cx, rowY + 14, w, 13, 2, CHIP_BORDER);
				chamfer(context, cx + 1, rowY + 15, w - 2, 11, 1, CHIP);
				text(context, label, cx + 5, rowY + 17, MUTED, 0.8f);
			}

			if (module.hasSettings()) {
				dots(context, x + PANEL_W - 46, rowY + 15,
					isHovered(mouseX, mouseY, x + PANEL_W - 52, rowY + 8, 16, 24));
			}

			pill(context, x + PANEL_W - 40, rowY + (ROW_H - PILL_H) / 2, on);
			rowY += ROW_H;
		}
	}

	// ------------------------------------------------------------------
	// 入力
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		int x = leftX;
		int y = leftY;

		// ヘッダーでドラッグ開始
		if (click.button() == 0 && inside(mx, my, x, y, PANEL_W, HEADER_L)) {
			dragging = true;
			dragOffsetX = (int) mx - leftX;
			dragOffsetY = (int) my - leftY;
			return true;
		}
		if (click.button() == 0 && inside(mx, my, rightX, rightY, PANEL_W, HEADER_R)) {
			dragging = true;
			dragOffsetX = (int) mx - rightX;
			dragOffsetY = (int) my - rightY;
			return true;
		}

		// カテゴリ
		int rowY = y + HEADER_L;
		for (ModuleCategory category : ModuleCategory.values()) {
			if (inside(mx, my, x, rowY, PANEL_W, ROW_H)) {
				tab = category;
				return true;
			}
			rowY += ROW_H;
		}
		// MISC
		rowY += DIVIDER_H;
		if (inside(mx, my, x, rowY, PANEL_W, ROW_H)) {
			showHint("RShift = この画面 / R = FastPlace");
			return true;
		}
		rowY += ROW_H;
		if (inside(mx, my, x, rowY, PANEL_W, ROW_H)) {
			client.setScreen(new FastPlaceConfigScreen(this));
			return true;
		}
		// 歯車 = 設定
		if (inside(mx, my, x + PANEL_W - 28, y + 14, 18, 18)) {
			client.setScreen(new FastPlaceConfigScreen(this));
			return true;
		}

		// 右ヘッダー（折りたたみ）
		if (inside(mx, my, rightX, rightY, PANEL_W, HEADER_R)) {
			collapsed = !collapsed;
			return true;
		}

		// モジュール行
		List<Module> list = modules();
		int moduleY = rightY + HEADER_R;
		for (Module module : list) {
			if (inside(mx, my, rightX, moduleY, PANEL_W, ROW_H)) {
				if (module.hasSettings() && inside(mx, my, rightX + PANEL_W - 52, moduleY + 8, 16, 24)) {
					client.setScreen(module.createSettings(this));
					return true;
				}
				module.toggle();
				return true;
			}
			moduleY += ROW_H;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging && click.button() == 0) {
			leftX = (int) click.x() - dragOffsetX;
			leftY = (int) click.y() - dragOffsetY;
			rightX = leftX + PANEL_W + GAP;
			rightY = leftY;
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragging = false;
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
		client.setScreen(parent);
	}

	// ------------------------------------------------------------------
	// 描画部品
	// ------------------------------------------------------------------

	private void showHint(String message) {
		hint = message;
		hintUntil = System.currentTimeMillis() + 2500;
	}

	private void text(DrawContext context, String value, int x, int y, int color, float scale) {
		if (scale == 1.0f) {
			context.drawText(textRenderer, value, x, y, color, false);
			return;
		}
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(x, y);
		matrices.scale(scale, scale);
		context.drawText(textRenderer, value, 0, 0, color, false);
		matrices.popMatrix();
	}

	private void badge(DrawContext context, int x, int y, String label) {
		int w = (int) (textRenderer.getWidth(label) * 0.8f) + 8;
		chamfer(context, x, y, w, 13, 2, ACCENT);
		text(context, label, x + 4, y + 4, ACCENT, 0.8f);
	}

	private void pill(DrawContext context, int x, int y, boolean on) {
		chamfer(context, x, y, PILL_W, PILL_H, 6, on ? ACCENT : TRACK_OFF);
		int knob = PILL_H - 4;
		int kx = on ? x + PILL_W - knob - 2 : x + 2;
		chamfer(context, kx, y + 2, knob, knob, 4, 0xFFFFFFFF);
	}

	private void chevron(DrawContext context, int x, int y, int color) {
		context.fill(x, y, x + 1, y + 5, color);
		context.fill(x + 1, y + 1, x + 2, y + 4, color);
		context.fill(x + 2, y + 2, x + 3, y + 3, color);
	}

	private void dots(DrawContext context, int x, int y, boolean hover) {
		int color = hover ? TEXT : DIM;
		context.fill(x, y, x + 1, y + 1, color);
		context.fill(x, y + 5, x + 1, y + 6, color);
		context.fill(x, y + 10, x + 1, y + 11, color);
	}

	private void gear(DrawContext context, int x, int y, boolean hover) {
		int color = hover ? TEXT : MUTED;
		context.fill(x + 4, y, x + 8, y + 2, color);
		context.fill(x + 4, y + 10, x + 8, y + 12, color);
		context.fill(x, y + 4, x + 2, y + 8, color);
		context.fill(x + 10, y + 4, x + 12, y + 8, color);
		context.fill(x + 3, y + 3, x + 9, y + 9, color);
		context.fill(x + 5, y + 5, x + 7, y + 7, WINDOW);
	}

	private void icon(DrawContext context, ModuleCategory category, int x, int y, int color) {
		switch (category) {
			case COMBAT -> {
				context.fill(x + 1, y + 3, x + 9, y + 5, color);
				context.fill(x + 3, y + 1, x + 5, y + 9, color);
				context.fill(x + 5, y + 5, x + 9, y + 9, color);
			}
			case MOVEMENT -> {
				context.fill(x, y + 5, x + 10, y + 7, color);
				context.fill(x + 6, y + 2, x + 10, y + 4, color);
				context.fill(x + 6, y + 8, x + 10, y + 10, color);
			}
			case PLAYER -> {
				context.fill(x + 3, y, x + 7, y + 4, color);
				context.fill(x, y + 5, x + 10, y + 10, color);
			}
			default -> {
				context.fill(x + 1, y + 1, x + 9, y + 9, color);
				context.fill(x + 3, y + 3, x + 7, y + 7, WINDOW);
			}
		}
	}

	private static void chamfer(DrawContext context, int x, int y, int w, int h, int radius, int color) {
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (r == 0) {
			context.fill(x, y, x + w, y + h, color);
			return;
		}
		context.fill(x + r, y, x + w - r, y + h, color);
		context.fill(x, y + r, x + r, y + h - r, color);
		context.fill(x + w - r, y + r, x + w, y + h - r, color);
	}

	private static void panel(DrawContext context, int x, int y, int w, int h, int fill, int border) {
		chamfer(context, x, y, w, h, 3, border);
		chamfer(context, x + 1, y + 1, w - 2, h - 2, 2, fill);
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private boolean isHovered(int mx, int my, int x, int y, int w, int h) {
		return inside(mx, my, x, y, w, h);
	}
}
