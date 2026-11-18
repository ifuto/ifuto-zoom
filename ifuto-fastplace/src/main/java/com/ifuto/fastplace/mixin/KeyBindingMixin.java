package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.input.VirtualKeys;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * モジュールが代わりに操作した瞬間だけ、キーを「押されている」と読ませる。
 *
 * <p>読み取り（{@code isPressed}）にだけ細工をする。本物の押下状態
 * （{@code setPressed} / {@code timesPressed}）は一切触らないので、
 * 実際の入力が壊れることはない。</p>
 *
 * <p>{@code require = 0}: このメソッド名が変わった環境でも起動は壊れない
 * （その場合キー表示 Mod にだけ映らなくなる）。</p>
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
	@Inject(method = "isPressed", at = @At("HEAD"), cancellable = true, require = 0)
	private void ifutoFastplace$virtualPress(CallbackInfoReturnable<Boolean> cir) {
		if (VirtualKeys.isVirtual((KeyBinding) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
