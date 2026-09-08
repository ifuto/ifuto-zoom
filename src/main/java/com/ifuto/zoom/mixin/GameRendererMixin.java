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
	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
	private void ifutoZoom$applyZoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
		// Vanilla asks for the "changing" fov for the world projection and for the plain one for the
		// held item / overlays, so only touching the former keeps the hand at its normal size.
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
