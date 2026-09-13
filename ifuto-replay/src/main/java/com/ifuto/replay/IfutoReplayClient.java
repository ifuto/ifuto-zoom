package com.ifuto.replay;

import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.gui.PauseMenuButtons;
import com.ifuto.replay.hud.RecordingIndicator;
import com.ifuto.replay.recording.RecordingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口。操作はぜんぶボタン（ポーズメニューの隅）から。キーバインドは持たない。
 *
 * <p>やっていること:
 * ポーズメニューへのボタン追加・毎ティックの上限チェック・接続に合わせた自動停止・
 * 画面の隅のインジケータの登録。
 */
@Environment(EnvType.CLIENT)
public class IfutoReplayClient implements ClientModInitializer {
	public static final String MOD_ID = "ifuto-replay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		// 最初のフレームより前に設定ファイルを作って読み込んでおく
		ReplayConfig.get();

		// ESC のポーズメニューに「録画 / 一覧 / 設定」のボタンを足す（Flashback と同じ置き方）
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen) {
				PauseMenuButtons.attach(client, screen, scaledWidth, scaledHeight);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
}
