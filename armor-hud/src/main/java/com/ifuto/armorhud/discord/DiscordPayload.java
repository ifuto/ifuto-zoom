package com.ifuto.armorhud.discord;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * ifumods:server/discord のパケット本体。
 * 中身は「Client ID」「表示文」「時間秒数（任意）」の文字列。Client ID が空なら受け手側の設定値を使う。
 * 時間秒数: +なら残りカウントダウン、-なら経過表示、空/0 なら時間表示なし。
 * 旧形式（文字列1本 or 2本）も読めるようにしてある。
 */
public record DiscordPayload(String clientId, String text, String timeSpec) implements CustomPayload {
	public static final Identifier CHANNEL = Identifier.of("ifumods", "server/discord");
	public static final CustomPayload.Id<DiscordPayload> ID = new CustomPayload.Id<>(CHANNEL);
	public static final PacketCodec<RegistryByteBuf, DiscordPayload> CODEC = CustomPayload.codecOf(
			(value, buf) -> {
				buf.writeString(value.clientId);
				buf.writeString(value.text);
				buf.writeString(value.timeSpec);
			},
			buf -> {
				String first = buf.readString();
				String second = buf.readableBytes() > 0 ? buf.readString() : null;

				// 文字列が1本だけなら最古の形式（= 表示文）として扱う
				if (second == null) {
					return new DiscordPayload("", first, "");
				}

				String third = buf.readableBytes() > 0 ? buf.readString() : "";
				return new DiscordPayload(first, second, third);
			}
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
