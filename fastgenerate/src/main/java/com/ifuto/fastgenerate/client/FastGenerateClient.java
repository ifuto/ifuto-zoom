package com.ifuto.fastgenerate.client;

import com.ifuto.fastgenerate.FastGenerateMod;
import com.ifuto.fastgenerate.pipeline.GenerationDriver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/**
 * クライアント側は「フレーム時間の計測」だけ。
 *
 * <p>内蔵 GPU のノート PC では、生成で CPU を使い切ると描画が詰まる。生成の並列度は
 * サーバーの tick 時間だけでなくフレーム時間も見て決めたいので、毎フレームの間隔を
 * 測って {@link GenerationDriver} に渡す（何も描かない HUD 要素として登録する）。</p>
 */
public final class FastGenerateClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
			Identifier.of(FastGenerateMod.MOD_ID, "frame_time"), new FrameSampler());
	}

	/** 描画せずにフレーム間隔だけ測る */
	private static final class FrameSampler implements HudElement {
		private long lastNanos;

		@Override
		public void render(DrawContext context, RenderTickCounter tickCounter) {
			long now = System.nanoTime();
			if (lastNanos != 0L) {
				GenerationDriver.setFrameTime((now - lastNanos) / 1.0e6);
			}
			lastNanos = now;
		}
	}
}
