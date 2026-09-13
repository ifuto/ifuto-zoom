package com.ifuto.replay;

import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.hud.RecordingIndicator;
import com.ifuto.replay.recording.RecordingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口。キーの登録・毎ティックの上限チェック・接続に合わせた自動停止と、
 * 画面の隅のインジケータの登録をする。
 */
@Environment(EnvType.CLIENT)
public class IfutoReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "ifuto-replay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// キー設定画面に出るカテゴリ名（言語キー: key.category.ifuto-replay.replay）
	public static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "replay"));

	private static KeyBinding recordKey;
	private static KeyBinding markerKey;

	@Override
	public void onInitializeClient() {
		// 最初のフレームより前に設定ファイルを作って読み込んでおく
		ReplayConfig.get();

		recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ifuto-replay.toggle_recording",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				KEY_CATEGORY));

		markerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ifuto-replay.marker",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_M,
				KEY_CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (recordKey != null) {
				while (recordKey.wasPressed()) {
					RecordingManager.INSTANCE.toggle(client);
				}
			}

			if (markerKey != null) {
				while (markerKey.wasPressed()) {
					RecordingManager.INSTANCE.addMarker(client, null);
				}
			}

			// サイズ・時間の上限に達していたら自動で止める
			RecordingManager.INSTANCE.tick(client);
		});

		// サーバーに入ったら（設定により）自動で録り始める
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				RecordingManager.INSTANCE.onJoin(client, handler));

		// 抜けたら必ず保存して閉じる
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RecordingManager.INSTANCE.stop(client));

		// ゲーム終了時も同じ
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RecordingManager.INSTANCE.stop(client));

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
				Identifier.of(MOD_ID, "recording_indicator"), new RecordingIndicator());

		LOGGER.info("[ifuto-replay] initialized");
	}

	public static KeyBinding getRecordKey() {
		return recordKey;
	}

	public static KeyBinding getMarkerKey() {
		return markerKey;
	}
}
