package com.ifuto.replay.mixin;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.recording.LocalEvents;
import com.ifuto.replay.recording.RecordingManager;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * クライアントの通信を1か所で覗く。
 *
 * <p>{@code channelRead0} は「届いたパケットをバニラが処理する直前」、
 * {@code send} は「こちらから送るパケットをキューに入れる直前」。
 * どちらもシングルプレイ（ローカル接続）・マルチプレイの両方で必ず通るので、
 * ここ1か所を見るだけで全部取れる。
 *
 * <p>録画していないときは null チェック1回だけしかしないので、ほぼノーコスト。
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
	/** 何かがおかしくてもゲームの通信を止めないため、失敗は1回だけ記録する */
	private static final AtomicBoolean WARNED = new AtomicBoolean();

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
			at = @At("HEAD"))
	private void ifutoReplay$onInboundPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		// このあいだにクライアントが湧かせた物は「パケットとして残る物」なので記録しない
		LocalEvents.setFromPacket(true);

		// 世界を作ったパケットは録画していなくても覚える（あとで途中から
		// 録り始めたときの土台にする。受け取り側でも覚えているが念のため）
		if (packet instanceof GameJoinS2CPacket join) {
			RecordingManager.rememberJoinPacket(join);
		} else if (packet instanceof PlayerRespawnS2CPacket respawn) {
			RecordingManager.rememberRespawnPacket(respawn);
		}

		if (!RecordingManager.INSTANCE.isRecording()) {
			return;
		}

		try {
			RecordingManager.INSTANCE.onInboundPacket((ClientConnection) (Object) this, packet);
		} catch (Throwable t) {
			warn(t);
		}
	}

	/**
	 * パケットを処理しているあいだの印。
	 *
	 * <p>このあいだにクライアントが湧かせた物（パーティクルなど）は「サーバーから届いた物」
	 * なので、パケット側に残っている。二重に記録しないように印を付けておく。
	 * パケットの適用はこのメソッドの中で同じスレッドで行われる。
	 */
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
			at = @At("RETURN"))
	private void ifutoReplay$afterInboundPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		LocalEvents.setFromPacket(false);
	}

	@Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
			at = @At("HEAD"))
	private void ifutoReplay$onOutboundPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush,
											  CallbackInfo ci) {
		if (!RecordingManager.INSTANCE.isRecording()) {
			return;
		}

		try {
			RecordingManager.INSTANCE.onOutboundPacket((ClientConnection) (Object) this, packet);
		} catch (Throwable t) {
			warn(t);
		}
	}

	private static void warn(Throwable t) {
		if (WARNED.compareAndSet(false, true)) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] パケットの記録に失敗しました（以降は黙ります）", t);
		}
	}
}
