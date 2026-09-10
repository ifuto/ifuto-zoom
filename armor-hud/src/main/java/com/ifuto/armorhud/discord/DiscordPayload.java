package com.ifuto.armorhud.discord;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * ifumods:server/discord のパケット本体。中身は文字列1本だけ。
 * 他の ifuto mod も同じチャンネルを使う前提なので、型だけこのMODに閉じている。
 */
public record DiscordPayload(String text) implements CustomPayload {
	public static final Identifier CHANNEL = Identifier.of("ifumods", "server/discord");
	public static final CustomPayload.Id<DiscordPayload> ID = new CustomPayload.Id<>(CHANNEL);
	public static final PacketCodec<RegistryByteBuf, DiscordPayload> CODEC = CustomPayload.codecOf(
			(value, buf) -> buf.writeString(value.text),
			buf -> new DiscordPayload(buf.readString())
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
