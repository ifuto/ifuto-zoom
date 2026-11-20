package com.ifuto.fastplace.stun;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.aim.AimAssistModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.Set;

/**
 * Auto Stun Slam。
 *
 * <p>メイスで叩くと盾に吸われて「スタン（盾割り）」が取れない。そこで
 * <b>叩く直前に斧へ持ち替えて盾を割り、すぐメイスに戻して叩く</b>。</p>
 *
 * <p><b>発動条件</b>（すべて満たすときだけ）</p>
 * <ul>
 *   <li>手にメイス、ホットバーに斧がある</li>
 *   <li>落下距離 1 以上で浮遊中（= メイスのスマッシュが乗る高さ）</li>
 *   <li>視点の先の相手が盾を構えている（使用中のアイテムが盾）</li>
 *   <li>相手が生きていて、3.4 ブロック以内で、遮蔽が無い</li>
 * </ul>
 *
 * <p><b>やらないこと</b>: パケットを組み立てない。スロット変更も攻撃も
 * バニラと同じ入口（{@code setSelectedSlot} / {@code attackEntity}）を通す。
 * スロットの変更がサーバーに届いてから殴るために、斧とメイスの間に
 * 1 tick 以上の間を空ける。</p>
 */
public final class AutoStunSlamModule {
	/** 殴れる距離 [blocks]（バニラ 3.0 + ラグぶんの余裕） */
	private static final double REACH = 3.4;

	private enum Stage {
		IDLE,
		/** 斧へ持ち替える */
		SWITCH_AXE,
		/** 斧で盾を割る */
		HIT_AXE,
		/** メイスに戻して叩く */
		HIT_MACE
	}

	private static Stage stage = Stage.IDLE;
	private static Entity target;
	private static int maceSlot = -1;
	private static int axeSlot = -1;
	private static int previousSlot = -1;
	private static long stageAt;

	private static Set<Item> axes;

	private AutoStunSlamModule() {
	}

	private static Set<Item> axes() {
		Set<Item> cached = axes;
		if (cached == null) {
			cached = Set.of(
				Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.GOLDEN_AXE,
				Items.DIAMOND_AXE, Items.NETHERITE_AXE);
			axes = cached;
		}
		return cached;
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().stunSlamEnabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		cfg.stunSlamEnabled = on;
		cfg.save();
		if (!on) {
			reset(MinecraftClient.getInstance());
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	/** いま切り替えの途中か */
	public static boolean busy() {
		return stage != Stage.IDLE;
	}

	// ------------------------------------------------------------------
	// 横取り（mixin から呼ばれる）
	// ------------------------------------------------------------------

	/**
	 * メイスの攻撃を横取りする。true を返すとバニラの攻撃は取り消される
	 * （＝盾に当てて終わり、にならない）。
	 */
	public static boolean interceptAttack(PlayerEntity attacker, Entity victim) {
		if (!enabled() || stage != Stage.IDLE || !(attacker instanceof ClientPlayerEntity player)) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != player || client.world == null || client.currentScreen != null
			|| !player.isAlive() || player.isSpectator()) {
			return false;
		}
		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (interaction == null) {
			return false;
		}
		if (!(victim instanceof LivingEntity living) || living == player
			|| !living.isAlive() || living.isRemoved()) {
			return false;
		}
		if (!player.getMainHandStack().isOf(Items.MACE)) {
			return false;
		}
		// 浮遊中で、落下距離 1 以上（メイスのスマッシュが乗る条件）
		if (player.isOnGround() || player.fallDistance < 1.0f) {
			return false;
		}
		if (!shielding(living)) {
			return false;
		}
		int axe = findAxeSlot(player);
		if (axe < 0) {
			return false;
		}
		if (!inReachWithLine(client, player, living)) {
			return false;
		}
		previousSlot = player.getInventory().getSelectedSlot();
		maceSlot = previousSlot;
		axeSlot = axe;
		target = living;
		stage = Stage.SWITCH_AXE;
		stageAt = System.currentTimeMillis();
		return true;
	}

	// ------------------------------------------------------------------
	// 実行（tick から呼ばれる）
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
		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (interaction == null) {
			reset(client);
			return;
		}
		long now = System.currentTimeMillis();
		if (now < stageAt) {
			return;
		}
		FastPlaceConfig cfg = FastPlaceConfig.get();

		switch (stage) {
			case SWITCH_AXE -> {
				player.getInventory().setSelectedSlot(axeSlot);
				stage = Stage.HIT_AXE;
				// スロット変更がサーバーに届くのを待つ（同じ tick で殴ると
				// まだメイスのまま処理される）
				stageAt = now + Math.max(50, cfg.stunSlamAxeDelayMs);
			}
			case HIT_AXE -> {
				boolean hit = canHit(client, player, target);
				if (hit) {
					interaction.attackEntity(player, target);
					player.swingHand(Hand.MAIN_HAND);
				}
				player.getInventory().setSelectedSlot(maceSlot);
				if (!hit) {
					// 相手が逃げた / 盾を下ろした -> メイスは振らずに終わる
					reset(client);
					return;
				}
				stage = Stage.HIT_MACE;
				stageAt = now + Math.max(50, cfg.stunSlamMaceDelayMs);
			}
			case HIT_MACE -> {
				if (canHit(client, player, target)) {
					interaction.attackEntity(player, target);
					player.swingHand(Hand.MAIN_HAND);
				}
				reset(client);
			}
			default -> reset(client);
		}
	}

	// ------------------------------------------------------------------
	// 判定
	// ------------------------------------------------------------------

	/** 盾を構えている（使用中のアイテムが盾） */
	private static boolean shielding(LivingEntity living) {
		return living.isUsingItem() && living.getActiveItem().isOf(Items.SHIELD);
	}

	private static boolean canHit(MinecraftClient client, ClientPlayerEntity player, Entity victim) {
		if (!(victim instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
			return false;
		}
		return inReachWithLine(client, player, living);
	}

	private static boolean inReachWithLine(MinecraftClient client, ClientPlayerEntity player,
			LivingEntity living) {
		var point = living.getBoundingBox().getCenter();
		if (player.getEyePos().distanceTo(point) > REACH) {
			return false;
		}
		return AimAssistModule.clearLine(player, client.world, player.getEyePos(), point, living);
	}

	private static boolean isAxe(ItemStack stack) {
		return axes().contains(stack.getItem());
	}

	private static int findAxeSlot(ClientPlayerEntity player) {
		var inventory = player.getInventory();
		int best = -1;
		for (int slot = 0; slot < 9; slot++) {
			if (isAxe(inventory.getStack(slot))) {
				best = slot;
				if (inventory.getStack(slot).isOf(Items.NETHERITE_AXE)) {
					break;
				}
			}
		}
		return best;
	}

	private static void reset(MinecraftClient client) {
		if (stage != Stage.IDLE && FastPlaceConfig.get().stunSlamRestoreSlot
			&& previousSlot >= 0 && client.player != null
			&& client.player.getInventory().getSelectedSlot() != previousSlot
			&& client.player.getInventory().getSelectedSlot() == axeSlot) {
			// 斧を持ったまま終わった場合だけ戻す（メイスに戻せていれば触らない）
			client.player.getInventory().setSelectedSlot(previousSlot);
		}
		stage = Stage.IDLE;
		target = null;
		maceSlot = -1;
		axeSlot = -1;
		previousSlot = -1;
		stageAt = 0L;
	}
}
