package com.ifuto.f3;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F3 詐称の頭脳。バニラも BetterF3 もここを通る。
 * 全部「絶対値の置き換え」（本物の値から計算し直す）なので、
 * 二重に適用されてもズレない。
 */
public final class SpoofEngine {
	private SpoofEngine() {
	}

	/** 書き換えに必要な本物の値。テストでは手で作れる。 */
	public record Ctx(double rx, double ry, double rz, int realFps, String dimension, long nowMs) {
	}

	private static final Random RNG = new Random();
	private static int lastFps = 144;
	private static long lastFpsTickMs = 0;

	/** 見つけた行の見本（診断画面用。上限 64）。 */
	public static final Map<String, String> vanillaSeen = cappedMap();
	public static final Map<String, String> betterSeen = cappedMap();

	private static Map<String, String> cappedMap() {
		return new LinkedHashMap<>(64, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> e) {
				return size() > 64;
			}
		};
	}

	private static void noteSeen(Map<String, String> map, String key, String sample) {
		if (key == null) {
			return;
		}
		String s = sample == null ? "" : sample;
		if (s.length() > 80) {
			s = s.substring(0, 80);
		}
		map.put(key, s);
	}

	/** テスト用に FPS の状態を戻す。 */
	public static void resetForTest() {
		lastFps = 144;
		lastFpsTickMs = 0;
	}

	// ================= FPS =================

	/** 表示する FPS。同じフレーム内では同じ値を返す。 */
	public static synchronized int fps(F3Config cfg, Ctx ctx) {
		long jitter = Math.max(50, cfg.fpsJitterMs);
		if (lastFpsTickMs != 0 && ctx.nowMs() - lastFpsTickMs < jitter) {
			return lastFps;
		}
		lastFpsTickMs = ctx.nowMs();
		int step = Math.max(1, cfg.fpsStep);
		int lo = Math.min(cfg.fpsLo, cfg.fpsHi);
		int hi = Math.max(cfg.fpsLo, cfg.fpsHi);
		int v;
		switch (cfg.fpsMode) {
			case "FIXED" -> v = cfg.fpsFixed;
			case "FOLLOW" -> v = clamp(roundStep((int) Math.round(ctx.realFps() * cfg.fpsScale + cfg.fpsOffset), step), lo, hi);
			default -> {
				// RANGE: ランダムウォークで範囲を行き来する
				int cur = lastFps;
				if (cur < lo || cur > hi) {
					cur = (lo + hi) / 2;
				}
				int next = cur + (RNG.nextInt(3) - 1) * step;
				if (next < lo || next > hi) {
					next = cur - (RNG.nextInt(3) - 1) * step;
				}
				v = clamp(roundStep(next, step), lo, hi);
			}
		}
		lastFps = Math.max(1, v);
		return lastFps;
	}

	private static int roundStep(int v, int step) {
		return (int) Math.round(v / (double) step) * step;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ================= 座標 =================

	/** 表示する座標 [x, y, z]。 */
	public static double[] spoofPos(F3Config cfg, Ctx ctx) {
		return switch (cfg.coordsMode) {
			case "OFFSET" -> new double[]{ctx.rx() + cfg.offX, ctx.ry() + cfg.offY, ctx.rz() + cfg.offZ};
			case "ANCHOR" -> {
				if (!cfg.anchorSet) {
					yield new double[]{ctx.rx(), ctx.ry(), ctx.rz()};
				}
				yield new double[]{
					cfg.anchorFakeX + (ctx.rx() - cfg.anchorRealX),
					cfg.anchorFakeY + (ctx.ry() - cfg.anchorRealY),
					cfg.anchorFakeZ + (ctx.rz() - cfg.anchorRealZ),
				};
			}
			case "FIXED" -> new double[]{cfg.fixX, cfg.fixY, cfg.fixZ};
			default -> new double[]{ctx.rx(), ctx.ry(), ctx.rz()};
		};
	}

	public static int[] spoofBlock(F3Config cfg, Ctx ctx) {
		double[] p = spoofPos(cfg, ctx);
		return new int[]{(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
	}

	public static int[] spoofChunk(F3Config cfg, Ctx ctx) {
		int[] b = spoofBlock(cfg, ctx);
		return new int[]{b[0] >> 4, b[1] >> 4, b[2] >> 4};
	}

	public static int[] spoofChunkRel(F3Config cfg, Ctx ctx) {
		int[] b = spoofBlock(cfg, ctx);
		return new int[]{b[0] & 15, b[1] & 15, b[2] & 15};
	}

	/** ネザー換算の表示座標。オーバーワールドにいれば /8、ネザーにいれば x8。 */
	public static double[] spoofNether(F3Config cfg, Ctx ctx) {
		double[] p = spoofPos(cfg, ctx);
		String dim = ctx.dimension() == null ? "" : ctx.dimension();
		if (dim.endsWith("the_nether")) {
			return new double[]{p[0] * 8, p[1], p[2] * 8};
		}
		if (dim.endsWith("the_end") || dim.isEmpty()) {
			return p;
		}
		return new double[]{p[0] / 8, p[1], p[2] / 8};
	}

	// ================= バニラ行の書き換え =================

	private static final Pattern VANILLA_FPS = Pattern.compile("(\\d+)(\\s+fps)", Pattern.CASE_INSENSITIVE);
	private static final Pattern COORD_TRIPLE = Pattern.compile("(-?\\d+\\.\\d+)\\s*/\\s*(-?\\d+\\.\\d+)\\s*/\\s*(-?\\d+\\.\\d+)");
	private static final Pattern INT_TRIPLE = Pattern.compile("(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");
	private static final Pattern MEM_SHAPE = Pattern.compile("(\\d+)%\\s*(\\d+)/(\\d+)\\s*MB");
	private static final Pattern ALLOC_SHAPE = Pattern.compile("(\\d+)\\s*MB/s");

	/** バニラ F3 の 1 行を書き換える。section は DebugHudLines のセクション ID。 */
	public static String rewriteVanilla(F3Config cfg, String section, String line, Ctx ctx) {
		noteSeen(vanillaSeen, section == null || section.isEmpty() ? "(default)" : section, line);
		if (cfg == null || !cfg.masterEnabled || line == null) {
			return line;
		}
		String out = line;

		// FPS ("120 fps ..." の数字)
		Matcher fpsMatcher = VANILLA_FPS.matcher(out);
		if (fpsMatcher.find()) {
			out = fpsMatcher.replaceFirst(Matcher.quoteReplacement(fps(cfg, ctx) + fpsMatcher.group(2)));
		}

		// 座標 ("XYZ: 1.2 / 3.4 / 5.6")
		if (out.contains("XYZ")) {
			double[] p = spoofPos(cfg, ctx);
			Matcher m = COORD_TRIPLE.matcher(out);
			StringBuffer sb = new StringBuffer();
			while (m.find()) {
				m.appendReplacement(sb, Matcher.quoteReplacement(
					withDecimals(p[0], m.group(1)) + " / " + withDecimals(p[1], m.group(2)) + " / " + withDecimals(p[2], m.group(3))));
			}
			m.appendTail(sb);
			out = sb.toString();
		}

		// ブロック座標・チャンク座標（整数 3 つ）
		if (out.startsWith("Block:")) {
			out = replaceIntTriple(out, spoofBlock(cfg, ctx));
		} else if (out.startsWith("Chunk:")) {
			out = replaceIntTriple(out, spoofChunk(cfg, ctx));
		}

		// 向き・バイオーム・明るさ・ディメンション
		if (!cfg.facingOverride.isEmpty() && out.startsWith("Facing:")) {
			out = out.replaceFirst("^Facing:\\s*\\S+", Matcher.quoteReplacement("Facing: " + cfg.facingOverride));
		}
		if (!cfg.biomeOverride.isEmpty() && out.startsWith("Biome:")) {
			out = "Biome: " + cfg.biomeOverride;
		}
		if (!cfg.lightOverride.isEmpty() && out.startsWith("Light:")) {
			out = "Light: " + cfg.lightOverride;
		}
		if (!cfg.dimensionOverride.isEmpty() && out.startsWith("Dimension:")) {
			out = "Dimension: " + cfg.dimensionOverride;
		}
		if (!cfg.dayOverride.isEmpty() && (out.startsWith("Day:") || out.startsWith("Local Difficulty:") || out.startsWith("Difficulty:"))) {
			out = applyGenericRules(cfg, out);
		}

		// スペック系（行頭一致）
		out = replacePrefixLine(out, "CPU:", cfg.cpuOverride);
		out = replacePrefixLine(out, "Processor:", cfg.cpuOverride);
		out = replacePrefixLine(out, "GPU:", cfg.gpuOverride);
		out = replacePrefixLine(out, "Renderer:", cfg.gpuOverride);
		out = replacePrefixLine(out, "Display:", cfg.displayOverride);
		out = replacePrefixLine(out, "Java:", cfg.javaOverride);
		out = replacePrefixLine(out, "OpenGL:", cfg.glOverride);
		out = replacePrefixLine(out, "Driver:", cfg.driverOverride);

		// メモリ（"45% 1024/4096MB" の形を保って数字だけ変える）
		if (cfg.memTotalMB > 0) {
			Matcher m = MEM_SHAPE.matcher(out);
			if (m.find()) {
				int used = Math.max(0, cfg.memUsedMB);
				int total = Math.max(1, cfg.memTotalMB);
				int pct = (int) Math.round(used * 100.0 / total);
				out = m.replaceFirst(pct + "% " + used + "/" + total + "MB");
			}
		}
		if (cfg.allocRateMBs > 0) {
			Matcher m = ALLOC_SHAPE.matcher(out);
			if (m.find()) {
				out = m.replaceFirst(cfg.allocRateMBs + " MB/s");
			}
		}

		// 汎用ルールは最後
		return applyGenericRules(cfg, out);
	}

	private static String replaceIntTriple(String line, int[] v) {
		Matcher m = INT_TRIPLE.matcher(line);
		if (m.find()) {
			return m.replaceFirst(v[0] + " " + v[1] + " " + v[2]);
		}
		return line;
	}

	private static String replacePrefixLine(String line, String prefix, String override) {
		if (override == null || override.isEmpty() || !line.startsWith(prefix)) {
			return line;
		}
		return prefix + " " + override;
	}

	/** 元の小数点以下の桁数を保ってフォーマット。 */
	static String withDecimals(double v, String orig) {
		int dot = orig.indexOf('.');
		int decimals = dot < 0 ? 0 : orig.length() - dot - 1;
		if (decimals < 0) {
			decimals = 0;
		}
		if (decimals > 10) {
			decimals = 10;
		}
		return String.format(Locale.ROOT, "%." + decimals + "f", v);
	}

	// ================= BetterF3 値の書き換え =================

	private static final Pattern ANY_DOUBLE = Pattern.compile("-?\\d+\\.\\d+");
	private static final Pattern ANY_INT = Pattern.compile("(?<![\\d.])(-?\\d+)(?![\\d.])");

	/**
	 * BetterF3 の DebugLine.value() に渡される値を書き換える。
	 * id は行の種類（fps / player_coords / biome ...）。取れなければ null。
	 */
	public static Object rewriteBetterF3(F3Config cfg, String id, Object value, Ctx ctx) {
		noteSeen(betterSeen, id == null ? "(?)" : id, valueString(value));
		if (cfg == null || !cfg.masterEnabled || value == null) {
			return value;
		}
		if (id == null) {
			return rewriteSniffed(cfg, value, ctx);
		}
		String k = id.toLowerCase(Locale.ROOT);

		// FPS は getCurrentFps 側で済んでいるので触らない
		if (k.equals("fps")) {
			return value;
		}

		// 座標ファミリー
		if (k.equals("player_coords")) {
			return rewriteNumbers(value, toObj(spoofPos(cfg, ctx)), false);
		}
		if (k.equals("block_coords")) {
			return rewriteNumbers(value, toObj(spoofBlock(cfg, ctx)), true);
		}
		if (k.equals("chunk_relative_coords")) {
			return rewriteNumbers(value, toObj(spoofChunkRel(cfg, ctx)), true);
		}
		if (k.equals("chunk_coords")) {
			return rewriteNumbers(value, toObj(spoofChunk(cfg, ctx)), true);
		}
		if (k.equals("nether_coords")) {
			return rewriteNumbers(value, toObj(spoofNether(cfg, ctx)), false);
		}
		if (k.contains("velocity")) {
			if ("FIXED".equals(cfg.coordsMode)) {
				return rewriteNumbers(value, new Double[]{0.0, 0.0, 0.0}, false);
			}
			return value;
		}

		// 向き・場所
		if (k.equals("facing") && !cfg.facingOverride.isEmpty()) {
			return replaceWhole(value, cfg.facingOverride);
		}
		if (k.equals("dimension") && !cfg.dimensionOverride.isEmpty()) {
			return replaceWhole(value, cfg.dimensionOverride);
		}
		if (k.equals("biome")) {
			if (!cfg.biomeOverride.isEmpty()) {
				return replaceWhole(value, cfg.biomeOverride);
			}
			String atFake = SpoofBridge.biomeAtFake(cfg, ctx);
			if (atFake != null) {
				return replaceWhole(value, atFake);
			}
			return value;
		}
		if ((k.equals("light_client") || k.equals("light_server")) && !cfg.lightOverride.isEmpty()) {
			return replaceWhole(value, cfg.lightOverride);
		}
		if ((k.equals("day_ticks") || k.equals("days_played") || k.equals("local_difficulty")) && !cfg.dayOverride.isEmpty()) {
			return replaceWhole(value, cfg.dayOverride);
		}

		// メモリ
		if (k.equals("memory_usage") && cfg.memTotalMB > 0) {
			int used = Math.max(0, cfg.memUsedMB);
			int total = Math.max(1, cfg.memTotalMB);
			int pct = (int) Math.round(used * 100.0 / total);
			return replaceWhole(value, pct + "% " + used + "/" + total + "MB");
		}
		if (k.equals("allocation_rate") && cfg.allocRateMBs > 0) {
			return rewriteString(value, s -> {
				Matcher m = ALLOC_SHAPE.matcher(s);
				return m.find() ? m.replaceFirst(cfg.allocRateMBs + " MB/s") : s;
			});
		}

		// スペック系
		if (k.equals("cpu") && !cfg.cpuOverride.isEmpty()) {
			return replaceWhole(value, cfg.cpuOverride);
		}
		if (k.equals("display") && !cfg.displayOverride.isEmpty()) {
			return replaceWhole(value, cfg.displayOverride);
		}
		if (k.equals("gpu") && !cfg.gpuOverride.isEmpty()) {
			return replaceWhole(value, cfg.gpuOverride);
		}
		if (k.equals("gpu_driver") && !cfg.driverOverride.isEmpty()) {
			return replaceWhole(value, cfg.driverOverride);
		}
		if (k.equals("opengl_version") && !cfg.glOverride.isEmpty()) {
			return replaceWhole(value, cfg.glOverride);
		}
		if (k.equals("java_version") && !cfg.javaOverride.isEmpty()) {
			return replaceWhole(value, cfg.javaOverride);
		}

		// 知らない行は汎用ルールだけ
		return rewriteString(value, s -> applyGenericRules(cfg, s));
	}

	/** id が取れなかったときの内容推測フォールバック。 */
	private static Object rewriteSniffed(F3Config cfg, Object value, Ctx ctx) {
		String s = valueString(value);
		if (s == null) {
			return value;
		}
		String low = s.toLowerCase(Locale.ROOT);
		if (low.contains("fps")) {
			return value;
		}
		if (s.contains("XYZ") || COORD_TRIPLE.matcher(s).find()) {
			return rewriteNumbers(value, toObj(spoofPos(cfg, ctx)), false);
		}
		return rewriteString(value, t -> applyGenericRules(cfg, t));
	}

	private static Double[] toObj(double[] v) {
		return new Double[]{v[0], v[1], v[2]};
	}

	private static Double[] toObj(int[] v) {
		return new Double[]{(double) v[0], (double) v[1], (double) v[2]};
	}

	/**
	 * 値の中の数字を順番に x/y/z と置き換える。
	 * String・Text・List のどれでも形を保ったまま返す。
	 */
	static Object rewriteNumbers(Object value, Double[] axes, boolean intMode) {
		int[] cursor = new int[]{0};
		if (value instanceof String s) {
			return rewriteNumberedString(s, axes, cursor, intMode);
		}
		if (value instanceof Text t) {
			return rewriteText(t, s -> rewriteNumberedString(s, axes, cursor, intMode));
		}
		if (value instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object e : list) {
				if (e instanceof String s) {
					out.add(rewriteNumberedString(s, axes, cursor, intMode));
				} else if (e instanceof Text t) {
					out.add(rewriteText(t, s -> rewriteNumberedString(s, axes, cursor, intMode)));
				} else {
					out.add(e);
				}
			}
			return out;
		}
		return value;
	}

	static String rewriteNumberedString(String s, Double[] axes, int[] cursor, boolean intMode) {
		Pattern p = intMode ? ANY_INT : ANY_DOUBLE;
		Matcher m = p.matcher(s);
		StringBuffer sb = new StringBuffer();
		boolean found = false;
		while (m.find()) {
			found = true;
			if (cursor[0] >= 3) {
				continue;
			}
			double v = axes[cursor[0]++];
			String rep = intMode ? String.valueOf((long) Math.floor(v)) : withDecimals(v, m.group());
			m.appendReplacement(sb, Matcher.quoteReplacement(rep));
		}
		m.appendTail(sb);
		// double モードで小数つきが無く、整数だけの行（ネザー等）は整数として扱う
		if (!intMode && !found) {
			return rewriteNumberedString(s, axes, cursor, true);
		}
		return sb.toString();
	}

	/** 値全体を上書き文字で置き換える（Text なら色は保つ）。 */
	static Object replaceWhole(Object value, String text) {
		if (value instanceof String) {
			return text;
		}
		if (value instanceof Text t) {
			MutableText out = Text.literal(text);
			out.setStyle(t.getStyle());
			return out;
		}
		if (value instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			boolean done = false;
			for (Object e : list) {
				if (!done && (e instanceof String || e instanceof Text)) {
					out.add(replaceWhole(e, text));
					done = true;
				} else if (e instanceof String || e instanceof Text) {
					out.add(replaceWhole(e, ""));
				} else {
					out.add(e);
				}
			}
			return out;
		}
		return text;
	}

	static Object rewriteString(Object value, java.util.function.Function<String, String> fn) {
		if (value instanceof String s) {
			return fn.apply(s);
		}
		if (value instanceof Text t) {
			return rewriteText(t, fn);
		}
		if (value instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object e : list) {
				if (e instanceof String s) {
					out.add(fn.apply(s));
				} else if (e instanceof Text t) {
					out.add(rewriteText(t, fn));
				} else {
					out.add(e);
				}
			}
			return out;
		}
		return value;
	}

	/** Text の中身だけ書き換えて、色・兄弟は保つ。 */
	static Text rewriteText(Text t, java.util.function.Function<String, String> fn) {
		String flat = flatten(t);
		String rewritten = fn.apply(flat);
		if (rewritten.equals(flat) && t.getSiblings().isEmpty()) {
			return t;
		}
		// 兄弟ごとに書き換え（色を保つ）。兄弟がなければ自分だけ。
		if (t.getSiblings().isEmpty()) {
			MutableText out = Text.literal(rewritten);
			out.setStyle(t.getStyle());
			return out;
		}
		MutableText out = Text.literal(fn.apply(t.getString()));
		out.setStyle(t.getStyle());
		for (Text sib : t.getSiblings()) {
			out.append(rewriteText(sib, fn));
		}
		return out;
	}

	private static String flatten(Text t) {
		StringBuilder sb = new StringBuilder(t.getString());
		for (Text sib : t.getSiblings()) {
			sb.append(flatten(sib));
		}
		return sb.toString();
	}

	private static String valueString(Object value) {
		if (value instanceof String s) {
			return s;
		}
		if (value instanceof Text t) {
			return flatten(t);
		}
		if (value instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object e : list) {
				String s = valueString(e);
				if (s != null) {
					if (sb.length() > 0) {
						sb.append(' ');
					}
					sb.append(s);
				}
			}
			return sb.toString();
		}
		return value == null ? null : value.toString();
	}

	// ================= 汎用ルール =================

	public static String applyGenericRules(F3Config cfg, String line) {
		if (cfg.compiled == null || cfg.compiled.isEmpty() || line == null) {
			return line;
		}
		String out = line;
		for (F3Config.CompiledRule rule : cfg.compiled) {
			try {
				out = rule.pattern().matcher(out).replaceAll(rule.replacement());
			} catch (Exception ignored) {
			}
		}
		return out;
	}
}
