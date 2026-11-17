package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scaffold が動いている間はジャンプ入力を落とす（God Bridge はジャンプしない）。
 * 歩き出しに 1 回跳ねるとブリッジが崩れるので、入力の段階で握りつぶす。
 */
@Mixin(Input.class)
public abstract class InputMixin {
	@Shadow
	public PlayerInput playerInput;

	@Inject(method = "tick", at = @At("RETURN"), require = 0)
	private void ifutoFastplace$blockJumpWhileBridging(CallbackInfo ci) {
		ScaffoldModule.noteJumpHook();
		this.playerInput = ScaffoldModule.withoutJump(this.playerInput);
	}
}
