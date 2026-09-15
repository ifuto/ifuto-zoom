package com.ifuto.replay.mixin;

import com.ifuto.replay.recording.RecordingManager;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「世界を作ったパケット」を覚えておく。
 *
 * <p>パケット録画は前向きにしか再生できないので、再生には「世界を作る最初のパケット」が必要。
 * 録り始めがサーバーに入った直後ならそのパケットも録れるが、**途中から** 録り始めた場合は
 * もう届かない。そこで、入ったときに届いた物を1個だけ覚えておいて、
 * あとで「いまの世界の写し」を作るときの土台にする。
 *
 * <p>ここは入室と次元移動のときにしか呼ばれないので、ふだんのコストはゼロ。
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onGameJoin", at = @At("HEAD"))
	private void ifutoReplay$onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
		RecordingManager.rememberJoinPacket(packet);
	}

	@Inject(method = "onPlayerRespawn", at = @At("HEAD"))
	private void ifutoReplay$onPlayerRespawn(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
		RecordingManager.rememberRespawnPacket(packet);
	}
}
