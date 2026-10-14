package com.ifuto.f3.test;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.SpoofEngine;
import net.minecraft.text.Text;

import java.util.Arrays;
import java.util.List;

/** F3 詐称ロジックの自己テスト。gradle testF3 / CI で毎回。 */
public final class F3SelfTest {
	private static int passed = 0;

	public static void main(String[] args) {
		testFpsModes();
		testCoordsModes();
		testVanillaRewrite();
		testBetterF3Rewrite();
		testGenericRules();
		testIdempotent();
		System.out.println("F3SelfTest: ALL " + passed + " TESTS PASSED");
	}

	private static SpoofEngine.Ctx ctx(double x, double y, double z, int fps, long now) {
		return new SpoofEngine.Ctx(x, y, z, fps, "minecraft:overworld", now);
	}

	private static F3Config freshCfg() {
		F3Config cfg = new F3Config();
		cfg.rules = new java.util.ArrayList<>();
		for (int i = 0; i < 4; i++) {
			cfg.rules.add(new F3Config.Rule());
		}
		return cfg;
	}

	private static void check(boolean cond, String name) {
		if (!cond) {
			throw new AssertionError("FAILED: " + name);
		}
		passed++;
	}

	// ---------- FPS ----------

	private static void testFpsModes() {
		SpoofEngine.resetForTest();
		F3Config cfg = freshCfg();

		cfg.fpsMode = "FIXED";
		cfg.fpsFixed = 240;
		check(SpoofEngine.fps(cfg, ctx(0, 0, 0, 60, 1000)) == 240, "fps fixed");

		SpoofEngine.resetForTest();
		cfg.fpsMode = "FIXED";
		check(SpoofEngine.fps(cfg, ctx(0, 0, 0, 60, 1000)) == 240, "fps fixed stable");

		SpoofEngine.resetForTest();
		cfg.fpsMode = "FOLLOW";
		cfg.fpsScale = 2.0;
		cfg.fpsOffset = 10;
		cfg.fpsLo = 0;
		cfg.fpsHi = 10000;
		cfg.fpsStep = 1;
		check(SpoofEngine.fps(cfg, ctx(0, 0, 0, 100, 2000)) == 210, "fps follow math");

		SpoofEngine.resetForTest();
		cfg.fpsLo = 120;
		cfg.fpsHi = 240;
		check(SpoofEngine.fps(cfg, ctx(0, 0, 0, 1000, 3000)) == 240, "fps follow clamp hi");

		SpoofEngine.resetForTest();
		cfg.fpsMode = "RANGE";
		cfg.fpsLo = 120;
		cfg.fpsHi = 240;
		cfg.fpsStep = 5;
		for (int i = 0; i < 50; i++) {
			int v = SpoofEngine.fps(cfg, ctx(0, 0, 0, 60, 4000 + i * 600L));
			check(v >= 120 && v <= 240 && v % 5 == 0, "fps range walk " + i);
		}

		SpoofEngine.resetForTest();
		cfg.fpsMode = "RANGE";
		int a = SpoofEngine.fps(cfg, ctx(0, 0, 0, 60, 99999));
		int b = SpoofEngine.fps(cfg, ctx(0, 0, 0, 60, 99999));
		check(a == b, "fps frame stable");
	}

	// ---------- 座標 ----------

	private static void testCoordsModes() {
		F3Config cfg = freshCfg();
		SpoofEngine.Ctx c = ctx(10, 64, -5, 60, 0);

		cfg.coordsMode = "OFF";
		double[] p = SpoofEngine.spoofPos(cfg, c);
		check(p[0] == 10 && p[1] == 64 && p[2] == -5, "coords off");

		cfg.coordsMode = "OFFSET";
		cfg.offX = 1;
		cfg.offY = 2;
		cfg.offZ = 3;
		p = SpoofEngine.spoofPos(cfg, c);
		check(p[0] == 11 && p[1] == 66 && p[2] == -2, "coords offset");

		cfg.coordsMode = "ANCHOR";
		cfg.anchorSet = false;
		p = SpoofEngine.spoofPos(cfg, c);
		check(p[0] == 10 && p[1] == 64 && p[2] == -5, "coords anchor unset passthrough");

		cfg.anchorSet = true;
		cfg.anchorRealX = 10;
		cfg.anchorRealY = 64;
		cfg.anchorRealZ = -5;
		cfg.anchorFakeX = 0;
		cfg.anchorFakeY = -59.5;
		cfg.anchorFakeZ = 0;
		p = SpoofEngine.spoofPos(cfg, ctx(11, 65, -3, 60, 0));
		check(Math.abs(p[0] - 1) < 1e-9 && Math.abs(p[1] + 58.5) < 1e-9 && Math.abs(p[2] - 2) < 1e-9, "coords anchor delta");

		cfg.coordsMode = "FIXED";
		cfg.fixX = 100;
		cfg.fixY = -60;
		cfg.fixZ = 200;
		p = SpoofEngine.spoofPos(cfg, c);
		check(p[0] == 100 && p[1] == -60 && p[2] == 200, "coords fixed");

		cfg.coordsMode = "OFFSET";
		cfg.offX = 0;
		cfg.offY = 0;
		cfg.offZ = 0;
		int[] b = SpoofEngine.spoofBlock(cfg, ctx(10.7, 64.2, -5.9, 60, 0));
		check(b[0] == 10 && b[1] == 64 && b[2] == -6, "block floor");

		int[] rel = SpoofEngine.spoofChunkRel(cfg, ctx(18.0, 65.0, -3.0, 60, 0));
		check(rel[0] == 2 && rel[2] == 13, "chunk rel");
	}

	// ---------- バニラ行 ----------

	private static void testVanillaRewrite() {
		SpoofEngine.resetForTest();
		F3Config cfg = freshCfg();
		cfg.fpsMode = "FIXED";
		cfg.fpsFixed = 237;
		cfg.coordsMode = "OFFSET";
		cfg.offX = 1;
		cfg.offY = 2;
		cfg.offZ = 3;
		SpoofEngine.Ctx c = ctx(10.5, 64.0, -3.25, 60, 5000);

		String fpsLine = SpoofEngine.rewriteVanilla(cfg, "fps", "120 fps T: 8 12 30", c);
		check(fpsLine.startsWith("237 fps"), "vanilla fps line: " + fpsLine);

		String xyz = SpoofEngine.rewriteVanilla(cfg, "pos", "XYZ: 10.500 / 64.000 / -3.250", c);
		check(xyz.equals("XYZ: 11.500 / 66.000 / -0.250"), "vanilla xyz decimals: " + xyz);

		String block = SpoofEngine.rewriteVanilla(cfg, "pos", "Block: 10 64 -4", c);
		check(block.equals("Block: 11 66 -1"), "vanilla block: " + block);

		cfg.facingOverride = "south";
		String facing = SpoofEngine.rewriteVanilla(cfg, "pos", "Facing: north (Towards positive Z)", c);
		check(facing.startsWith("Facing: south"), "vanilla facing: " + facing);

		cfg.biomeOverride = "minecraft:the_void";
		String biome = SpoofEngine.rewriteVanilla(cfg, "pos", "Biome: minecraft:plains", c);
		check(biome.equals("Biome: minecraft:the_void"), "vanilla biome: " + biome);

		cfg.memUsedMB = 2048;
		cfg.memTotalMB = 8192;
		String mem = SpoofEngine.rewriteVanilla(cfg, "mem", "Mem: 30% 1228/4096MB", c);
		check(mem.equals("Mem: 25% 2048/8192MB"), "vanilla mem: " + mem);

		cfg.masterEnabled = false;
		String off = SpoofEngine.rewriteVanilla(cfg, "pos", "XYZ: 10.500 / 64.000 / -3.250", c);
		check(off.equals("XYZ: 10.500 / 64.000 / -3.250"), "master off passthrough");
		cfg.masterEnabled = true;
	}

	// ---------- BetterF3 ----------

	private static void testBetterF3Rewrite() {
		F3Config cfg = freshCfg();
		cfg.coordsMode = "OFFSET";
		cfg.offX = 1;
		cfg.offY = 2;
		cfg.offZ = 3;
		SpoofEngine.Ctx c = ctx(10.5, 64.0, -3.25, 60, 6000);

		Object fps = SpoofEngine.rewriteBetterF3(cfg, "fps", "120 / max 240", c);
		check("120 / max 240".equals(fps), "better fps passthrough");

		List<Object> coords = Arrays.asList(Text.literal("10.500"), Text.literal("64.000"), Text.literal("-3.250"));
		Object out = SpoofEngine.rewriteBetterF3(cfg, "player_coords", coords, c);
		check(out instanceof List, "better coords is list");
		List<?> list = (List<?>) out;
		check(((Text) list.get(0)).getString().equals("11.500"), "better coords x: " + list.get(0));
		check(((Text) list.get(1)).getString().equals("66.000"), "better coords y");
		check(((Text) list.get(2)).getString().equals("-0.250"), "better coords z");

		Object block = SpoofEngine.rewriteBetterF3(cfg, "block_coords", "10 64 -4", c);
		check("11 66 -1".equals(block), "better block string: " + block);

		cfg.biomeOverride = "minecraft:deep_dark";
		Object biome = SpoofEngine.rewriteBetterF3(cfg, "biome", "minecraft:plains", c);
		check("minecraft:deep_dark".equals(biome), "better biome override");

		cfg.memUsedMB = 1000;
		cfg.memTotalMB = 4000;
		Object mem = SpoofEngine.rewriteBetterF3(cfg, "memory_usage", "30% 1228/4096MB", c);
		check("25% 1000/4000MB".equals(mem), "better mem: " + mem);

		cfg.coordsMode = "FIXED";
		Object vel = SpoofEngine.rewriteBetterF3(cfg, "velocity_x", Text.literal("1.234"), c);
		check(vel instanceof Text && ((Text) vel).getString().equals("0.000"), "better velocity fixed zero");
	}

	// ---------- 汎用ルール ----------

	private static void testGenericRules() {
		F3Config cfg = freshCfg();
		cfg.coordsMode = "OFF";
		cfg.rules.get(0).enabled = true;
		cfg.rules.get(0).pattern = "foo";
		cfg.rules.get(0).replacement = "bar";
		cfg.compiled = new java.util.ArrayList<>();
		try {
			java.lang.reflect.Method m = F3Config.class.getDeclaredMethod("rebuildRules");
			m.setAccessible(true);
			m.invoke(cfg);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		String out = SpoofEngine.rewriteVanilla(cfg, "x", "foo foo", ctx(0, 0, 0, 60, 0));
		check(out.equals("bar bar"), "generic rule: " + out);

		Object better = SpoofEngine.rewriteBetterF3(cfg, "some_unknown_line", "has foo inside", ctx(0, 0, 0, 60, 0));
		check("has bar inside".equals(better), "generic rule betterf3");
	}

	// ---------- 二重適用 ----------

	private static void testIdempotent() {
		SpoofEngine.resetForTest();
		F3Config cfg = freshCfg();
		cfg.fpsMode = "FIXED";
		cfg.fpsFixed = 200;
		cfg.coordsMode = "OFFSET";
		cfg.offX = 5;
		cfg.offY = 5;
		cfg.offZ = 5;
		SpoofEngine.Ctx c = ctx(1.5, 2.5, 3.5, 60, 7000);
		String once = SpoofEngine.rewriteVanilla(cfg, "pos", "XYZ: 1.500 / 2.500 / 3.500", c);
		String twice = SpoofEngine.rewriteVanilla(cfg, "pos", once, c);
		check(once.equals(twice), "idempotent coords: " + once + " vs " + twice);

		String f1 = SpoofEngine.rewriteVanilla(cfg, "fps", "60 fps", c);
		String f2 = SpoofEngine.rewriteVanilla(cfg, "fps", f1, c);
		check(f1.equals(f2), "idempotent fps");
	}
}
