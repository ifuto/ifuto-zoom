package com.ifuto.zoom.mixin;

import com.ifuto.zoom.ZoomState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ワールドの FOV を現在のズーム倍率で割る。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
	private void ifutoZoom$applyZoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
		// getFov はワールド描画用 (changingFov=true) と手持ち・オーバーレイ用 (false) で別に呼ばれる。
		// 前者だけ触れば手持ちアイテムはバニラサイズのままになる
		if (!changingFov) {
			return;
		}

		double zoom = ZoomState.getRenderZoom();

		if (Math.abs(zoom - 1.0D) < 1.0E-4D) {
			return;
		}

		cir.setReturnValue((float) (cir.getReturnValueF() / zoom));
	}
}
