package com.ifuto.fastplace.anchor;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.human.SmoothLook;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Auto Safe Anchor。
 *
 * <p>自分がリスポーンアンカーを置いた瞬間、<b>手前（自分側）に 1 ブロック置ける
 * 空間がある</b>ときだけ、安全なアンカー運用を自動で行う:</p>
 * <ol>
 *   <li>グロウストーンを持って、アンカーを <b>1 回だけ</b>チャージ</li>
 *   <li>アンカーの手前の地面（<b>アンカーではなく地面</b>）へ視点を移し、
 *       グロウストーンを 1 ブロック置いて爆風の壁を作る</li>
 *   <li>アンカーを持って、壁ごしに見えるアンカーの面へ向き直って右クリック（起爆）</li>
 * </ol>
 *
 * <p>「アンカーを向いたままグロウストーンを置く」と、置く代わりに
 * <b>2 回目のチャージ</b>になってしまう。だから 2 は必ず地面を向く。
 * そのために各段階で <b>いま本当にどこを見ているか</b>を毎フレームレイキャストで
 * 確かめ、狙いが合ったときだけ右クリックする。パケットは組み立てない。</p>
 *
 * <p>途中でアンカーが壊れた / 置けなかった / 見えなくなった ときは、
 * <b>起爆せずに</b>やめる（勝手に爆発させない）。</p>
 */
public final class AutoSafeAnchorModule {
	/** 操作できる距離 [blocks] */
	private static final double REACH = 4.4;
	/** 1 段階の狙い付けに許す時間 [ms] */
	private static final long AIM_TIMEOUT_MS = 1400L;
	/** 起爆の狙い付けに許す時間 [ms] */
	private static final long DETONATE_TIMEOUT_MS = 2200L;
	/** 起爆点の候補を切り替える間隔 [ms] */
	private static final long CANDIDATE_SWITCH_MS = 420L;
	/** チャージが反映されるのを待つ時間 [ms] */
	private static final long CHARGE_SETTLE_MS = 450L;

	private enum Stage {
		IDLE,
		SWITCH_GLOWSTONE,
		AIM_ANCHOR,
		WAIT_CHARGE,
		AIM_GROUND,
		WAIT_PLACE,
		SWITCH_ANCHOR,
		AIM_DETONATE
	}

	private static Stage stage = Stage.IDLE;
	private static BlockPos anchor;
	private static BlockPos front;
	private static int glowSlot = -1;
	private static int anchorSlot = -1;
	private static int previousSlot = -1;
	private static int candidate;
	private static long stageAt;
	private static long stageSince;
	private static long candidateSince;

	private AutoSafeAnchorModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().safeAnchorEnabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		cfg.safeAnchorEnabled = on;
		cfg.save();
		if (!on) {
			reset(MinecraftClient.getInstance());
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	public static boolean busy() {
		return stage != Stage.IDLE;
	}

	// ------------------------------------------------------------------
	// 設置の検出（mixin から、interactBlock のあとに呼ばれる）
	// ------------------------------------------------------------------

	/**
	 * ブロックを右クリックした直後に呼ぶ。置かれたのがリスポーンアンカーなら開始する。
	 */
	public static void onBlockUse(MinecraftClient client, ClientPlayerEntity player,
			BlockHitResult hit, ActionResult result) {
		if (!enabled() || stage != Stage.IDLE || client.player != player || client.world == null
			|| result == null || !result.isAccepted()) {
			return;
		}
		World world = client.world;
		// 「置かれた」のは叩いたブロックの隣。叩いたブロック自体がアンカーなら
		// それは使用（チャージ/起爆）であって設置ではないので触らない
		BlockPos placed = hit.getBlockPos().offset(hit.getSide());
		if (!world.getBlockState(placed).isOf(Blocks.RESPAWN_ANCHOR)) {
			return;
		}
		start(client, player, placed);
	}

	private static void start(MinecraftClient client, ClientPlayerEntity player, BlockPos placed) {
		World world = client.world;
		if (world == null) {
			return;
		}
		Direction towardsPlayer = horizontalDirectionFrom(Vec3d.ofCenter(placed), player.getPos());
		BlockPos frontPos = placed.offset(towardsPlayer);
		// 手前に 1 ブロック置ける空間があるか
		if (!world.getBlockState(frontPos).isReplaceable()) {
			return;
		}
		BlockPos support = frontPos.down();
		BlockState supportState = world.getBlockState(support);
		if (!supportState.isSolidBlock(world, support)) {
			return;   // 地面が無いと「地面を向いて置く」ができない
		}
		int glow = findSlot(player, Items.GLOWSTONE);
		int anchorItem = findSlot(player, Items.RESPAWN_ANCHOR);
		if (glow < 0 || anchorItem < 0) {
			return;
		}
		anchor = placed;
		front = frontPos;
		glowSlot = glow;
		anchorSlot = anchorItem;
		previousSlot = player.getInventory().getSelectedSlot();
		candidate = 0;
		stage = Stage.SWITCH_GLOWSTONE;
		stageAt = System.currentTimeMillis();
		stageSince = stageAt;
		candidateSince = stageAt;
	}

	// ------------------------------------------------------------------
	// 毎 tick（無効化・画面を開いた の後始末）
	// ------------------------------------------------------------------

	public static void tick(MinecraftClient client) {
		if (stage == Stage.IDLE) {
			return;
		}
		ClientPlayerEntity player = client.player;
		if (!enabled() || player == null || client.world == null || client.currentScreen != null
			|| !player.isAlive() || player.isSpectator()) {
			reset(client);
			return;
		}
		if (!client.world.getBlockState(anchor).isOf(Blocks.RESPAWN_ANCHOR)) {
			reset(client);
		}
	}

	// ------------------------------------------------------------------
	// 毎フレーム
	// ------------------------------------------------------------------

	public static void frameStep(MinecraftClient client, double dtMs) {
		if (stage == Stage.IDLE) {
			return;
		}
		FastPlaceConfig cfg = FastPlaceConfig.get();
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (!enabled() || player == null || client.world == null || interaction == null
			|| client.currentScreen != null || !player.isAlive() || player.isSpectator()) {
			reset(client);
			return;
		}
		long now = System.currentTimeMillis();
		if (now < stageAt) {
			return;
		}

		switch (stage) {
			case SWITCH_GLOWSTONE -> {
				select(player, glowSlot);
				advance(Stage.AIM_ANCHOR, now, cfg.safeAnchorStepDelayMs);
			}
			case AIM_ANCHOR -> aimAtAnchor(client, player, interaction, cfg, dtMs, now);
			case WAIT_CHARGE -> {
				BlockState state = client.world.getBlockState(anchor);
				if (!state.isOf(Blocks.RESPAWN_ANCHOR)) {
					reset(client);
					return;
				}
				int charges = charges(state);
				if (charges > 0) {
					advance(Stage.AIM_GROUND, now, cfg.safeAnchorStepDelayMs);
				} else if (charges == 0) {
					// まだ届いていないだけかもしれないので、少しだけ待つ。
					// 2 回目は絶対に押さない（2 チャージになってしまう）
					if (now - stageSince > CHARGE_SETTLE_MS) {
						reset(client);   // チャージできなかった -> 壁も起爆もせずにやめる
					}
				} else {
					// チャージ数が読めない環境。右クリックは 1 回だけ済ませてあるので進む
					advance(Stage.AIM_GROUND, now, cfg.safeAnchorStepDelayMs);
				}
			}
			case AIM_GROUND -> aimAtGround(client, player, interaction, cfg, dtMs, now);
			case WAIT_PLACE -> {
				if (!client.world.getBlockState(front).isAir()) {
					advance(Stage.SWITCH_ANCHOR, now, cfg.safeAnchorStepDelayMs);
				} else if (now - stageAt > 500L) {
					reset(client);   // 置けなかった -> 起爆しない
				}
			}
			case SWITCH_ANCHOR -> {
				select(player, anchorSlot);
				candidate = 0;
				candidateSince = now;
				advance(Stage.AIM_DETONATE, now, cfg.safeAnchorStepDelayMs);
			}
			case AIM_DETONATE -> aimAndDetonate(client, player, interaction, cfg, dtMs, now);
			default -> reset(client);
		}
	}

	// ------------------------------------------------------------------
	// 1) アンカーを 1 回チャージする
	// ------------------------------------------------------------------

	private static void aimAtAnchor(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager interaction, FastPlaceConfig cfg, double dtMs, long now) {
		if (!anchorAlive(client, player)) {
			reset(client);
			return;
		}
		select(player, glowSlot);
		Vec3d point = anchorSidePoint(client, player);
		SmoothLook.stepFrame(player, point, cfg.safeAnchorAimSpeed, dtMs, 0.8);
		BlockHitResult hit = lookingAt(client.world, player, REACH);
		if (hit != null && hit.getBlockPos().equals(anchor)) {
			interaction.interactBlock(player, Hand.MAIN_HAND, hit);
			player.swingHand(Hand.MAIN_HAND);
			advance(Stage.WAIT_CHARGE, now, cfg.safeAnchorStepDelayMs);
			return;
		}
		if (now - stageSince > AIM_TIMEOUT_MS) {
			reset(client);   // 向けなかった -> 何もしない
		}
	}

	// ------------------------------------------------------------------
	// 2) 手前の「地面」を向いてグロウストーンを置く
	// ------------------------------------------------------------------

	private static void aimAtGround(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager interaction, FastPlaceConfig cfg, double dtMs, long now) {
		if (!anchorAlive(client, player)) {
			reset(client);
			return;
		}
		select(player, glowSlot);
		Vec3d supportTop = Vec3d.ofCenter(front.down()).add(0.0, 0.5, 0.0);
		SmoothLook.stepFrame(player, supportTop, cfg.safeAnchorAimSpeed, dtMs, 0.8);
		BlockHitResult hit = lookingAt(client.world, player, REACH);
		if (hit != null && hit.getBlockPos().equals(front.down())
			&& hit.getSide() == Direction.UP) {
			// アンカー自身を叩くと 2 回目のチャージになる。地面だけを叩く
			interaction.interactBlock(player, Hand.MAIN_HAND, hit);
			player.swingHand(Hand.MAIN_HAND);
			advance(Stage.WAIT_PLACE, now, cfg.safeAnchorStepDelayMs);
			return;
		}
		if (now - stageSince > AIM_TIMEOUT_MS) {
			reset(client);
		}
	}

	// ------------------------------------------------------------------
	// 3) アンカーを持って、見えている面を叩いて起爆する
	// ------------------------------------------------------------------

	private static void aimAndDetonate(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager interaction, FastPlaceConfig cfg, double dtMs, long now) {
		if (!anchorAlive(client, player)) {
			reset(client);
			return;
		}
		select(player, anchorSlot);
		if (now - candidateSince > CANDIDATE_SWITCH_MS) {
			candidate++;
			candidateSince = now;
		}
		Vec3d point = detonatePoint(client, player, candidate);
		SmoothLook.stepFrame(player, point, cfg.safeAnchorAimSpeed, dtMs, 0.8);
		BlockHitResult hit = lookingAt(client.world, player, REACH);
		if (hit != null && hit.getBlockPos().equals(anchor)) {
			interaction.interactBlock(player, Hand.MAIN_HAND, hit);
			player.swingHand(Hand.MAIN_HAND);
			reset(client);
			return;
		}
		if (now - stageSince > DETONATE_TIMEOUT_MS) {
			reset(client);   // 見えないなら起爆しない（安全側）
		}
	}

	// ------------------------------------------------------------------
	// 位置の計算
	// ------------------------------------------------------------------

	/** アンカーの、自分側の面の中心（少し上） */
	private static Vec3d anchorSidePoint(MinecraftClient client, ClientPlayerEntity player) {
		Vec3d center = Vec3d.ofCenter(anchor);
		Vec3d delta = player.getPos().subtract(center);
		Vec3d horizontal = new Vec3d(delta.x, 0.0, delta.z);
		if (horizontal.lengthSquared() < 1.0e-6) {
			return center.add(0.0, 0.3, 0.0);
		}
		Vec3d normal = horizontal.normalize().multiply(0.5);
		return center.add(normal.x, 0.12, normal.z);
	}

	/** 起爆用の狙い点（壁の向こうでも見える面を候補として順に試す） */
	private static Vec3d detonatePoint(MinecraftClient client, ClientPlayerEntity player, int index) {
		Vec3d center = Vec3d.ofCenter(anchor);
		Vec3d eye = player.getEyePos();
		// ブロック上面の、自分の側に寄った点
		double tx = MathHelper.clamp(eye.x, anchor.getX() + 0.15, anchor.getX() + 0.85);
		double tz = MathHelper.clamp(eye.z, anchor.getZ() + 0.15, anchor.getZ() + 0.85);
		Vec3d top = new Vec3d(tx, anchor.getY() + 0.999, tz);
		Vec3d upper = center.add(0.0, 0.35, 0.0);
		Vec3d side = anchorSidePoint(client, player);
		return switch (Math.floorMod(index, 3)) {
			case 0 -> top;
			case 1 -> upper.withY(anchor.getY() + 0.75);
			default -> side;
		};
	}

	/** アンカー → 自分の水平方向（東西南北のどれか） */
	private static Direction horizontalDirectionFrom(Vec3d from, Vec3d to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx >= 0.0 ? Direction.EAST : Direction.WEST;
		}
		return dz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** いま見ているブロック（バニラのクロスヘアと同じ計算） */
	private static BlockHitResult lookingAt(World world, ClientPlayerEntity player, double reach) {
		if (world == null) {
			return null;
		}
		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(reach));
		BlockHitResult hit = world.raycast(new RaycastContext(eye, end,
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	private static boolean anchorAlive(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null || anchor == null) {
			return false;
		}
		if (!client.world.getBlockState(anchor).isOf(Blocks.RESPAWN_ANCHOR)) {
			return false;
		}
		return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(anchor)) <= REACH * REACH;
	}

	/** アンカーのチャージ数。読めない環境では -1 */
	private static int charges(BlockState state) {
		for (Property<?> property : state.getProperties()) {
			if (property instanceof IntProperty intProperty && property.getName().equals("charges")) {
				return state.get(intProperty);
			}
		}
		return -1;
	}

	private static void select(ClientPlayerEntity player, int slot) {
		if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
			player.getInventory().setSelectedSlot(slot);
		}
	}

	private static int findSlot(ClientPlayerEntity player, net.minecraft.item.Item item) {
		var inventory = player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack.isOf(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static void advance(Stage next, long now, long delayMs) {
		stage = next;
		stageAt = now + Math.max(0L, delayMs);
		stageSince = now;
	}

	private static void reset(MinecraftClient client) {
		if (stage != Stage.IDLE && FastPlaceConfig.get().safeAnchorRestoreSlot
			&& previousSlot >= 0 && client.player != null) {
			client.player.getInventory().setSelectedSlot(previousSlot);
		}
		stage = Stage.IDLE;
		anchor = null;
		front = null;
		glowSlot = -1;
		anchorSlot = -1;
		previousSlot = -1;
		candidate = 0;
		stageAt = 0L;
		stageSince = 0L;
		candidateSince = 0L;
	}
}
