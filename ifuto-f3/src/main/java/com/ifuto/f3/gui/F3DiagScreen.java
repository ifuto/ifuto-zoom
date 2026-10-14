package com.ifuto.f3.gui;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.SpoofBridge;
import com.ifuto.f3.SpoofEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** 見つけた F3 行の一覧。詐称が効いているかの確認用。 */
public final class F3DiagScreen extends Screen {
	private final Screen parent;

	public F3DiagScreen(Screen parent) {
		super(Text.literal("F3 Faker 診断"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int listWidth = Math.min(500, width - 16);
		ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this);
		layout.addHeader(title, textRenderer);
		DirectionalLayoutWidget body = layout.addBody(DirectionalLayoutWidget.vertical());

		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(3);
		F3Config cfg = F3Config.get();

		content.add(line("全体: " + (cfg.masterEnabled ? "ON" : "OFF")
			+ "　FPS: " + cfg.fpsMode + "　座標: " + cfg.coordsMode, Formatting.WHITE, listWidth));
		content.add(line("BetterF3 フック: " + (SpoofBridge.BetterF3State.hooked ? "当たった" : "まだ（BetterF3 無しか未表示）"),
			Formatting.GRAY, listWidth));

		content.add(line("--- バニラ行 (" + SpoofEngine.vanillaSeen.size() + ") ---", Formatting.YELLOW, listWidth));
		if (SpoofEngine.vanillaSeen.isEmpty()) {
			content.add(line("まだ無し。F3 を開くと出る。", Formatting.GRAY, listWidth));
		}
		for (var entry : SpoofEngine.vanillaSeen.entrySet()) {
			content.add(line("[" + entry.getKey() + "] " + entry.getValue(), Formatting.GRAY, listWidth));
		}

		content.add(line("--- BetterF3 行 (" + SpoofEngine.betterSeen.size() + ") ---", Formatting.YELLOW, listWidth));
		if (SpoofEngine.betterSeen.isEmpty()) {
			content.add(line("まだ無し。BetterF3 入りで F3 を開くと出る。", Formatting.GRAY, listWidth));
		}
		for (var entry : SpoofEngine.betterSeen.entrySet()) {
			content.add(line("[" + entry.getKey() + "] " + entry.getValue(), Formatting.GRAY, listWidth));
		}

		ScrollableLayoutWidget scrollable = new ScrollableLayoutWidget(client, content, 140);
		scrollable.setWidth(listWidth);
		body.add(scrollable);

		DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(6));
		footer.add(ButtonWidget.builder(Text.literal("更新"), b -> clearAndInit()).width(90).build());
		footer.add(ButtonWidget.builder(Text.literal("戻る"), b -> close()).width(90).build());
		layout.forEachChild(this::addDrawableChild);
		refreshWidgetPositions();
	}

	private TextWidget line(String text, Formatting color, int listWidth) {
		TextWidget w = new TextWidget(Text.literal(text).formatted(color), textRenderer);
		w.setMaxWidth(listWidth);
		return w;
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}
}
