package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link InputMixin} の保険（{@code KeyboardInput} は {@code Input#tick} を上書きするため）。
 *
 * <p><b>@Shadow を使わない</b>: {@code playerInput} は親クラス {@code Input} の
 * public フィールドなので、キャストすれば足りる。サブクラスの mixin から親のフィールドを
 * {@code @Shadow} すると解決に失敗して <b>クラス変換ごと落ちる</b>
 * （{@code Mixin transformation of net.minecraft.class_743 failed} → 起動クラッシュ）。</p>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("RETURN"), require = 0)
	private void ifutoFastplace$blockJumpWhileBridging(CallbackInfo ci) {
		Input self = (Input) (Object) this;
		ScaffoldModule.noteJumpHook();
		self.playerInput = ScaffoldModule.withoutJump(self.playerInput);
	}
}
