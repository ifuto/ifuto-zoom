package com.ifuto.f3.mixin;

import com.ifuto.f3.module.ScaffoldModule;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * scaffold が ON のあいだはジャンプ入力を外す。
 * Input#tick の最後に飛び込み、組み立て直後の PlayerInput だけ差し替える。
 */
@Mixin(Input.class)
public abstract class InputMixin {
	@Shadow
	public PlayerInput playerInput;

	@Inject(method = "tick", at = @At("RETURN"))
	private void ifutoF3$blockJumpWhileScaffolding(CallbackInfo ci) {
		if (!ScaffoldModule.blocksJump()) {
			return;
		}
		PlayerInput in = this.playerInput;
		if (in == null || !in.jump()) {
			return;
		}
		this.playerInput = new PlayerInput(
			in.forward(), in.backward(), in.left(), in.right(),
			false, in.sneak(), in.sprint());
	}
}
