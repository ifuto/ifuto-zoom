package com.ifuto.f3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * F3 詐称の設定。config/ifuto-f3.json に保存される。
 * フィールドは素直な POJO。GUI から直接いじる。
 */
public final class F3Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static F3Config instance;

	public boolean masterEnabled = true;

	// ---- FPS: FIXED / FOLLOW / RANGE ----
	public String fpsMode = "RANGE";
	public int fpsFixed = 240;
	public double fpsScale = 1.0;
	public int fpsOffset = 0;
	public int fpsLo = 120;
	public int fpsHi = 240;
	public int fpsStep = 5;
	public int fpsJitterMs = 500;

	// ---- 座標: OFF / OFFSET / ANCHOR / FIXED ----
	public String coordsMode = "ANCHOR";
	public double offX = 0;
	public double offY = 0;
	public double offZ = 0;
	public boolean anchorSet = false;
	public double anchorRealX = 0;
	public double anchorRealY = 0;
	public double anchorRealZ = 0;
	public double anchorFakeX = 0;
	public double anchorFakeY = -59.5;
	public double anchorFakeZ = 0;
	public double fixX = 0;
	public double fixY = -59.5;
	public double fixZ = 0;

	// ---- 文字の上書き（空 = 本物 or 自動） ----
	public String facingOverride = "";
	public String biomeOverride = "";
	public String dimensionOverride = "";
	public String lightOverride = "";
	public String dayOverride = "";
	public int memUsedMB = 2048;
	public int memTotalMB = 8192;
	public int allocRateMBs = 0;
	public String cpuOverride = "";
	public String gpuOverride = "";
	public String displayOverride = "";
	public String glOverride = "";
	public String driverOverride = "";
	public String javaOverride = "";

	/** 汎用ルール（正規表現）。行の種類を問わず最後に適用される。 */
	public List<Rule> rules = new ArrayList<>();

	public static final class Rule {
		public String pattern = "";
		public String replacement = "";
		public boolean enabled = false;
	}

	/** コンパイル済みルール。毎フレーム compile しないためのキャッシュ。 */
	public transient List<CompiledRule> compiled = new ArrayList<>();

	public record CompiledRule(Pattern pattern, String replacement) {
	}

	public static F3Config get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		Path path = file();
		F3Config loaded = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				loaded = GSON.fromJson(reader, F3Config.class);
			} catch (Exception ignored) {
				loaded = null;
			}
		}
		instance = loaded != null ? loaded : new F3Config();
		instance.ensureRuleSlots();
		instance.rebuildRules();
	}

	public void save() {
		ensureRuleSlots();
		rebuildRules();
		try {
			Files.createDirectories(file().getParent());
			try (Writer writer = Files.newBufferedWriter(file())) {
				GSON.toJson(this, writer);
			}
		} catch (IOException ignored) {
		}
	}

	private void ensureRuleSlots() {
		if (rules == null) {
			rules = new ArrayList<>();
		}
		while (rules.size() < 4) {
			rules.add(new Rule());
		}
	}

	private void rebuildRules() {
		List<CompiledRule> list = new ArrayList<>();
		ensureRuleSlots();
		for (Rule rule : rules) {
			if (rule == null || !rule.enabled || rule.pattern == null || rule.pattern.isEmpty()) {
				continue;
			}
			try {
				list.add(new CompiledRule(Pattern.compile(rule.pattern), rule.replacement != null ? rule.replacement : ""));
			} catch (Exception ignored) {
				// 変な正規表現は無視（落とさない）
			}
		}
		compiled = list;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("ifuto-f3.json");
	}
}
