package com.ifuto.replay.recording;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;

import java.util.function.Function;

/**
 * パケットを「そのままのワイヤ形式（可変長のパケットID + 中身）」に戻す。
 *
 * <p>バニラの PLAY 状態のコーデック（{@link PlayStateFactories}）をそのまま借りるので、
 * 対応表を自分で持たなくていいし、バニラが書き方を変えても追従できる。
 * レジストリ（動的レジストリ）は接続ごとに違うので、接続が変わったら作り直す。
 */
final class PacketEncoder {
	private final NetworkState<ClientPlayPacketListener> serverToClient;
	private final NetworkState<ServerPlayPacketListener> clientToServer;
	private final NetworkState<ServerPlayPacketListener> clientToServerCreative;

	PacketEncoder(DynamicRegistryManager registries) {
		Function<ByteBuf, RegistryByteBuf> binder = RegistryByteBuf.makeFactory(registries);
		this.serverToClient = PlayStateFactories.S2C.bind(binder);

		// C2S 側は「クリエイティブかどうか」で1つだけパケットの書式が変わる。
		// クライアントの状態をネットワークスレッドから読むのは危ないので、
		// パケットの種類だけを見て切り替える（CreativeInventoryAction はクリエイティブ専用）。
		this.clientToServer = PlayStateFactories.C2S.bind(binder, () -> false);
		this.clientToServerCreative = PlayStateFactories.C2S.bind(binder, () -> true);
	}

	void encode(ByteBuf out, Packet<?> packet, boolean outbound) {
		if (outbound) {
			NetworkState<ServerPlayPacketListener> state = packet instanceof CreativeInventoryActionC2SPacket
					? this.clientToServerCreative
					: this.clientToServer;

			state.codec().encode(out, asServerBound(packet));
		} else {
			this.serverToClient.codec().encode(out, asClientBound(packet));
		}
	}

	@SuppressWarnings("unchecked")
	private static Packet<ServerPlayPacketListener> asServerBound(Packet<?> packet) {
		return (Packet<ServerPlayPacketListener>) packet;
	}

	@SuppressWarnings("unchecked")
	private static Packet<ClientPlayPacketListener> asClientBound(Packet<?> packet) {
		return (Packet<ClientPlayPacketListener>) packet;
	}
}
