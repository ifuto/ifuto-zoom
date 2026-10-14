package com.ifuto.f3.gui;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.SpoofBridge;
import com.ifuto.f3.SpoofEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Mod Menu から開く F3 詐称の設定画面。 */
public final class F3ConfigScreen extends Screen {
	private static final int FIELD_W = 90;
	private static final int ROW_W = 340;

	private final Screen parent;
	private final F3Config cfg;
	private TextWidget preview;

	public F3ConfigScreen(Screen parent) {
		super(Text.literal("F3 Faker 設定"));
		this.parent = parent;
		this.cfg = F3Config.get();
	}

	@Override
	protected void init() {
		int listWidth = Math.min(ROW_W + 20, width - 16);
		ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this);
		layout.addHeader(title, textRenderer);
		DirectionalLayoutWidget body = layout.addBody(DirectionalLayoutWidget.vertical());

		preview = new TextWidget(Text.literal("...").formatted(Formatting.GRAY), textRenderer);
		preview.setMaxWidth(listWidth);
		body.add(preview, positioner -> positioner.marginBottom(4));

		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(4);

		content.add(header("■ 全体"));
		content.add(toggleRow("詐称する", cfg.masterEnabled, v -> cfg.masterEnabled = v));

		content.add(header("■ FPS"));
		content.add(cycleRow("モード", new String[]{"FIXED", "FOLLOW", "RANGE"}, cfg.fpsMode, v -> cfg.fpsMode = v));
		content.add(numRow("固定値", String.valueOf(cfg.fpsFixed), v -> cfg.fpsFixed = parseInt(v, cfg.fpsFixed)));
		content.add(numRow("倍率 (FOLLOW)", String.valueOf(cfg.fpsScale), v -> cfg.fpsScale = parseDouble(v, cfg.fpsScale)));
		content.add(numRow("加算 (FOLLOW)", String.valueOf(cfg.fpsOffset), v -> cfg.fpsOffset = parseInt(v, cfg.fpsOffset)));
		content.add(numRow("下限", String.valueOf(cfg.fpsLo), v -> cfg.fpsLo = parseInt(v, cfg.fpsLo)));
		content.add(numRow("上限", String.valueOf(cfg.fpsHi), v -> cfg.fpsHi = parseInt(v, cfg.fpsHi)));
		content.add(numRow("刻み", String.valueOf(cfg.fpsStep), v -> cfg.fpsStep = parseInt(v, cfg.fpsStep)));
		content.add(numRow("更新ms", String.valueOf(cfg.fpsJitterMs), v -> cfg.fpsJitterMs = parseInt(v, cfg.fpsJitterMs)));

		content.add(header("■ 座標"));
		content.add(cycleRow("モード", new String[]{"OFF", "OFFSET", "ANCHOR", "FIXED"}, cfg.coordsMode, v -> cfg.coordsMode = v));
		content.add(xyzRow("ずらし (OFFSET)", cfg.offX, cfg.offY, cfg.offZ,
			v -> cfg.offX = v, v -> cfg.offY = v, v -> cfg.offZ = v));
		content.add(xyzRow("見せる座標", cfg.anchorFakeX, cfg.anchorFakeY, cfg.anchorFakeZ,
			v -> cfg.anchorFakeX = v, v -> cfg.anchorFakeY = v, v -> cfg.anchorFakeZ = v));
		content.add(xyzRow("完全固定 (FIXED)", cfg.fixX, cfg.fixY, cfg.fixZ,
			v -> cfg.fixX = v, v -> cfg.fixY = v, v -> cfg.fixZ = v));
		content.add(anchorRow());

		content.add(header("■ 文字の上書き（空 = 本物）"));
		content.add(textRow("向き", cfg.facingOverride, v -> cfg.facingOverride = v));
		content.add(textRow("バイオーム", cfg.biomeOverride, v -> cfg.biomeOverride = v));
		content.add(textRow("ディメンション", cfg.dimensionOverride, v -> cfg.dimensionOverride = v));
		content.add(textRow("明るさ", cfg.lightOverride, v -> cfg.lightOverride = v));
		content.add(textRow("日数・難易度", cfg.dayOverride, v -> cfg.dayOverride = v));
		content.add(textRow("CPU", cfg.cpuOverride, v -> cfg.cpuOverride = v));
		content.add(textRow("GPU", cfg.gpuOverride, v -> cfg.gpuOverride = v));
		content.add(textRow("画面", cfg.displayOverride, v -> cfg.displayOverride = v));
		content.add(textRow("OpenGL", cfg.glOverride, v -> cfg.glOverride = v));
		content.add(textRow("ドライバ", cfg.driverOverride, v -> cfg.driverOverride = v));
		content.add(textRow("Java", cfg.javaOverride, v -> cfg.javaOverride = v));
		content.add(numRow("メモリ使用MB (0=本物)", String.valueOf(cfg.memUsedMB), v -> cfg.memUsedMB = parseInt(v, cfg.memUsedMB)));
		content.add(numRow("メモリ全体MB (0=本物)", String.valueOf(cfg.memTotalMB), v -> cfg.memTotalMB = parseInt(v, cfg.memTotalMB)));
		content.add(numRow("確保速度MB/s (0=本物)", String.valueOf(cfg.allocRateMBs), v -> cfg.allocRateMBs = parseInt(v, cfg.allocRateMBs)));

		content.add(header("■ 汎用ルール（正規表現）"));
		for (int i = 0; i < cfg.rules.size(); i++) {
			content.add(ruleRow(i, cfg.rules.get(i)));
		}

		ScrollableLayoutWidget scrollable = new ScrollableLayoutWidget(client, content, 140);
		scrollable.setWidth(listWidth);
		body.add(scrollable);

		DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(6));
		footer.add(ButtonWidget.builder(Text.literal("診断"), b -> openDiag()).width(90).build());
		footer.add(ButtonWidget.builder(Text.literal("完了"), b -> close()).width(90).build());
		layout.forEachChild(this::addDrawableChild);
		refreshWidgetPositions();
		updatePreview();
	}

	private void openDiag() {
		MinecraftClient.getInstance().setScreen(new F3DiagScreen(this));
	}

	@Override
	public void tick() {
		super.tick();
		updatePreview();
	}

	private void updatePreview() {
		if (preview == null) {
			return;
		}
		try {
			SpoofEngine.Ctx ctx = SpoofBridge.capture(SpoofBridge.realFps);
			int shownFps = cfg.masterEnabled ? SpoofEngine.fps(cfg, ctx) : ctx.realFps();
			double[] p = SpoofEngine.spoofPos(cfg, ctx);
			preview.setMessage(Text.literal("FPS " + ctx.realFps() + " → " + shownFps
				+ "　座標 " + fmt1(ctx.rx()) + "," + fmt1(ctx.ry()) + "," + fmt1(ctx.rz())
				+ " → " + fmt1(p[0]) + "," + fmt1(p[1]) + "," + fmt1(p[2])).formatted(Formatting.GRAY));
		} catch (Exception ignored) {
		}
	}

	private static String fmt1(double v) {
		return String.format(java.util.Locale.ROOT, "%.1f", v);
	}

	@Override
	public void close() {
		cfg.save();
		MinecraftClient.getInstance().setScreen(parent);
	}

	// ---------- 行づくり ----------

	private TextWidget header(String text) {
		TextWidget w = new TextWidget(Text.literal(text).formatted(Formatting.YELLOW), textRenderer);
		w.setMaxWidth(ROW_W);
		return w;
	}

	private DirectionalLayoutWidget toggleRow(String label, boolean initial, java.util.function.Consumer<Boolean> setter) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(6);
		TextWidget name = new TextWidget(Text.literal(label), textRenderer);
		name.setMaxWidth(ROW_W - FIELD_W - 10);
		row.add(name);
		boolean[] state = {initial};
		row.add(ButtonWidget.builder(toggleText(state[0]), b -> {
			state[0] = !state[0];
			b.setMessage(toggleText(state[0]));
			setter.accept(state[0]);
		}).width(FIELD_W).build());
		return row;
	}

	private static Text toggleText(boolean on) {
		return Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED);
	}

	private DirectionalLayoutWidget cycleRow(String label, String[] options, String initial,
			java.util.function.Consumer<String> setter) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(6);
		TextWidget name = new TextWidget(Text.literal(label), textRenderer);
		name.setMaxWidth(ROW_W - FIELD_W - 10);
		row.add(name);
		int[] idx = {0};
		for (int i = 0; i < options.length; i++) {
			if (options[i].equals(initial)) {
				idx[0] = i;
			}
		}
		row.add(ButtonWidget.builder(Text.literal(options[idx[0]]), b -> {
			idx[0] = (idx[0] + 1) % options.length;
			b.setMessage(Text.literal(options[idx[0]]));
			setter.accept(options[idx[0]]);
		}).width(FIELD_W).build());
		return row;
	}

	private DirectionalLayoutWidget numRow(String label, String initial, java.util.function.Consumer<String> setter) {
		return fieldRow(label, initial, FIELD_W, setter);
	}

	private DirectionalLayoutWidget textRow(String label, String initial, java.util.function.Consumer<String> setter) {
		return fieldRow(label, initial, 200, setter);
	}

	private DirectionalLayoutWidget fieldRow(String label, String initial, int fieldW,
			java.util.function.Consumer<String> setter) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(6);
		TextWidget name = new TextWidget(Text.literal(label), textRenderer);
		name.setMaxWidth(ROW_W - fieldW - 10);
		row.add(name);
		TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, fieldW, 20, Text.literal(label));
		field.setMaxLength(128);
		field.setText(initial);
		field.setChangedListener(setter::accept);
		row.add(field);
		return row;
	}

	private DirectionalLayoutWidget xyzRow(String label, double x, double y, double z,
			java.util.function.DoubleConsumer sx, java.util.function.DoubleConsumer sy, java.util.function.DoubleConsumer sz) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(4);
		TextWidget name = new TextWidget(Text.literal(label), textRenderer);
		name.setMaxWidth(150);
		row.add(name);
		row.add(doubleField(x, sx));
		row.add(doubleField(y, sy));
		row.add(doubleField(z, sz));
		return row;
	}

	private TextFieldWidget doubleField(double initial, java.util.function.DoubleConsumer setter) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 58, 20, Text.literal("数値"));
		field.setMaxLength(24);
		field.setText(trimNum(initial));
		field.setChangedListener(v -> {
			try {
				setter.accept(Double.parseDouble(v.trim()));
			} catch (Exception ignored) {
			}
		});
		return field;
	}

	private static String trimNum(double v) {
		if (v == Math.floor(v) && !Double.isInfinite(v)) {
			return String.valueOf((long) v);
		}
		return String.valueOf(v);
	}

	private DirectionalLayoutWidget anchorRow() {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(6);
		boolean inWorld = client != null && client.player != null;
		ButtonWidget set = ButtonWidget.builder(Text.literal("今いる場所を基準点に"), b -> {
			if (client != null && client.player != null) {
				cfg.anchorRealX = client.player.getX();
				cfg.anchorRealY = client.player.getY();
				cfg.anchorRealZ = client.player.getZ();
				cfg.anchorSet = true;
				clearAndInit();
			}
		}).width(150).build();
		set.active = inWorld;
		row.add(set);
		String status = cfg.anchorSet
			? "基準: " + fmt1(cfg.anchorRealX) + "," + fmt1(cfg.anchorRealY) + "," + fmt1(cfg.anchorRealZ)
			: (inWorld ? "未設定" : "未設定（ワールド外）");
		TextWidget w = new TextWidget(Text.literal(status).formatted(Formatting.GRAY), textRenderer);
		w.setMaxWidth(ROW_W - 160);
		row.add(w);
		return row;
	}

	private DirectionalLayoutWidget ruleRow(int index, F3Config.Rule rule) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(4);
		TextWidget name = new TextWidget(Text.literal("R" + (index + 1)), textRenderer);
		name.setMaxWidth(24);
		row.add(name);
		TextFieldWidget pat = new TextFieldWidget(textRenderer, 0, 0, 130, 20, Text.literal("正規表現"));
		pat.setMaxLength(128);
		pat.setText(rule.pattern);
		pat.setChangedListener(v -> rule.pattern = v);
		row.add(pat);
		TextFieldWidget rep = new TextFieldWidget(textRenderer, 0, 0, 110, 20, Text.literal("置換"));
		rep.setMaxLength(128);
		rep.setText(rule.replacement);
		rep.setChangedListener(v -> rule.replacement = v);
		row.add(rep);
		boolean[] state = {rule.enabled};
		row.add(ButtonWidget.builder(toggleText(state[0]), b -> {
			state[0] = !state[0];
			b.setMessage(toggleText(state[0]));
			rule.enabled = state[0]);
		}).width(52).build());
		return row;
	}

	private static int parseInt(String v, int fallback) {
		try {
			return Integer.parseInt(v.trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	private static double parseDouble(String v, double fallback) {
		try {
			return Double.parseDouble(v.trim());
		} catch (Exception e) {
			return fallback;
		}
	}
}
