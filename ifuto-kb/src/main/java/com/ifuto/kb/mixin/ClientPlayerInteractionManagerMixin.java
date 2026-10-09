package com.ifuto.kb.mixin;

import com.ifuto.kb.measure.HitTracker;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
	@Inject(method = "attackEntity", at = @At("TAIL"))
	private void ifutoKb$onAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
		HitTracker.onSwing(player, target);
	}
}
