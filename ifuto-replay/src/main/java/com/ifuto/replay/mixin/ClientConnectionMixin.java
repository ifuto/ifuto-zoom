package com.ifuto.replay.mixin;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.recording.RecordingManager;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
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
		if (!RecordingManager.INSTANCE.isRecording()) {
			return;
		}

		try {
			RecordingManager.INSTANCE.onInboundPacket((ClientConnection) (Object) this, packet);
		} catch (Throwable t) {
			warn(t);
		}
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
