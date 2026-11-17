package com.ifuto.fastplace.tool;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.human.ToolSelectModel;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto Tool。
 *
 * <p><b>何をするか</b>: 適正でない道具でブロックを掘ろうとしているとき、
 * {@link ToolSelectModel} が出した「人間が選ぶまでの時間」だけ待ってから、
 * ホットバーの一番速い道具へ持ち替える。掘り始めはバニラに任せる（攻撃キーは
 * 押されたままなので、道具が変わればそのまま掘り進む）。</p>
 *
 * <p><b>人間らしさ</b>:</p>
 * <ul>
 *   <li>持ち替えるまでに 100〜500ms の判断時間（Hick–Hyman / 練習のべき乗則 /
 *       drift-diffusion / 反復プライミング / 誤り後の減速）</li>
 *   <li>差が小さい相手では、たまに 2 番目の道具を掴む（人間の取り違え）</li>
 *   <li>一度見たブロックは知識として貯まり、次から速くなる</li>
 *   <li>掘るのをやめた / 狙いが変わったら、その判断は捨てる（人間も同じ）</li>
 * </ul>
 *
 * <p><b>遅くしない</b>: 15% 以上速くならないなら持ち替えない。持ち替え先が無ければ
 * 何もしない。判断が終わる前に掘り終わったブロックには何もしない。</p>
 */
public final class AutoToolModule {
	/** これ以上速くならないなら持ち替えない（% ではなく倍率差） */
	private static final double MIN_GAIN = 0.15;
	/** 残り耐久がこれ以下になった道具は避ける（設定で ON/OFF） */
	private static final int LOW_DURABILITY = 10;

	private static final ToolSelectModel MODEL = new ToolSelectModel();
	private static final Map<Block, Integer> SEEN = new HashMap<>();

	private static long decideAt = -1L;
	private static BlockPos pendingPos;
	private static Block pendingBlock;
	private static int pendingBest = -1;
	private static int pendingSecond = -1;
	private static boolean pendingMispick;

	private AutoToolModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().autoToolEnabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		cfg.autoToolEnabled = on;
		cfg.save();
		if (!on) {
			cancel();
			MODEL.reset();
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	/** いま判断待ちなら、その残り時間 [ms]（HUD・デバッグ用） */
	public static long pendingRemainingMs(long nowMs) {
		return decideAt < 0L ? -1L : Math.max(0L, decideAt - nowMs);
	}

	public static void tick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		ClientPlayerEntity player = client.player;
		if (!enabled() || player == null || client.world == null
			|| client.currentScreen != null
			|| ScaffoldModule.isEngaged()
			|| AimAssistModule.isTracking()
			|| !player.isAlive() || player.isSpectator()) {
			cancel();
			return;
		}

		FastPlaceConfig cfg = FastPlaceConfig.get();
		if (cfg.autoToolOnlyWhileMining && !client.options.attackKey.isPressed()) {
			cancel();
			return;
		}

		HitResult hit = client.crosshairTarget;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			cancel();
			return;
		}
		BlockPos pos = blockHit.getBlockPos();
		BlockState state = client.world.getBlockState(pos);
		if (state.isAir()) {
			cancel();
			return;
		}

		PlayerInventory inventory = player.getInventory();
		int current = inventory.getSelectedSlot();
		ItemStack held = inventory.getStack(current);
		double currentSpeed = Math.max(1.0f, held.getMiningSpeedMultiplier(state));

		double threshold = currentSpeed * (1.0 + MIN_GAIN);
		int best = -1;
		int second = -1;
		double bestSpeed = -1.0;
		double secondSpeed = -1.0;
		for (int slot = 0; slot < 9; slot++) {
			if (slot == current) {
				continue;
			}
			ItemStack stack = inventory.getStack(slot);
			if (stack.isEmpty() || (cfg.autoToolAvoidBreaking && isNearlyBroken(stack))) {
				continue;
			}
			double speed = stack.getMiningSpeedMultiplier(state);
			if (stack.isSuitableFor(state)) {
				speed += 0.01;   // 同速なら適正なほうを選ぶ（ごく僅かな加点）
			}
			if (speed <= threshold) {
				continue;   // 持ち替える価値が無い
			}
			if (speed > bestSpeed) {
				second = best;
				secondSpeed = bestSpeed;
				best = slot;
				bestSpeed = speed;
			} else if (speed > secondSpeed) {
				second = slot;
				secondSpeed = speed;
			}
		}

		if (best < 0) {
			cancel();   // 持ち替える価値が無い
			return;
		}

		Block block = state.getBlock();
		boolean samePlan = decideAt >= 0L
			&& pos.equals(pendingPos)
			&& pendingBlock == block
			&& pendingBest == best;
		if (!samePlan) {
			// 新しく判断を始める
			int candidates = second >= 0 ? 2 : 1;
			int prior = SEEN.getOrDefault(block, 0);
			double gain = currentSpeed > 1.0e-6 ? bestSpeed / currentSpeed - 1.0 : 1.0;
			long delay = cfg.autoToolHumanize
				? MODEL.decide(candidates, prior, best == MODEL.lastSlot(), gain)
				: 0L;
			decideAt = now + delay;
			pendingPos = pos;
			pendingBlock = block;
			pendingBest = best;
			pendingSecond = second;
			pendingMispick = cfg.autoToolHumanize && second >= 0 && MODEL.mispick(gain);
		}

		if (now < decideAt) {
			return;   // まだ迷っている
		}

		int target = pendingMispick && pendingSecond >= 0 ? pendingSecond : pendingBest;
		inventory.setSelectedSlot(target);
		SEEN.merge(pendingBlock, 1, Integer::sum);
		MODEL.onSwitch(target, pendingMispick);
		decideAt = -1L;
		pendingPos = null;
		pendingBlock = null;
		pendingBest = -1;
		pendingSecond = -1;
		pendingMispick = false;
	}

	private static boolean isNearlyBroken(ItemStack stack) {
		return stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= LOW_DURABILITY;
	}

	private static void cancel() {
		decideAt = -1L;
		pendingPos = null;
		pendingBlock = null;
		pendingBest = -1;
		pendingSecond = -1;
		pendingMispick = false;
	}
}
