package com.ifuto.armorhud.discord;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * ifumods:server/discord のパケット本体。
 * 中身は「Client ID」「表示文」の文字列2本。Client ID が空なら受け手側の設定値を使う。
 * 旧形式（文字列1本 = 表示文だけ）も読めるようにしてある。
 */
public record DiscordPayload(String clientId, String text) implements CustomPayload {
	public static final Identifier CHANNEL = Identifier.of("ifumods", "server/discord");
	public static final CustomPayload.Id<DiscordPayload> ID = new CustomPayload.Id<>(CHANNEL);
	public static final PacketCodec<RegistryByteBuf, DiscordPayload> CODEC = CustomPayload.codecOf(
			(value, buf) -> {
				buf.writeString(value.clientId);
				buf.writeString(value.text);
			},
			buf -> {
				String first = buf.readString();
				String second = buf.readableBytes() > 0 ? buf.readString() : null;

				// 文字列が1本だけなら旧形式（= 表示文）として扱う
				if (second == null) {
					return new DiscordPayload("", first);
				}

				return new DiscordPayload(first, second);
			}
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
