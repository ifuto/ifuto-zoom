package com.ifuto.zoom.mixin;

import com.ifuto.zoom.ZoomState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Divides the vanilla field of view by the current zoom factor.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D", at = @At("RETURN"), cancellable = true)
	private void ifutoZoom$applyZoom(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
		double zoom = ZoomState.getRenderZoom();

		if (Math.abs(zoom - 1.0D) < 1.0E-4D) {
			return;
		}

		cir.setReturnValue(cir.getReturnValueD() / zoom);
	}
}
