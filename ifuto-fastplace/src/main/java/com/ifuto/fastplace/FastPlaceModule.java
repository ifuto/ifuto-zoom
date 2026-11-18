package com.ifuto.fastplace;

import com.ifuto.fastplace.mixin.MinecraftClientAccessor;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.GameMode;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 毎 tick 呼ばれる本体。右クリック長押しでブロックを連続設置する速度を
 * ClickTimingModel の統計で制御する。
 *
 * <p><b>設置は「フレーム単位」で撃つ</b>: 20Hz の tick で間隔を測ると、実際に置かれる
 * 時刻は必ず 50ms の倍数になり、サーバーから見た間隔が「50, 50, 50, 100, 50…」と
 * 同じ数字の繰り返しになる（高速設置の検知で一番見られる形）。ここでは描画フレーム
 * ごとに時計を見て、間隔を ms 単位のまま撃つ。撃つときは
 * {@code interactionManager.interactBlock} + {@code swingHand} という
 * <b>バニラと同じ入口</b>を通すので、CPS 表示 Mod やキー表示 Mod、手の振り、
 * 設置音までバニラの操作と区別が付かない。</p>
 *
 * <p>そのぶんバニラ自身の自動発火（{@code doItemUse}）は止めておく必要がある。
 * tick 中は {@code itemUseCooldown} を大きめに保ち、撃つ直前にだけ 0 に戻す。</p>
 *
 * <p>勝手に乗っかる場所（食べる・飲む・弓を引く等）には触らない。
 * 手にブロックを持っているときだけ動く。Scaffold（God Bridge）が動いている間は休む。</p>
 */
public final class FastPlaceModule {
	/** HUD に「いま投げてる」印を出す時間（ms）。これを過ぎたら表示しない */
	private static final long HUD_LINGER_MS = 1500;

	/**
	 * バニラの自動発火を止めておくための cooldown（tick）。
	 * 毎 tick 1 ずつ減るだけなので、十分大きければ戻ってこない。
	 */
	private static final int SUPPRESS_COOLDOWN = 100;

	/** 押し始めてから最初の 1 個までの反応時間 [ms]（人間の単純反応 + 運動開始） */
	private static final double REACTION_MIN_MS = 55.0;
	private static final double REACTION_MAX_MS = 145.0;

	/** 手が届く距離 [blocks]。これより遠い的には置かない（置けばサーバーに弾かれる） */
	private static final double REACH = 4.5;

	private static final ClickTimingModel MODEL = new ClickTimingModel();

	/** 撃つ予定時刻（ms）。tick ではなくフレームで判定するので連続値 */
	private static double dueAtMs = -1.0;
	private static long lastPlacementAt = -1;
	private static boolean driving;

	private FastPlaceModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().enabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig.get().enabled = on;
		FastPlaceConfig.get().save();
		if (!on) {
			reset();
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	public static void reset() {
		MODEL.resetStats();
		dueAtMs = -1.0;
		lastPlacementAt = -1;
		driving = false;
	}

	/** いま撃っている最中か（バニラの発火を止める必要があるか） */
	public static boolean driving() {
		return driving;
	}

	/**
	 * 毎 tick。時計（疲労の回復・ゆらぎの強さ）を進め、撃っている間はバニラの発火を止め続ける。
	 * 実際に置くのは {@link #frameStep}。
	 */
	public static void onTick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		MODEL.tick(now);

		if (!canDrive(client)) {
			stopDriving(client);
			return;
		}

		if (driving) {
			suppressVanilla(client);
		}
	}

	/**
	 * 描画フレームごと。予定時刻を過ぎていれば 1 個置く。
	 *
	 * @param dtMs 前フレームからの経過（使わないが、将来の速度補正用に受け取る）
	 */
	public static void frameStep(MinecraftClient client, double dtMs) {
		if (!canDrive(client)) {
			stopDriving(client);
			return;
		}

		long now = System.currentTimeMillis();

		if (!driving) {
			// 押し始め。即置きはせず、人間の反応時間だけ待つ
			driving = true;
			dueAtMs = now + REACTION_MIN_MS
				+ ThreadLocalRandom.current().nextDouble() * (REACTION_MAX_MS - REACTION_MIN_MS);
			suppressVanilla(client);
			return;
		}

		// フレームの合間の tick でバニラが置かないように、常に抑えておく
		suppressVanilla(client);

		if (now < dueAtMs) {
			return;
		}

		BlockHitResult hit = placeTarget(client);

		if (hit == null) {
			// 的が無いフレーム（視線が外れた等）。次フレームでやり直す
			return;
		}

		place(client, hit);

		double base = FastPlaceConfig.get().intervalMs;
		double delay = MODEL.fireClick(now, base);
		dueAtMs = now + Math.max(ClickTimingModel.MIN_INTERVAL_MS, delay);
		lastPlacementAt = now;
	}

	/** 置ける状態か（押している・ブロック持ち・画面なし・Scaffold 休止中 など） */
	private static boolean canDrive(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		ClientPlayerInteractionManager interaction = client.interactionManager;

		return player != null && world != null && interaction != null
			&& enabled()
			&& client.currentScreen == null
			&& player.isAlive()
			&& !ScaffoldModule.isEngaged()
			&& client.options.useKey.isPressed()
			&& interaction.getCurrentGameMode() != GameMode.SPECTATOR
			&& player.getMainHandStack().getItem() instanceof BlockItem;
	}

	/** いま狙っているブロック（届く範囲のものだけ） */
	private static BlockHitResult placeTarget(MinecraftClient client) {
		HitResult target = client.crosshairTarget;

		if (!(target instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
			return null;
		}

		ClientPlayerEntity player = client.player;

		if (player == null) {
			return null;
		}

		if (player.getEyePos().squaredDistanceTo(blockHit.getPos()) > REACH * REACH) {
			return null;
		}

		return blockHit;
	}

	/** バニラと同じ入口で 1 個置く */
	private static void place(MinecraftClient client, BlockHitResult hit) {
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interaction = client.interactionManager;

		if (player == null || interaction == null) {
			return;
		}

		// 直前だけ cooldown を外す（interactBlock 自体は cooldown を見ないが、
		// 次の tick のバニラ発火がここで戻らないよう、撃ったらすぐ戻す）
		((MinecraftClientAccessor) client).ifutoFastplace$setItemUseCooldown(0);
		interaction.interactBlock(player, Hand.MAIN_HAND, hit);
		player.swingHand(Hand.MAIN_HAND);
		suppressVanilla(client);
	}

	/** tick 中のバニラの自動発火を止める */
	private static void suppressVanilla(MinecraftClient client) {
		((MinecraftClientAccessor) client).ifutoFastplace$setItemUseCooldown(SUPPRESS_COOLDOWN);
	}

	/** 撃つのをやめる（cooldown は元に戻す。戻さないと普通に置けなくなる） */
	private static void stopDriving(MinecraftClient client) {
		dueAtMs = -1.0;

		if (driving) {
			driving = false;
			((MinecraftClientAccessor) client).ifutoFastplace$setItemUseCooldown(0);
		}
	}

	/** HUD を出すべきか（いま投げている or 投げ終わり後しばらく） */
	public static boolean hudVisible(long now) {
		return FastPlaceConfig.get().hudCps
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
