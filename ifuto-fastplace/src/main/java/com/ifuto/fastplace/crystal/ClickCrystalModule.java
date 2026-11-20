package com.ifuto.fastplace.crystal;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.human.SmoothLook;
import com.ifuto.fastplace.input.VirtualKeys;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * ClickCrystal。<b>攻撃キーを押している間だけ</b>動く。
 *
 * <p>「置く → 殴る」を、<b>パケットを組み立てずに実際の操作で</b>行う:</p>
 * <ol>
 *   <li>黒曜石の上面へ<b>実際に視点を向ける</b>（毎フレーム少しずつ。tick の 50ms 段差なし）</li>
 *   <li>クロスヘアが本当にその上面に当たっていることを {@link World#raycast} で確認してから
 *       {@code interactBlock}（= バニラの右クリックと同じ入口）で置く</li>
 *   <li>置いたクリスタルへ<b>実際に視点を向け</b>、目視できる（{@code canSee} + 遮蔽なし）かつ
 *       殴れる距離（3.0）にあるときだけ {@code attackEntity} で殴る</li>
 * </ol>
 *
 * <p>見えない位置のクリスタルは殴らない。届かないものは殴らない。置けなかったら置かない。
 * 攻撃キーを離した瞬間に止まり、必要なら元のスロットへ戻る。</p>
 */
public final class ClickCrystalModule {
	/** 置くときのリーチ [blocks]（バニラの 4.5） */
	private static final double PLACE_REACH = 4.5;
	/** 殴れる距離 [blocks]（バニラの攻撃リーチ 3.0） */
	private static final double ATTACK_REACH = 3.0;
	/** 置いたクリスタルがサーバーから来るまでの猶予 [ms] */
	private static final long SPAWN_TIMEOUT_MS = 500L;
	/** 上面に当たったとみなす角度 [deg] */
	private static final double AIM_TOLERANCE_DEG = 0.7;

	private enum Stage {
		/** 黒曜石の上面を狙っている */
		AIM_BASE,
		/** 置いたクリスタルを狙っている */
		AIM_CRYSTAL
	}

	private static Stage stage = Stage.AIM_BASE;
	private static BlockPos base;
	private static int originalSlot = -1;
	private static boolean holding;
	private static long stageSince;
	private static long nextCycleAt;

	private ClickCrystalModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().clickCrystalEnabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		cfg.clickCrystalEnabled = on;
		cfg.save();
		if (!on) {
			stop(MinecraftClient.getInstance());
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	/** いま連打中か（HUD・他モジュール用） */
	public static boolean active() {
		return holding && base != null;
	}

	/** 毎 tick。画面を開いた / OFF / 死んだ ときの後始末だけを行う */
	public static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || !enabled()
			|| client.currentScreen != null || !player.isAlive() || player.isSpectator()) {
			stop(client);
		}
	}

	/** 描画フレームごとの本体 */
	public static void frameStep(MinecraftClient client, double dtMs) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interaction = client.interactionManager;

		if (!enabled() || player == null || client.world == null || interaction == null
			|| client.currentScreen != null || !player.isAlive() || player.isSpectator()
			|| interaction.getCurrentGameMode() == GameMode.SPECTATOR) {
			stop(client);
			return;
		}

		long now = System.currentTimeMillis();
		boolean pressed = VirtualKeys.physical(client.options.attackKey);
		if (!pressed) {
			if (holding) {
				stop(client);
			}
			return;
		}
		if (!holding) {
			begin(client, player, cfg);
			if (!holding) {
				return;
			}
		}
		if (base == null || now < nextCycleAt) {
			return;
		}
		if (!baseStillValid(client, player, cfg)) {
			stop(client);
			return;
		}

		switch (stage) {
			case AIM_BASE -> aimAndPlace(client, player, interaction, cfg, dtMs, now);
			case AIM_CRYSTAL -> aimAndSwing(client, player, interaction, cfg, dtMs, now);
		}
	}

	// ------------------------------------------------------------------
	// 押し始め
	// ------------------------------------------------------------------

	private static void begin(MinecraftClient client, ClientPlayerEntity player, FastPlaceConfig cfg) {
		BlockHitResult hit = lookingAt(client.world, player, cfg.clickCrystalRange);
		if (hit == null) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		if (!isCrystalBase(client.world, pos) || !client.world.getBlockState(pos.up()).isAir()) {
			return;
		}
		base = pos;
		originalSlot = player.getInventory().getSelectedSlot();
		holding = true;
		stage = Stage.AIM_BASE;
		nextCycleAt = 0L;
		stageSince = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------
	// 置く
	// ------------------------------------------------------------------

	private static void aimAndPlace(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager interaction, FastPlaceConfig cfg, double dtMs, long now) {
		// 上面の中心へ、実際に体を向ける
		Vec3d top = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
		SmoothLook.stepFrame(player, top, cfg.clickCrystalAimSpeed, dtMs, AIM_TOLERANCE_DEG);

		// 「本当に今そこを見ているか」を毎フレーム引き直す（crosshairTarget は 20Hz なので使わない）
		BlockHitResult hit = lookingAt(client.world, player, PLACE_REACH);
		if (hit == null || !hit.getBlockPos().equals(base) || hit.getSide() != Direction.UP) {
			return;   // まだ狙いが合っていない -> 押さない
		}
		int slot = findCrystalSlot(player);
		if (slot < 0) {
			stop(client);
			return;
		}
		player.getInventory().setSelectedSlot(slot);
		interaction.interactBlock(player, Hand.MAIN_HAND, hit);
		player.swingHand(Hand.MAIN_HAND);
		stage = Stage.AIM_CRYSTAL;
		stageSince = now;
	}

	// ------------------------------------------------------------------
	// 殴る
	// ------------------------------------------------------------------

	private static void aimAndSwing(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager interaction, FastPlaceConfig cfg, double dtMs, long now) {
		EndCrystalEntity crystal = placedCrystal(client, player);
		if (crystal == null) {
			// サーバーからのスポーン待ち。来なければ何もしない（見えない物は殴らない）
			if (now - stageSince > SPAWN_TIMEOUT_MS) {
				stage = Stage.AIM_BASE;
				nextCycleAt = now + Math.max(20L, cfg.clickCrystalIntervalMs);
			}
			return;
		}
		Vec3d point = crystal.getBoundingBox().getCenter();
		if (player.getEyePos().distanceTo(point) > ATTACK_REACH) {
			stage = Stage.AIM_BASE;   // 届かない
			return;
		}
		if (!player.canSee(crystal)
			|| !AimAssistModule.clearLine(player, client.world, player.getEyePos(), point, crystal)) {
			stage = Stage.AIM_BASE;   // 壁越し・遮蔽あり -> 殴らない
			return;
		}
		SmoothLook.stepFrame(player, point, cfg.clickCrystalAimSpeed, dtMs, AIM_TOLERANCE_DEG);
		if (SmoothLook.errorDeg(player, point) > 2.5) {
			return;   // まだ振り向いている途中
		}
		interaction.attackEntity(player, crystal);
		player.swingHand(Hand.MAIN_HAND);
		stage = Stage.AIM_BASE;
		nextCycleAt = now + Math.max(20L, cfg.clickCrystalIntervalMs);
	}

	// ------------------------------------------------------------------
	// 判定
	// ------------------------------------------------------------------

	/** いま見ているブロック（バニラのクロスヘアと同じ方法で、毎フレーム計算） */
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

	private static boolean baseStillValid(MinecraftClient client, ClientPlayerEntity player,
			FastPlaceConfig cfg) {
		if (client.world == null || base == null) {
			return false;
		}
		if (!isCrystalBase(client.world, base)) {
			return false;
		}
		if (!client.world.getBlockState(base.up()).isAir()) {
			return false;
		}
		double range = Math.min(cfg.clickCrystalRange, PLACE_REACH);
		return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(base)) <= range * range;
	}

	private static boolean isCrystalBase(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
	}

	/** 自分が置いた位置にあるクリスタルだけを対象にする（そこから 0.6 ブロック以内） */
	private static EndCrystalEntity placedCrystal(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null || base == null) {
			return null;
		}
		Vec3d center = Vec3d.ofCenter(base.up());
		EndCrystalEntity best = null;
		double bestDistance = 0.6 * 0.6;
		for (EndCrystalEntity crystal : client.world.getEntitiesByClass(EndCrystalEntity.class,
			new net.minecraft.util.math.Box(center, center).expand(2.0), e -> true)) {
			double distance = crystal.getBoundingBox().getCenter().squaredDistanceTo(center);
			if (distance <= bestDistance && crystal.squaredDistanceTo(player) <= 36.0) {
				bestDistance = distance;
				best = crystal;
			}
		}
		return best;
	}

	private static int findCrystalSlot(ClientPlayerEntity player) {
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			if (inventory.getStack(slot).isOf(Items.END_CRYSTAL)) {
				return slot;
			}
		}
		return -1;
	}

	private static void stop(MinecraftClient client) {
		if (holding && FastPlaceConfig.get().clickCrystalRestoreSlot
			&& originalSlot >= 0 && client.player != null) {
			client.player.getInventory().setSelectedSlot(originalSlot);
		}
		holding = false;
		base = null;
		originalSlot = -1;
		stage = Stage.AIM_BASE;
		stageSince = 0L;
		nextCycleAt = 0L;
	}
}
