package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link InputMixin} の保険。
 *
 * <p>キーボード入力は {@code KeyboardInput#tick} が {@code Input#tick} を呼ぶ形なので、
 * どちらで拾っても最終値がジャンプ無しになるように両方へ差し込む
 * （{@code withoutJump} は冪等なので二重に効いても問題ない）。</p>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Shadow
	public PlayerInput playerInput;

	@Inject(method = "tick", at = @At("RETURN"), require = 0)
	private void ifutoFastplace$blockJumpWhileBridging(CallbackInfo ci) {
		this.playerInput = ScaffoldModule.withoutJump(this.playerInput);
	}
}
