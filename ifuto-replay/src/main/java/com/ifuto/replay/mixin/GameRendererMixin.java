package com.ifuto.replay.mixin;

import com.ifuto.replay.export.ReplayExporter;
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
		// 書き出し中は「次のフレームの時刻」へ進めてからカメラを動かす（1フレームずれない順番）
		ReplayExporter.onBeforeRenderFrame();
		ReplayPlayback.onRenderFrame(renderTickCounter.getTickProgress(true));
	}

	@Inject(method = "renderWorld", at = @At("TAIL"))
	private void ifutoReplay$afterRenderWorld(RenderTickCounter renderTickCounter, CallbackInfo ci) {
		// 世界が描き終わったので、その絵を取り込むよう書き出し側に頼む
		ReplayExporter.onAfterRenderFrame();
	}
}
