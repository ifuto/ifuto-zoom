package com.ifuto.fastplace.hud;

import com.ifuto.fastplace.aim.AimAssistModule;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * アシストの回転を <b>描画フレームごと</b> に適用するためのフック（何も描かない）。
 *
 * <p>20 回/秒の tick で yaw を書くと 50ms ごとに段差が出てカクつく。
 * ここは毎フレーム呼ばれるので、速度 (deg/s) をそのまま時間で積める。</p>
 */
public final class AimFrameHook implements HudElement {
	/** これ以上空いたら（タブ戻し等）差分を捨てる [ms] */
	private static final double MAX_FRAME_MS = 250.0;

	private long lastNanos;

	@Override
	public void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		long now = System.nanoTime();
		if (lastNanos != 0L) {
			double dtMs = (now - lastNanos) / 1.0e6;
			if (dtMs > 0.0 && dtMs < MAX_FRAME_MS) {
				AimAssistModule.frameStep(client, dtMs);
			}
		}
		lastNanos = now;
	}
}
