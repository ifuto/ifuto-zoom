package com.ifuto.fastplace.hud;

import com.ifuto.fastplace.FastPlaceModule;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * 高速設置を <b>描画フレームごと</b> に撃つためのフック（何も描かない）。
 *
 * <p>tick（20Hz）で撃つと設置時刻が必ず 50ms の倍数になり、間隔が
 * 「50, 50, 100…」と同じ数字の繰り返しになる。フレームで撃てば
 * 間隔は ms 単位のままばらけるので、サーバーから見た設置間隔が人間の連打に近づく。</p>
 */
public final class FastPlaceFrameHook implements HudElement {
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
				FastPlaceModule.frameStep(client, dtMs);
			}
		}

		lastNanos = now;
	}
}
