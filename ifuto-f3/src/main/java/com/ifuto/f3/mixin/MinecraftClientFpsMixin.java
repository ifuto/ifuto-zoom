package com.ifuto.f3.mixin;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.SpoofBridge;
import com.ifuto.f3.SpoofEngine;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * getCurrentFps() の返り値だけ詐称する。フィールドは本物のまま。
 * バニラも BetterF3（数字も色も）もここを見ているので一発で効く。
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFpsMixin {
	@Shadow
	private int currentFps;

	@Inject(method = "getCurrentFps", at = @At("HEAD"), cancellable = true)
	private void ifutoF3$spoofFps(CallbackInfoReturnable<Integer> cir) {
		int real = this.currentFps;
		SpoofBridge.realFps = real;
		try {
			F3Config cfg = F3Config.get();
			if (!cfg.masterEnabled) {
				return;
			}
			cir.setReturnValue(SpoofEngine.fps(cfg, SpoofBridge.capture(real)));
		} catch (Exception ignored) {
			// 本物を返す
		}
	}
}
