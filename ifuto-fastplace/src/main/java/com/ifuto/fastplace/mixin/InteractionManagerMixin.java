package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.anchor.AutoSafeAnchorModule;
import com.ifuto.fastplace.stun.AutoStunSlamModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 左クリック（攻撃）と右クリック（ブロック使用）の入口に 1 か所だけフックを置く。
 *
 * <p>ここに入るのは<b>バニラの操作そのもの</b>（{@code MinecraftClient#doAttack} や
 * {@code doItemUse} が呼ぶ先）。パケットを組み立てるのではなく、同じ入口に
 * 乗っているだけなので、サーバーから見ても普通のクリックと区別が付かない。</p>
 *
 * <ul>
 *   <li>{@code attackEntity}: メイス + 盾の条件なら横取りして斧 → メイスに差し替える
 *       （{@link AutoStunSlamModule#interceptAttack}）</li>
 *   <li>{@code interactBlock}: 置かれたのがリスポーンアンカーなら Safe Anchor を始める
 *       （{@link AutoSafeAnchorModule#onBlockUse}）</li>
 * </ul>
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class InteractionManagerMixin {
	@Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true, require = 0)
	private void ifutoFastplace$stunSlam(PlayerEntity player, Entity target, CallbackInfo ci) {
		if (AutoStunSlamModule.interceptAttack(player, target)) {
			ci.cancel();
		}
	}

	@Inject(method = "interactBlock", at = @At("RETURN"), require = 0)
	private void ifutoFastplace$safeAnchor(ClientPlayerEntity player, Hand hand,
			BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
		AutoSafeAnchorModule.onBlockUse(MinecraftClient.getInstance(), player, hitResult,
			cir.getReturnValue());
	}
}
