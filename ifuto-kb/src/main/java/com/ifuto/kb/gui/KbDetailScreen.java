package com.ifuto.kb.gui;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import com.ifuto.kb.model.ServerRecord;
import com.ifuto.kb.store.KbStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class KbDetailScreen extends Screen {
	private static final int LIST_WIDTH = 520;
	private static final int SCROLL_MIN_HEIGHT = 120;
	private static final int JSON_LINES = 300;
	private static final long ARM_MS = 4000L;

	private final Screen parent;
	private final String address;
	private ScrollableLayoutWidget scrollable;
	private ThreePartsLayoutWidget layout;
	private ButtonWidget deleteButton;
	private ButtonWidget copyButton;
	private boolean armed;
	private long armedUntil;
	private long copiedUntil;
	private String json = "";

	public KbDetailScreen(Screen parent, String address) {
		super(Text.literal(address));
		this.parent = parent;
		this.address = address;
	}

	@Override
	protected void init() {
		ServerRecord rec = KbStore.recordFor(address);
		json = rec.toJson(KbStore.gson());
		int listWidth = Math.min(LIST_WIDTH, width - 16);
		layout = new ThreePartsLayoutWidget(this);
		layout.addHeader(title, textRenderer);
		DirectionalLayoutWidget body = layout.addBody(DirectionalLayoutWidget.vertical());
		for (String line : summaryLines(rec)) {
			TextWidget w = new TextWidget(Text.literal(line).formatted(Formatting.GRAY), textRenderer);
			w.setMaxWidth(listWidth);
			body.add(w);
		}
		TextWidget jsonLabel = new TextWidget(Text.literal("--- JSON ---").formatted(Formatting.DARK_GRAY),
			textRenderer);
		body.add(jsonLabel, positioner -> positioner.marginTop(6).marginBottom(2));
		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(0);
		String[] lines = json.split("\n");
		for (int i = 0; i < Math.min(lines.length, JSON_LINES); i++) {
			TextWidget w = new TextWidget(Text.literal(strip(lines[i])), textRenderer);
			w.setMaxWidth(listWidth);
			content.add(w);
		}
		if (lines.length > JSON_LINES) {
			content.add(new TextWidget(Text.literal("…以下省略（コピーで全文）")
				.formatted(Formatting.DARK_GRAY), textRenderer));
		}
		scrollable = new ScrollableLayoutWidget(client, content, SCROLL_MIN_HEIGHT);
		scrollable.setWidth(listWidth);
		body.add(scrollable);
		DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(6));
		copyButton = ButtonWidget.builder(Text.literal("コピー"), button -> onCopy()).width(100).build();
		deleteButton = ButtonWidget.builder(Text.literal("削除"), button -> onDelete()).width(100).build();
		footer.add(copyButton);
		footer.add(deleteButton);
		footer.add(ButtonWidget.builder(Text.literal("戻る"), button -> close()).width(100).build());
		layout.forEachChild(this::addDrawableChild);
		refreshWidgetPositions();
	}

	private void onCopy() {
		try {
			MinecraftClient.getInstance().keyboard.setClipboard(json);
			copyButton.setMessage(Text.literal("コピーした"));
			copiedUntil = System.currentTimeMillis() + 1500;
		} catch (Exception ignored) {
		}
	}

	private void onDelete() {
		long now = System.currentTimeMillis();
		if (armed && now < armedUntil) {
			armed = false;
			KbStore.delete(address);
			if (parent instanceof KbServerListScreen list) {
				list.refreshList();
			}
			close();
			return;
		}
		armed = true;
		armedUntil = now + ARM_MS;
		deleteButton.setMessage(Text.literal("本当に削除？"));
	}

	private static ArrayList<String> summaryLines(ServerRecord rec) {
		ServerRecord.Summary sum = rec.summary();
		ArrayList<String> out = new ArrayList<>();
		out.add(dirLine("殴り", sum.dealt));
		out.add(dirLine("被弾", sum.received));
		topWeapons(out, "殴り", sum.dealt);
		topWeapons(out, "被弾", sum.received);
		if (rec.excluded.isEmpty()) {
			out.add("除外: なし");
		} else {
			StringBuilder sb = new StringBuilder("除外:");
			for (Map.Entry<String, Long> e : rec.excluded.entrySet()) {
				sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
			}
			out.add(sb.toString());
		}
		if (rec.dropped > 0) {
			out.add("古いサンプル " + rec.dropped + " 件を破棄（上限200）");
		}
		return out;
	}

	private static String dirLine(String name, ServerRecord.Dir dir) {
		return name + ": n=" + dir.n + " kh=" + fmt(dir.kh) + "±" + fmt(dir.khIqr)
			+ " kv=" + fmt(dir.kv) + "±" + fmt(dir.kvIqr) + " (縦n=" + dir.nV
			+ ", 高確信" + pct(dir.highFrac) + ", 未校正" + pct(dir.seedFrac)
			+ (dir.weak > 0 ? ", 弱" + dir.weak : "") + ")";
	}

	private static void topWeapons(ArrayList<String> out, String name, ServerRecord.Dir dir) {
		if (dir.byWeapon.isEmpty()) {
			return;
		}
		StringBuilder sb = new StringBuilder("  " + name + "武器:");
		int shown = 0;
		for (Map.Entry<String, ServerRecord.WStat> e : dir.byWeapon.entrySet()) {
			if (shown >= 5) {
				sb.append(" …");
				break;
			}
			sb.append(' ').append(shortId(e.getKey())).append("(n=").append(e.getValue().n)
				.append(" kh=").append(fmt(e.getValue().kh)).append(')');
			shown++;
		}
		out.add(sb.toString());
	}

	private static String shortId(String id) {
		int slash = id.lastIndexOf(':');
		int under = id.lastIndexOf('_');
		String s = slash >= 0 ? id.substring(slash + 1) : id;
		if (under > 0 && s.length() > 12) {
			s = s.substring(under + 1);
		}
		return s.length() > 14 ? s.substring(0, 14) : s;
	}

	private static String fmt(double v) {
		return v < 0 ? "-" : String.format(Locale.ROOT, "%.2f", v);
	}

	private static String pct(double v) {
		return v < 0 ? "-" : Math.round(v * 100) + "%";
	}

	private static String strip(String line) {
		return line.length() > 90 ? line.substring(0, 90) : line;
	}

	@Override
	protected void refreshWidgetPositions() {
		scrollable.setHeight(SCROLL_MIN_HEIGHT);
		layout.refreshPositions();
		int extra = height - layout.getFooterHeight() - scrollable.getNavigationFocus().getBottom();
		scrollable.setHeight(scrollable.getHeight() + extra);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		long now = System.currentTimeMillis();
		if (armed && now > armedUntil) {
			armed = false;
			deleteButton.setMessage(Text.literal("削除"));
		}
		if (copiedUntil != 0 && now > copiedUntil) {
			copiedUntil = 0;
			copyButton.setMessage(Text.literal("コピー"));
		}
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}
}
