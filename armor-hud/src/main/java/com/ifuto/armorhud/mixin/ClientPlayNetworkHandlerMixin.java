package com.ifuto.armorhud.mixin;

import com.ifuto.armorhud.discord.DiscordBridge;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.CustomPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * カスタムペイロードをのぞき見するだけ。キャンセルは絶対しないので、
 * ifumods:server/discord を受け取る他の ifuto mod と共存できる。
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onCustomPayload(Lnet/minecraft/network/packet/CustomPayload;)V", at = @At("HEAD"))
	private void ifutoArmorHud$peekDiscord(CustomPayload payload, CallbackInfo ci) {
		DiscordBridge.handle(payload);
	}
}
