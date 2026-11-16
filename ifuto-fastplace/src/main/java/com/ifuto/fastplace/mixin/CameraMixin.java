package com.ifuto.fastplace.mixin;

import com.ifuto.fastplace.scaffold.ScaffoldView;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * カメラのピッチだけをプレイヤーの視点に差し替える。
 *
 * <p>Scaffold 中はエンティティのピッチ（サーバーに送られる本物の向き）が下を向くので、
 * そのままだと自分の画面も下を向いてしまう。バニラはカメラを毎フレーム
 * {@code Camera#update} でエンティティから組み立てるため、その最後で回転だけを
 * 上書きして「前を見たまま歩ける」状態を作る。</p>
 *
 * <p>{@code setRotation} はカメラ内部のクォータニオンも同時に更新するので、
 * フィールドを直接書き換えるより安全（描画は getRotation() 側を見ている）。</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	public abstract float getYaw();

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("RETURN"), require = 0)
	private void ifutoFastplace$applyVisualPitch(CallbackInfo ci) {
		if (!ScaffoldView.cameraActive()) {
			return;
		}
		setRotation(getYaw(), ScaffoldView.cameraPitch());
	}
}
