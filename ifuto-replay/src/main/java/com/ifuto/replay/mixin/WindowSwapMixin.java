package com.ifuto.replay.mixin;

import com.ifuto.replay.export.ReplayExporter;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.tracy.TracyFrameCapturer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 書き出し中は画面への表示を飛ばす。
 *
 * <p>書き出しに要るのはフレームバッファの中身だけ。画面に出す present は
 * そのぶん丸ごと無駄だし、ドライバの強制 vsync に引っかかると
 * リフレッシュレート以上出なくなる。飛ばすかどうかは書き出し側が決める
 * （進捗が見えるよう何枚かに1回は通す）。書き出し中でなければ何もしない。
 */
@Mixin(Window.class)
public class WindowSwapMixin {
	@Inject(method = "swapBuffers", at = @At("HEAD"), cancellable = true)
	private void ifutoReplay$skipSwapDuringExport(TracyFrameCapturer capturer, CallbackInfo ci) {
		if (ReplayExporter.shouldSkipSwap()) {
			ci.cancel();
		}
	}
}
