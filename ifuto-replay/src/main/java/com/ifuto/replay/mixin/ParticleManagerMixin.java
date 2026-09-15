package com.ifuto.replay.mixin;

import com.ifuto.replay.recording.RecordingManager;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * クライアントが **自分で** 湧かせたパーティクルを記録する。
 *
 * <p>サーバーから届いた物はパケットとして残るので要らない
 * （{@link com.ifuto.replay.recording.LocalEvents#isFromPacket()} で見分ける）。
 * ここで拾うのは「パケットにならない物」だけ。
 *
 * <p>パーティクルは Minecraft が一番よく出す物なので、ここは最短で帰る
 * （録画していないときは RecordingManager 側の null チェック1回だけ）。
 */
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
	@Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
			at = @At("HEAD"))
	private void ifutoReplay$onAddParticle(ParticleEffect parameters, double x, double y, double z,
										   double velocityX, double velocityY, double velocityZ,
										   CallbackInfoReturnable<Particle> cir) {
		RecordingManager.INSTANCE.recordParticle(parameters, x, y, z, velocityX, velocityY, velocityZ);
	}
}
