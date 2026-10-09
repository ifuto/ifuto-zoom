package com.ifuto.kb.mixin;

import com.ifuto.kb.measure.HitTracker;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onEntityVelocityUpdate", at = @At("TAIL"))
	private void ifutoKb$onVelocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
		HitTracker.onVelocity(packet);
	}

	@Inject(method = "onEntityDamage", at = @At("TAIL"))
	private void ifutoKb$onDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
		HitTracker.onDamage(packet);
	}

	@Inject(method = "onEntityStatus", at = @At("TAIL"))
	private void ifutoKb$onStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
		HitTracker.onStatus(packet);
	}
}
