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

import java.util.List;

/**
 * RShift で開く ClickGUI。
 *
 * <p>HTML のモックに合わせて、面取り（45° のカクカク）をやめて
 * <b>角丸 + アンチエイリアス + 等倍フォント</b>で描く。レイアウトも
 * Web の 2 カラム（左: カテゴリ / 右: モジュール）そのままで、
 * 行は「名前 + 説明」の 2 段組みにした。</p>
 *
 * <p><b>操作</b></p>
 * <ul>
 *   <li>左クリック … モジュールの ON / OFF</li>
 *   <li>右クリック … その場にコンテキストメニュー（設定 / ON-OFF / キー確認）</li>
 *   <li>ヘッダーのドラッグ … ウィンドウ移動</li>
 *   <li>RShift（開いた直後の 1 回は無視）/ ESC … 閉じる</li>
 * </ul>
 */
public final class ModuleScreen extends GlassScreen {
	private static final int W = 560;
	private static final int HEADER = 64;
	private static final int BODY = 272;
	private static final int FOOTER = 34;
	private static final int H = HEADER + BODY + FOOTER;
	private static final int PAD = 14;
	private static final int SIDEBAR = 168;
	private static final int CAT_ROW = 38;
	private static final int SEPARATOR = 24;
	private static final int MOD_ROW = 44;
	private static final int LIST_X = PAD + SIDEBAR + 14;
	private static final int LIST_W = W - LIST_X - PAD;
	private static final int MENU_W = 178;
	private static final int MENU_ITEM = 28;
	private static final int MENU_PAD = 6;
	private static final int MENU_ITEMS = 3;
	/** RShift を押した直後に画面側で拾ってすぐ閉じてしまうのを防ぐ [ms] */
	private static final long OPEN_GUARD_MS = 160L;

	private final Screen parent;
	private ModuleCategory tab = ModuleCategory.COMBAT;

	private int winX;
	private int winY;
	private boolean dragging;
	private int dragOffsetX;
	private int dragOffsetY;

	private long openedAt = System.currentTimeMillis();
	private float openT;
	private float fade = 1.0f;
	private String hint = "";
	private long hintUntil;

	private Module menuModule;
	private int menuX;
	private int menuY;

	public ModuleScreen(Screen parent) {
		super(Text.literal("The Client"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		winX = (width - W) / 2;
		winY = (height - H) / 2;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);

		// 開いた瞬間のフェード（時間基準なのでフレームレートに依存しない）
		openT = Math.min(1.0f, (System.currentTimeMillis() - openedAt) / 150.0f);
		fade = Ui.easeOut(openT);

		Ui.shadow(context, winX, winY, W, H, 14);
		Ui.border(context, winX, winY, W, H, 14, a(Ui.BORDER_STRONG), a(Ui.SURFACE));

		renderHeader(context, mouseX, mouseY);
		renderSidebar(context, mouseX, mouseY, delta);
		renderList(context, mouseX, mouseY, delta);
		renderFooter(context);

		if (menuModule != null) {
			renderMenu(context, mouseX, mouseY);
		}
	}

	// ------------------------------------------------------------------
	// ヘッダー
	// ------------------------------------------------------------------

	private void renderHeader(DrawContext context, int mouseX, int mouseY) {
		Ui.roundRect(context, winX + PAD, winY + 20, 3, 22, 1.5, a(Ui.ACCENT));
		Ui.text(context, textRenderer, "The Client.", winX + PAD + 10, winY + 17, a(Ui.TEXT), 1.0f);
		double titleW = Ui.width(textRenderer, "The Client.", 1.0f);
		Ui.chip(context, textRenderer, "v" + FastPlaceMod.VERSION,
			winX + PAD + 10 + titleW + 8, winY + 19, 0.0f);
		Ui.textRight(context, textRenderer, "RShift で開閉", winX + W - PAD, winY + 17,
			a(Ui.DIM), 0.8f);
		Ui.textRight(context, textRenderer, "左クリックで ON / OFF　右クリックでメニュー",
			winX + W - PAD, winY + 34, a(Ui.DIM), 0.75f);
		Ui.roundRect(context, winX + PAD, winY + HEADER - 8, W - PAD * 2.0, 1, 0.5, a(0x1AFFFFFF));
	}

	// ------------------------------------------------------------------
	// 左: カテゴリ
	// ------------------------------------------------------------------

	private int sidebarX() {
		return winX + PAD;
	}

	private int catY(int index) {
		return winY + HEADER + 14 + index * CAT_ROW;
	}

	private int dividerY() {
		return catY(ModuleCategory.values().length) + 6;
	}

	private int miscY(int index) {
		return dividerY() + SEPARATOR + index * CAT_ROW;
	}

	private void renderSidebar(DrawContext context, int mouseX, int mouseY, float delta) {
		int sx = sidebarX();
		Ui.text(context, textRenderer, "カテゴリ", sx + 6, winY + HEADER + 2, a(Ui.DIM), 0.75f);

		ModuleCategory[] categories = ModuleCategory.values();
		for (int i = 0; i < categories.length; i++) {
			ModuleCategory category = categories[i];
			boolean active = category == tab;
			boolean hover = Ui.inside(mouseX, mouseY, sx, catY(i), SIDEBAR, CAT_ROW - 4);
			float hv = Ui.anim("cat:" + category.name(), hover);
			if (active) {
				Ui.roundRect(context, sx, catY(i), SIDEBAR, CAT_ROW - 4, 8, a(Ui.SELECTED));
			} else if (hv > 0.01f) {
				Ui.roundRect(context, sx, catY(i), SIDEBAR, CAT_ROW - 4, 8,
					a(Ui.withAlpha(Ui.HOVER, (int) (0x12 * hv))));
			}
			if (active) {
				Ui.roundRect(context, sx + SIDEBAR - 9, catY(i) + 11, 3, 12, 1.5, a(Ui.ACCENT));
			}
			Ui.text(context, textRenderer, category.label, sx + 12, catY(i) + 8,
				a(active ? Ui.TEXT : Ui.MUTED), 1.0f);
			Ui.text(context, textRenderer, category.hint, sx + 12, catY(i) + 20,
				a(active ? Ui.MUTED : Ui.DIM), 0.7f);
		}

		// MISC
		int dy = dividerY();
		Ui.roundRect(context, sx, dy, SIDEBAR, 1, 0.5, a(0x1AFFFFFF));
		Ui.text(context, textRenderer, "その他", sx + 6, dy + 8, a(Ui.DIM), 0.75f);

		miscRow(context, 0, "キーバインド", "RShift / R", mouseX, mouseY);
		miscRow(context, 1, "設定ファイル", "ifuto-fastplace.json", mouseX, mouseY);
	}

	private void miscRow(DrawContext context, int index, String label, String value,
			int mouseX, int mouseY) {
		int sx = sidebarX();
		int y = miscY(index);
		boolean hover = Ui.inside(mouseX, mouseY, sx, y, SIDEBAR, CAT_ROW - 4);
		if (hover) {
			Ui.roundRect(context, sx, y, SIDEBAR, CAT_ROW - 4, 8, a(Ui.HOVER));
		}
		Ui.text(context, textRenderer, label, sx + 12, y + 8, a(Ui.MUTED), 1.0f);
		Ui.text(context, textRenderer, value, sx + 12, y + 20, a(Ui.DIM), 0.7f);
	}

	// ------------------------------------------------------------------
	// 右: モジュール
	// ------------------------------------------------------------------

	private List<Module> modules() {
		return Modules.inCategory(tab);
	}

	private int listX() {
		return winX + LIST_X;
	}

	private int moduleY(int index) {
		return winY + HEADER + 34 + index * MOD_ROW;
	}

	private void renderList(DrawContext context, int mouseX, int mouseY, float delta) {
		int lx = listX();
		Ui.text(context, textRenderer, tab.label, lx, winY + HEADER + 2, a(Ui.TEXT), 1.0f);
		List<Module> list = modules();
		Ui.text(context, textRenderer, list.size() + " modules", lx, winY + HEADER + 14,
			a(Ui.DIM), 0.7f);

		if (list.isEmpty()) {
			Ui.text(context, textRenderer, "このカテゴリにはモジュールがありません",
				lx, moduleY(0), a(Ui.DIM), 0.9f);
			return;
		}

		for (int i = 0; i < list.size(); i++) {
			Module module = list.get(i);
			int ry = moduleY(i);
			boolean on = module.isEnabled();
			boolean hover = Ui.inside(mouseX, mouseY, lx, ry, LIST_W, MOD_ROW - 4);
			float hv = Ui.anim("mod:" + module.id(), hover);
			if (hv > 0.01f) {
				Ui.roundRect(context, lx, ry, LIST_W, MOD_ROW - 4, 8,
					a(Ui.withAlpha(Ui.HOVER, (int) (0x12 * hv))));
			}
			// ON はアクセントの丸、OFF は輪郭だけ（フォントの記号に頼らない）
			double dotY = ry + (MOD_ROW - 4) * 0.5;
			Ui.circle(context, lx + 13, dotY, 3.0, a(on ? Ui.ACCENT : 0x00000000));
			if (!on) {
				Ui.circle(context, lx + 13, dotY, 3.0, a(Ui.DIM));
				Ui.circle(context, lx + 13, dotY, 1.8, a(Ui.SURFACE));
			}
			Ui.text(context, textRenderer, module.name(), lx + 24, ry + 8,
				a(on ? Ui.TEXT : Ui.MUTED), 1.0f);
			Ui.text(context, textRenderer, module.description(), lx + 24, ry + 21,
				a(Ui.DIM), 0.7f);

			double pillW = 34;
			double pillH = 16;
			double pillX = lx + LIST_W - pillW - 12;
			if (module.hasSettings()) {
				double dotsX = pillX - 16;
				for (int d = 0; d < 3; d++) {
					Ui.circle(context, dotsX + 1.5, ry + (MOD_ROW - 4) * 0.5 - 4 + d * 4, 1.2,
						a(hover ? Ui.MUTED : Ui.DIM));
				}
			}
			Text key = module.keyText();
			if (key != null) {
				String label = key.getString();
				double chipW = Ui.chipWidth(textRenderer, label);
				double chipX = pillX - (module.hasSettings() ? 24 : 8) - chipW;
				Ui.roundRect(context, chipX, ry + 9, chipW, 15, 7.5, a(0xD023232A));
				Ui.text(context, textRenderer, label, chipX + 6, ry + 13, a(Ui.MUTED), 1.0f);
			}
			Ui.toggle(context, pillX, ry + (MOD_ROW - 4 - pillH) * 0.5, pillW, pillH,
				Ui.anim("toggle:" + module.id(), on));
			Ui.roundRect(context, lx, ry + MOD_ROW - 5, LIST_W, 1, 0.5, a(0x0FFFFFFF));
		}
	}

	private void renderFooter(DrawContext context) {
		int fy = winY + HEADER + BODY;
		Ui.roundRect(context, winX + PAD, fy, W - PAD * 2.0, 1, 0.5, a(0x1AFFFFFF));
		boolean showingHint = System.currentTimeMillis() < hintUntil;
		String left = showingHint ? hint : "左クリック: ON / OFF　　右クリック: メニュー";
		Ui.text(context, textRenderer, left, winX + PAD, fy + 12,
			a(showingHint ? Ui.TEXT : Ui.DIM), 0.8f);
		Ui.textRight(context, textRenderer, "The Client. " + FastPlaceMod.VERSION,
			winX + W - PAD, fy + 12, a(Ui.DIM), 0.8f);
	}

	// ------------------------------------------------------------------
	// 右クリックのコンテキストメニュー
	// ------------------------------------------------------------------

	private void openMenu(Module module, double mx, double my) {
		menuModule = module;
		menuX = (int) Math.max(4, Math.min(mx, width - MENU_W - 4));
		menuY = (int) Math.max(4, Math.min(my, height - menuHeight() - 4));
	}

	private int menuHeight() {
		return MENU_ITEMS * MENU_ITEM + MENU_PAD * 2;
	}

	private String menuLabel(int index) {
		return switch (index) {
			case 0 -> "設定を開く";
			case 1 -> menuModule != null && menuModule.isEnabled() ? "OFF にする" : "ON にする";
			default -> "キーバインドを見る";
		};
	}

	private void renderMenu(DrawContext context, int mouseX, int mouseY) {
		int h = menuHeight();
		Ui.shadow(context, menuX, menuY, MENU_W, h, 8);
		Ui.border(context, menuX, menuY, MENU_W, h, 8, Ui.BORDER_STRONG, 0xF2161619);
		Ui.text(context, textRenderer, menuModule.name(), menuX + 10, menuY + 5,
			Ui.DIM, 0.7f);
		for (int i = 0; i < MENU_ITEMS; i++) {
			int iy = menuY + MENU_PAD + i * MENU_ITEM;
			boolean hover = Ui.inside(mouseX, mouseY, menuX + 4, iy, MENU_W - 8, MENU_ITEM)
				&& mouseY >= menuY + MENU_PAD;
			if (hover) {
				Ui.roundRect(context, menuX + 4, iy, MENU_W - 8, MENU_ITEM, 6, 0xFF26262E);
			}
			Ui.text(context, textRenderer, menuLabel(i), menuX + 12, iy + 10,
				hover ? Ui.WHITE : Ui.TEXT, 1.0f);
		}
	}

	private void applyMenu(int index) {
		Module module = menuModule;
		menuModule = null;
		if (module == null) {
			return;
		}
		switch (index) {
			case 0 -> {
				if (module.hasSettings()) {
					client.setScreen(module.createSettings(this));
				} else {
					showHint(module.name() + " には設定がありません");
				}
			}
			case 1 -> module.toggle();
			case 2 -> {
				Text key = module.keyText();
				showHint(key == null
					? module.name() + " のキーは未設定です"
					: module.name() + " のキー: " + key.getString());
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------
	// 入力
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();

		if (menuModule != null) {
			if (Ui.inside(mx, my, menuX, menuY, MENU_W, menuHeight())) {
				int index = (int) ((my - menuY - MENU_PAD) / MENU_ITEM);
				if (index >= 0 && index < MENU_ITEMS) {
					applyMenu(index);
					return true;
				}
			}
			menuModule = null;
			return true;
		}

		// 右クリック: モジュール行のコンテキストメニュー
		if (click.button() == 1) {
			Module module = moduleAt(mx, my);
			if (module != null) {
				openMenu(module, mx, my);
				return true;
			}
			return super.mouseClicked(click, doubled);
		}
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}

		// ヘッダーでドラッグ
		if (Ui.inside(mx, my, winX, winY, W, HEADER)) {
			dragging = true;
			dragOffsetX = (int) mx - winX;
			dragOffsetY = (int) my - winY;
			return true;
		}

		int sx = sidebarX();
		ModuleCategory[] categories = ModuleCategory.values();
		for (int i = 0; i < categories.length; i++) {
			if (Ui.inside(mx, my, sx, catY(i), SIDEBAR, CAT_ROW - 4)) {
				tab = categories[i];
				Ui.clearAnimations();
				return true;
			}
		}
		if (Ui.inside(mx, my, sx, miscY(0), SIDEBAR, CAT_ROW - 4)) {
			showHint("RShift = この画面 / R = FastPlace の ON・OFF");
			return true;
		}
		if (Ui.inside(mx, my, sx, miscY(1), SIDEBAR, CAT_ROW - 4)) {
			client.setScreen(new FastPlaceConfigScreen(this));
			return true;
		}

		int lx = listX();
		List<Module> list = modules();
		for (int i = 0; i < list.size(); i++) {
			if (Ui.inside(mx, my, lx, moduleY(i), LIST_W, MOD_ROW - 4)) {
				list.get(i).toggle();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	private Module moduleAt(double mx, double my) {
		int lx = listX();
		List<Module> list = modules();
		for (int i = 0; i < list.size(); i++) {
			if (Ui.inside(mx, my, lx, moduleY(i), LIST_W, MOD_ROW - 4)) {
				return list.get(i);
			}
		}
		return null;
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging && click.button() == 0) {
			winX = (int) click.x() - dragOffsetX;
			winY = (int) click.y() - dragOffsetY;
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
		if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		if (keyInput.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			// 開いた直後の同じ押下で閉じてしまわないようにする
			if (System.currentTimeMillis() - openedAt > OPEN_GUARD_MS) {
				close();
			}
			return true;
		}
		return super.keyPressed(keyInput);
	}

	@Override
	public void close() {
		menuModule = null;
		client.setScreen(parent);
	}

	private void showHint(String message) {
		hint = message;
		hintUntil = System.currentTimeMillis() + 2600L;
	}

	private int a(int color) {
		return Ui.scaleAlpha(color, fade);
	}
}
