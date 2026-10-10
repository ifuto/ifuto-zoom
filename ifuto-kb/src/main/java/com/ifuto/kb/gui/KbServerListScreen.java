package com.ifuto.kb.gui;

import java.util.List;

import com.ifuto.kb.model.BaselineTable;
import com.ifuto.kb.model.ServerRecord;
import com.ifuto.kb.store.KbStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.LayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class KbServerListScreen extends Screen {
	private static final int LIST_WIDTH = 448;
	private static final int SCROLL_MIN_HEIGHT = 140;

	private final Screen parent;
	private ScrollableLayoutWidget scrollable;
	private ThreePartsLayoutWidget layout;

	public KbServerListScreen(Screen parent) {
		super(Text.literal("KB Meter — サーバー一覧"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int listWidth = Math.min(LIST_WIDTH, width - 16);
		layout = new ThreePartsLayoutWidget(this);
		layout.addHeader(title, textRenderer);
		DirectionalLayoutWidget body = layout.addBody(DirectionalLayoutWidget.vertical());
		BaselineTable base = KbStore.baselines();
		body.add(new TextWidget(Text.literal("バニラ校正: " + base.totalSamples()
			+ "サンプル (" + base.calibratedCells() + "セル)").formatted(Formatting.GRAY), textRenderer),
			positioner -> positioner.marginBottom(2));
		if (base.totalSamples() < 10) {
			body.add(new TextWidget(Text.literal("シングルでMOBを殴る・殴られると校正される")
				.formatted(Formatting.DARK_GRAY), textRenderer), positioner -> positioner.marginBottom(6));
		}
		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(6);
		List<ServerRecord> records = KbStore.all();
		if (records.isEmpty()) {
			content.add(new TextWidget(Text.literal("まだ計測データがありません"), textRenderer));
		} else {
			for (ServerRecord rec : records) {
				content.add(row(rec, listWidth));
			}
		}
		scrollable = new ScrollableLayoutWidget(client, content, SCROLL_MIN_HEIGHT);
		scrollable.setWidth(listWidth);
		body.add(scrollable);
		DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(6));
		footer.add(ButtonWidget.builder(Text.literal("更新"), button -> clearAndInit()).width(90).build());
		footer.add(ButtonWidget.builder(Text.literal("閉じる"), button -> close()).width(90).build());
		layout.forEachChild(this::addDrawableChild);
		refreshWidgetPositions();
	}

	private LayoutWidget row(ServerRecord rec, int listWidth) {
		ServerRecord.Summary sum = rec.summary();
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(6);
		DirectionalLayoutWidget labels = DirectionalLayoutWidget.vertical().spacing(1);
		TextWidget addr = new TextWidget(Text.literal(displayAddress(rec, sum)), textRenderer);
		TextWidget det = new TextWidget(Text.literal(describe(sum)).formatted(Formatting.GRAY), textRenderer);
		addr.setMaxWidth(Math.max(40, listWidth - 100));
		det.setMaxWidth(Math.max(40, listWidth - 100));
		labels.add(addr);
		labels.add(det);
		row.add(labels);
		row.add(ButtonWidget.builder(Text.literal("詳細"), button -> MinecraftClient.getInstance()
			.setScreen(new KbDetailScreen(this, rec.address))).width(64).build());
		return row;
	}

	private static String displayAddress(ServerRecord rec, ServerRecord.Summary sum) {
		boolean uncal = (sum.dealt.seedFrac > 0.5 && sum.dealt.n > 0)
			|| (sum.received.seedFrac > 0.5 && sum.received.n > 0);
		return rec.address + (uncal ? " [未校正]" : "");
	}

	private static String describe(ServerRecord.Summary sum) {
		return "殴り kh=" + fmt(sum.dealt.kh) + " kv=" + fmt(sum.dealt.kv) + " (n=" + sum.dealt.n + ")"
			+ " / 被弾 kh=" + fmt(sum.received.kh) + " kv=" + fmt(sum.received.kv)
			+ " (n=" + sum.received.n + ")";
	}

	private static String fmt(double v) {
		return v < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.2f", v);
	}

	@Override
	protected void refreshWidgetPositions() {
		scrollable.setHeight(SCROLL_MIN_HEIGHT);
		layout.refreshPositions();
		int extra = height - layout.getFooterHeight() - scrollable.getNavigationFocus().getBottom();
		scrollable.setHeight(scrollable.getHeight() + extra);
	}

	/** 詳細画面で削除したあと一覧を作り直す用。 */
	public void refreshList() {
		clearAndInit();
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}
}
