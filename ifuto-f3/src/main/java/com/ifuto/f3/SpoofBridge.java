package com.ifuto.f3;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * MC から本物の値を取ってくる役。全部失敗しても null・0 を返して落とさない。
 */
public final class SpoofBridge {
	private SpoofBridge() {
	}

	/** getCurrentFps の mixin が毎回入れる本物の FPS。 */
	public static volatile int realFps = 60;

	public static SpoofEngine.Ctx capture(int real) {
		double x = 0;
		double y = 0;
		double z = 0;
		String dim = "";
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player != null) {
				x = client.player.getX();
				y = client.player.getY();
				z = client.player.getZ();
			}
			if (client.world != null) {
				dim = client.world.getRegistryKey().getValue().toString();
			}
		} catch (Exception ignored) {
		}
		return new SpoofEngine.Ctx(x, y, z, real, dim, System.currentTimeMillis());
	}

	/** 詐称した座標にある本物のバイオーム（"minecraft:..."）。取れなければ null。 */
	public static String biomeAtFake(F3Config cfg, SpoofEngine.Ctx ctx) {
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.world == null) {
				return null;
			}
			double[] p = SpoofEngine.spoofPos(cfg, ctx);
			BlockPos pos = new BlockPos((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2]));
			return client.world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse(null);
		} catch (Exception ignored) {
			return null;
		}
	}

	/** BetterF3 のフックが当たったかどうか（診断画面用）。 */
	public static final class BetterF3State {
		private BetterF3State() {
		}

		public static volatile boolean hooked = false;
	}
}
