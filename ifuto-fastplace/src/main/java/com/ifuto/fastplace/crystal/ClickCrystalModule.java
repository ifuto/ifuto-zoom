package com.ifuto.fastplace.crystal;

import com.ifuto.fastplace.FastPlaceConfig;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * ClickCrystal。
 *
 * <p>黒曜石（岩盤）を<b>左クリック</b>した瞬間に、ホットバーのエンドクリスタルへ
 * 切り替えて、置く → 爆破する を {@code clickCrystalIntervalMs} 間隔で連続実行する。
 * 爆破は「近くのクリスタルを攻撃」で行うので、爆風で自分が飛ばない位置から
 * 撃ち続けられる。</p>
 *
 * <p>置いてから爆破する順番は、パケットが順序どおり届くことを利用している
 * （攻撃 → 設置 の順に送るので、サーバーでは必ず「爆破 → 設置」になる）。</p>
 */
public final class ClickCrystalModule {
	/** 爆破対象を探す半径 [blocks] */
	private static final double DETONATE_RADIUS = 6.0;

	private static boolean lastAttackPressed;
	private static boolean armed;
	private static long armedUntil;
	private static long nextActionAt;
	private static BlockPos basePos;
	private static int originalSlot = -1;

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
			armed = false;
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	public static boolean armed() {
		return armed;
	}

	public static void tick(MinecraftClient client) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interaction = client.interactionManager;

		if (!enabled() || player == null || client.world == null || interaction == null
			|| client.currentScreen != null || !player.isAlive() || player.isSpectator()
			|| interaction.getCurrentGameMode() == GameMode.SPECTATOR) {
			disarm(client);
			return;
		}

		long now = System.currentTimeMillis();
		boolean pressed = client.options.attackKey.isPressed();
		boolean clicked = pressed && !lastAttackPressed;
		lastAttackPressed = pressed;

		if (!armed && clicked) {
			BlockPos base = crystalBase(client, player, cfg);
			int slot = findCrystalSlot(player);
			if (base != null && slot >= 0) {
				armed = true;
				armedUntil = now + cfg.clickCrystalBurstMs;
				nextActionAt = now;          // 切り替えは数 ms（次 tick を待たない）
				basePos = base;
				originalSlot = player.getInventory().getSelectedSlot();
			}
		}
		if (!armed) {
			return;
		}
		if (now > armedUntil || basePos == null) {
			disarm(client);
			return;
		}
		if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(basePos))
			> cfg.clickCrystalRange * cfg.clickCrystalRange) {
			disarm(client);   // 離れた / 見失った
			return;
		}
		int slot = findCrystalSlot(player);
		if (slot < 0) {
			disarm(client);   // クリスタルが尽きた
			return;
		}
		if (now < nextActionAt) {
			return;
		}

		player.getInventory().setSelectedSlot(slot);

		// 1) 前のやつを爆破
		detonate(client, player, interaction);
		// 2) 新しく置く
		BlockHitResult hit = new BlockHitResult(
			Vec3d.ofCenter(basePos).add(0.0, 0.5, 0.0), Direction.UP, basePos, false);
		interaction.interactBlock(player, Hand.MAIN_HAND, hit);
		player.swingHand(Hand.MAIN_HAND);

		nextActionAt = now + Math.max(20L, cfg.clickCrystalIntervalMs);
	}

	private static void detonate(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager interaction) {
		if (client.world == null) {
			return;
		}
		Box area = player.getBoundingBox().expand(DETONATE_RADIUS);
		EndCrystalEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (EndCrystalEntity crystal : client.world.getEntitiesByClass(EndCrystalEntity.class, area, e -> true)) {
			double distance = crystal.squaredDistanceTo(player);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = crystal;
			}
		}
		if (best != null) {
			interaction.attackEntity(player, best);
		}
	}

	/** いま狙っている黒曜石（上が空気で、届く距離にある） */
	private static BlockPos crystalBase(MinecraftClient client, ClientPlayerEntity player, FastPlaceConfig cfg) {
		HitResult hit = client.crosshairTarget;
		if (!(hit instanceof BlockHitResult blockHit) || client.world == null) {
			return null;
		}
		BlockPos pos = blockHit.getBlockPos();
		BlockState state = client.world.getBlockState(pos);
		if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) {
			return null;
		}
		if (!client.world.getBlockState(pos.up()).isAir()) {
			return null;
		}
		if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos))
			> cfg.clickCrystalRange * cfg.clickCrystalRange) {
			return null;
		}
		return pos;
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

	private static void disarm(MinecraftClient client) {
		if (armed && FastPlaceConfig.get().clickCrystalRestoreSlot && originalSlot >= 0
			&& client.player != null) {
			client.player.getInventory().setSelectedSlot(originalSlot);
		}
		armed = false;
		basePos = null;
		originalSlot = -1;
		nextActionAt = 0L;
	}
}
