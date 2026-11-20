package com.ifuto.fastplace.hud;

import com.ifuto.fastplace.FastPlaceModule;
import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.anchor.AutoSafeAnchorModule;
import com.ifuto.fastplace.crystal.ClickCrystalModule;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * 描画フレームごとに動くモジュールの入口（何も描かない）。
 *
 * <p>20Hz の tick で視点や設置時刻を書くと、どうしても 50ms ごとの段差が出る。
 * ここは毎フレーム呼ばれるので、速度（deg/s）や「予定時刻（ms）」をそのまま
 * 時間で積める。フレームレートが変わっても見た目が変わらない。</p>
 *
 * <p>順番: 高速設置 → アシスト → クリスタル → セーフアンカー。
 * 視点を書くのはアシストが先、モジュールが自分で向きを操作するのはその後。</p>
 */
public final class FrameDriver implements HudElement {
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
				AimAssistModule.frameStep(client, dtMs);
				ClickCrystalModule.frameStep(client, dtMs);
				AutoSafeAnchorModule.frameStep(client, dtMs);
			}
		}
		lastNanos = now;
	}
}
