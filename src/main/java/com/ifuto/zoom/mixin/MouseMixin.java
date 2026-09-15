package com.ifuto.zoom.mixin;

import com.ifuto.zoom.ZoomState;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ズーム中のスクロールを倍率変更に回し、ついでに視点移動を減速する。
 */
@Mixin(Mouse.class)
public class MouseMixin {
	@Shadow
	private double cursorDeltaX;

	@Shadow
	private double cursorDeltaY;

	@Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
	private void ifutoZoom$onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (net.minecraft.client.MinecraftClient.getInstance().currentScreen != null) {
			return;
		}

		if (ZoomState.onScroll(vertical)) {
			ci.cancel();
		}
	}

	@Inject(method = "updateMouse(D)V", at = @At("HEAD"))
	private void ifutoZoom$slowDownCamera(double timeDelta, CallbackInfo ci) {
		double factor = ZoomState.getSensitivityFactor();

		if (factor != 1.0D) {
			this.cursorDeltaX *= factor;
			this.cursorDeltaY *= factor;
		}
	}
}
