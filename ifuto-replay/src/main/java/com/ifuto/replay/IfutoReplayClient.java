package com.ifuto.replay;

import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.gui.PauseMenuButtons;
import com.ifuto.replay.gui.RecordingListScreen;
import com.ifuto.replay.gui.widget.ModernButton;
import com.ifuto.replay.hud.RecordingIndicator;
import com.ifuto.replay.playback.ReplayPlayback;
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
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
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
			} else if (screen instanceof TitleScreen) {
				// タイトル画面の左上に「録画一覧」（ロゴは中央・Realms 通知は右上なので被らない）
				ModernButton listButton = new ModernButton(4, 4, 112, 22,
						Text.translatable("ifuto-replay.menu.list"),
						button -> client.setScreen(new RecordingListScreen(screen)), ModernButton.Style.NORMAL);
				listButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.menu.list.tooltip")));
				Screens.getButtons(screen).add(listButton);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// サイズ・時間の上限に達していたら自動で止める
			RecordingManager.INSTANCE.tick(client);

			// 再生中なら時刻を進めてパケットを流す
			ReplayPlayback playback = ReplayPlayback.getActive();

			if (playback != null) {
				playback.tick();
			}
		});

		// サーバーに入ったら（設定により）自動で録り始める
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				RecordingManager.INSTANCE.onJoin(client, handler));

		// 抜けたら必ず保存して閉じる
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RecordingManager.INSTANCE.onDisconnect(client));

		// ゲーム終了時も同じ（再生中なら先に片付ける）
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			ReplayPlayback playback = ReplayPlayback.getActive();

			if (playback != null) {
				playback.stop(null);
			}

			RecordingManager.INSTANCE.stop(client);
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
				Identifier.of(MOD_ID, "recording_indicator"), new RecordingIndicator());

		LOGGER.info("[ifuto-replay] initialized");
	}
}
