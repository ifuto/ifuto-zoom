package com.ifuto.armorhud.discord;

import com.ifuto.armorhud.config.ArmorHudConfig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.packet.CustomPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ifumods:server/discord を受け取って Discord 連携に渡す橋渡し。
 *
 * 他の ifuto mod も同じチャンネルを受けるので、以下のお作法で衝突を避けている。
 * - 型の登録は「なければ登録」。すでに登録済みなら（他modの型でも）そのまま使う
 * - 受信はハンドラを登録せず mixin で読むだけ。packet は絶対にキャンセルしない
 * - 他modの型でデコードされた場合は record の String コンポーネントを順に読む
 *   （ガイドで「String clientId, String text の record」に揃えてあるので互いに読める）
 *
 * サーバーからパケットが来たら、退出するまではこちらが Rich Presence を握る。
 * 握っている間は他modの Discord RPC を mixin（mixin/compat）で止めていて、
 * ifuto mod 向けには JVM プロパティ ifumods.discordLock が立つのでそれを見てもらう。
 * サーバーから抜けたら止めるのをやめて、他modの RPC もそのまま動く状態に戻す。
 */
public final class DiscordBridge {
	public static final String LOCK_PROPERTY = "ifumods.discordLock";

	private static final Logger LOGGER = LoggerFactory.getLogger("ifuto-armor-hud");
	private static final int HEARTBEAT_TICKS = 200; // 10秒ごとに自前のプレゼンスを押し戻す

	private static volatile boolean serverLocked;
	private static int heartbeatTicks;

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

		String clientId;
		String text;

		if (payload instanceof DiscordPayload ours) {
			clientId = ours.clientId();
			text = ours.text();
		} else {
			List<String> values = readRecordStrings(payload);

			if (values == null || values.isEmpty()) {
				return;
			}

			clientId = values.size() >= 2 ? values.get(0) : "";
			text = values.size() >= 2 ? values.get(1) : values.get(0);
		}

		if (text == null) {
			text = "";
		}

		clientId = resolveClientId(clientId);

		if (clientId == null) {
			// 有効な Client ID がひとつも無いなら連携自体オフ。他modの邪魔もしない
			return;
		}

		setServerLocked(true);
		DiscordRichPresence.get().submit(clientId, text);
	}

	/** クライアント tick ごとに呼ぶ。ロック中は定期的に自前のプレゼンスを送り直す。 */
	public static void tick() {
		if (!serverLocked) {
			return;
		}

		if (++heartbeatTicks >= HEARTBEAT_TICKS) {
			heartbeatTicks = 0;
			DiscordRichPresence.get().refresh();
		}
	}

	/** サーバーから抜けたときに呼ぶ。ロックを外して他modの Discord RPC を解放する。 */
	public static void onDisconnect() {
		heartbeatTicks = 0;
		setServerLocked(false);
		DiscordRichPresence.get().release();
	}

	/** mixin から「サーバーが Rich Presence を握っているか」を見るための窓口。 */
	public static boolean isServerLocked() {
		return serverLocked;
	}

	private static void setServerLocked(boolean locked) {
		if (serverLocked == locked) {
			return;
		}

		serverLocked = locked;

		if (locked) {
			System.setProperty(LOCK_PROPERTY, "true");
		} else {
			System.clearProperty(LOCK_PROPERTY);
		}
	}

	// パケットの Client ID が使えなければ設定画面の値を使う。両方ダメなら null
	private static String resolveClientId(String fromPacket) {
		if (looksLikeClientId(fromPacket)) {
			return fromPacket.trim();
		}

		String fromConfig = ArmorHudConfig.get().discordClientId;
		return looksLikeClientId(fromConfig) ? fromConfig.trim() : null;
	}

	private static boolean looksLikeClientId(String value) {
		return value != null && value.trim().matches("\\d{15,25}");
	}

	// 他modの record 型としてデコードされたものから String コンポーネントを宣言順で拾う
	private static List<String> readRecordStrings(CustomPayload payload) {
		try {
			var components = payload.getClass().getRecordComponents();

			if (components == null) {
				return null;
			}

			List<String> values = new ArrayList<>(components.length);

			for (var component : components) {
				if (component.getType() == String.class) {
					Object value = component.getAccessor().invoke(payload);
					values.add(value instanceof String s ? s : null);
				}
			}

			return values;
		} catch (Exception e) {
			LOGGER.warn("[ifuto-armor-hud] discord パケット({}) の読み取りに失敗", payload.getClass().getName(), e);
			return null;
		}
	}
}
