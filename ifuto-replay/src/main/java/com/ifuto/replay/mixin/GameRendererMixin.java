package com.ifuto.replay.mixin;

import com.ifuto.replay.playback.ReplayPlayback;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * カメラを「1フレームごと」に動かすための場所。
 *
 * <p>カメラはバニラがこの直後（{@code GameRenderer#updateCameraState}）で組み立てるので、
 * その直前で位置を更新すれば、録画のとおりの視点で描かれる。
 * 再生していないときは null チェック1回だけなので、ほぼノーコスト。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "renderWorld", at = @At("HEAD"))
	private void ifutoReplay$beforeRenderWorld(RenderTickCounter renderTickCounter, CallbackInfo ci) {
		ReplayPlayback.onRenderFrame(renderTickCounter.getTickProgress(true));
	}
}
