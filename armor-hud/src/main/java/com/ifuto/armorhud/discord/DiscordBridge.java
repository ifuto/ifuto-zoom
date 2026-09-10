package com.ifuto.armorhud.discord;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.packet.CustomPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ifumods:server/discord を受け取って文字列を取り出す橋渡し。
 *
 * 他の ifuto mod も同じチャンネルを受けるので、以下のお作法で衝突を避けている。
 * - 型の登録は「なければ登録」。すでに登録済みなら（他modの型でも）そのまま使う
 * - 受信はハンドラを登録せず mixin で読むだけ。packet は絶対にキャンセルしない
 * - 他modの型でデコードされた場合は record の String コンポーネントを読む
 *   （ガイドで「文字列1本の record」に揃えてあるので互いに読める）
 */
public final class DiscordBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("ifuto-armor-hud");

	private DiscordBridge() {
	}

	/** クライアント初期化時に1回だけ呼ぶ。 */
	public static void registerPayloadType() {
		try {
			PayloadTypeRegistry.playS2C().register(DiscordPayload.ID, DiscordPayload.CODEC);
		} catch (IllegalArgumentException alreadyRegistered) {
			// 別の ifuto mod が先に登録している。それで問題ないのでそのまま進む
			LOGGER.info("[ifuto-armor-hud] discord チャンネルは他modが登録済み。そちらの型で受け取ります");
		}
	}

	/** ClientPlayNetworkHandler#onCustomPayload の頭から呼ばれる。 */
	public static void handle(CustomPayload payload) {
		if (payload == null || !payload.getId().id().equals(DiscordPayload.CHANNEL)) {
			return;
		}

		String text = null;

		if (payload instanceof DiscordPayload ours) {
			text = ours.text();
		} else {
			text = readRecordString(payload);
		}

		if (text != null) {
			DiscordRichPresence.get().submit(text);
		}
	}

	// 他modの record 型としてデコードされたものから、唯一の String コンポーネントを取り出す
	private static String readRecordString(CustomPayload payload) {
		try {
			var components = payload.getClass().getRecordComponents();

			if (components == null) {
				return null;
			}

			for (var component : components) {
				if (component.getType() == String.class) {
					Object value = component.getAccessor().invoke(payload);

					if (value instanceof String s) {
						return s;
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[ifuto-armor-hud] discord パケット({}) の読み取りに失敗", payload.getClass().getName(), e);
		}

		return null;
	}
}
