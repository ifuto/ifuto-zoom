package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.scaffold.ScaffoldView;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scaffold 中はマウスの縦移動を「視点」にだけ流す。
 *
 * <p>バニラは {@code Entity#changeLookDirection} でエンティティの yaw/pitch を直接
 * 動かす。そのままだと、体を下（+70 度）へ向けている間は上方向のマウス入力が
 * 90 度のクリップに当たって一部捨てられてしまう。ここで入力を横取りして
 * 視点側（カメラ）へ加算し、yaw はそのままエンティティへ反映する。</p>
 *
 * <p>{@code require = 0} にしてあるので、このメソッドが無い環境でも起動は壊れない。
 * その場合は {@link ScaffoldView#syncFromEntity} の差分方式がバックアップとして働く。</p>
 */
@Mixin(Entity.class)
public abstract class EntityLookMixin {
	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true, require = 0)
	private void ifutoFastplace$captureLook(double deltaX, double deltaY, CallbackInfo ci) {
		if (!((Object) this instanceof ClientPlayerEntity)) {
			return;
		}
		if (ScaffoldView.handleMouseLook(deltaX, deltaY)) {
			ci.cancel();
		}
	}
}
