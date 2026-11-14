package com.ifuto.fastplace;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.world.GameMode;

/**
 * 毎 tick 呼ばれる本体。右クリック長押しでブロックを連続設置する速度を
 * ClickTimingModel の統計で制御する。
 *
 * <p>勝手に乗っかる場所（食べる・飲む・弓を引く等）には触らない。
 * 手にブロックを持っているときだけクールダウンを潰す。</p>
 */
public final class FastPlaceModule {
	/** HUD に「いま投げてる」印を出す時間（ms）。これを過ぎたら表示しない */
	private static final long HUD_LINGER_MS = 1500;

	private static final ClickTimingModel MODEL = new ClickTimingModel();
	private static long nextClickDueAt = -1;
	private static long lastPlacementAt = -1;

	private FastPlaceModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().enabled;
	}

	public static void reset() {
		MODEL.resetStats();
		nextClickDueAt = -1;
		lastPlacementAt = -1;
	}

	public static void onTick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		MODEL.tick(now);

		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		ClientPlayerInteractionManager interaction = client.interactionManager;

		if (player == null || world == null || interaction == null || !enabled()
				|| client.currentScreen != null
				|| !client.options.useKey.isPressed()
				|| interaction.getCurrentGameMode() == GameMode.SPECTATOR
				|| !(player.getMainHandStack().getItem() instanceof BlockItem)) {
			nextClickDueAt = -1;
			return;
		}

		if (nextClickDueAt < 0) {
			// 右クリックを押し始めた瞬間。バニラの即時発火には乗らず、次の周期をモデルで決める
			nextClickDueAt = now;
		}

		if (now >= nextClickDueAt) {
			// バニラがこの/次の tick で置けるようにクールダウンだけ潰す（ロジックはバニラ任せ）
			((MinecraftClientAccessor) client).ifutoFastplace$setItemUseCooldown(0);
			double base = FastPlaceConfig.get().intervalMs;
			long delay = MODEL.fireClick(now, base);
			nextClickDueAt = now + Math.max((long) ClickTimingModel.MIN_INTERVAL_MS, delay);
			lastPlacementAt = now;
		}
	}

	/** HUD を出すべきか（いま投げている or 投げ終わり後しばらく） */
	public static boolean hudVisible(long now) {
		return enabled() && FastPlaceConfig.get().hudCps
			&& lastPlacementAt >= 0 && now - lastPlacementAt < HUD_LINGER_MS;
	}

	/** 表示用 CPS（整数にならない・毎 tick 変動） */
	public static double displayCps(long now) {
		return MODEL.displayCps(now);
	}

	public static ClickTimingModel model() {
		return MODEL;
	}

	public static long lastPlacementAt() {
		return lastPlacementAt;
	}
}
