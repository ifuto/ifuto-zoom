package com.ifuto.fastplace;

import com.ifuto.fastplace.aim.AimAssistModule;
import com.ifuto.fastplace.anchor.AutoSafeAnchorModule;
import com.ifuto.fastplace.gui.ModuleScreen;
import com.ifuto.fastplace.crystal.ClickCrystalModule;
import com.ifuto.fastplace.hud.CpsHud;
import com.ifuto.fastplace.hud.FrameDriver;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import com.ifuto.fastplace.stun.AutoStunSlamModule;
import com.ifuto.fastplace.tool.AutoToolModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FastPlaceMod implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("ifuto-fastplace");
	public static final String MOD_ID = "ifuto-fastplace";

	public static final String VERSION = FabricLoader.getInstance()
		.getModContainer(MOD_ID)
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("dev");

	private static final KeyBinding.Category MODULE_CATEGORY =
		KeyBinding.Category.create(Identifier.of(MOD_ID, "modules"));

	private static int worldTicks;
	private static boolean jumpHookWarned;
	private static boolean interactionHookChecked;

	private static KeyBinding modulesKey;
	private static KeyBinding fastPlaceKey;

	/** モジュール画面のキー表示用 */
	public static Text fastPlaceBindText() {
		return fastPlaceKey == null ? Text.literal("--") : fastPlaceKey.getBoundKeyLocalizedText();
	}

	@Override
	public void onInitializeClient() {
		FastPlaceConfig.load();
		LOGGER.info("[ifuto-fastplace] loaded (fastplace={}, intervalMs={}, scaffold={})",
			FastPlaceConfig.get().enabled,
			FastPlaceConfig.get().intervalMs,
			FastPlaceConfig.get().scaffoldEnabled);

		modulesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.ifuto-fastplace.modules",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_SHIFT,
			MODULE_CATEGORY));

		fastPlaceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.ifuto-fastplace.toggle_fastplace",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			MODULE_CATEGORY));

		// 入力 → モジュール → Scaffold の順で 1 tick の先頭に処理する。
		// Scaffold がエンティティの向きを書くのはプレイヤーの tick（= 移動パケット送信）より前。
		// Aim Assist は向きを、Auto Tool はホットバーを書く。Scaffold が最後（向きの最終決定権）。
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			while (modulesKey.wasPressed()) {
				// RShift は「開く / 閉じる」のトグル。チャット中などは何もしない
				if (client.currentScreen instanceof ModuleScreen) {
					client.setScreen(null);
				} else if (client.currentScreen == null) {
					client.setScreen(new ModuleScreen(null));
				}
			}
			while (fastPlaceKey.wasPressed()) {
				FastPlaceModule.toggle();
			}
			AimAssistModule.tick(client);
			AutoToolModule.tick(client);
			AutoStunSlamModule.tick(client);
			ClickCrystalModule.tick(client);
			AutoSafeAnchorModule.tick(client);
			ScaffoldModule.tick(client);
			warnIfJumpHookMissing(client);
			warnIfInteractionHookMissing(client);
		});

		ClientTickEvents.END_CLIENT_TICK.register(FastPlaceModule::onTick);

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
			Identifier.of(MOD_ID, "cps_counter"), new CpsHud());

		// フレーム単位で動くモジュール（高速設置 / アシスト / クリスタル / セーフアンカー）。
		// 視点を 20Hz で書くと 50ms ごとの段差が出るので、全部ここから動かす
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
			Identifier.of(MOD_ID, "frame_driver"), new FrameDriver());
	}

	/**
	 * 攻撃・ブロック使用の mixin が本当に当たっているかを、変換後のクラスを見て確かめる。
	 *
	 * <p>{@code require = 0} にしてあるので、メソッド名が変わった環境でも起動は壊れない
	 * 代わりに<b>黙って効かなくなる</b>。Auto Stun Slam / Auto Safe Anchor が
	 * 「動かない」と気づけるように、ハンドラのメソッドが生えているかを 1 回だけ見る。</p>
	 */
	private static void warnIfInteractionHookMissing(MinecraftClient client) {
		if (interactionHookChecked || client.player == null) {
			return;
		}
		interactionHookChecked = true;
		boolean found = false;
		try {
			for (var method : net.minecraft.client.network.ClientPlayerInteractionManager.class
					.getDeclaredMethods()) {
				if (method.getName().contains("ifutoFastplace$")) {
					found = true;
					break;
				}
			}
		} catch (Throwable ignored) {
			return;
		}
		if (!found) {
			LOGGER.warn("[ifuto-fastplace] interaction mixin is not applied; "
				+ "Auto Stun Slam / Auto Safe Anchor will do nothing. "
				+ "Mixin config is non-fatal (required=false).");
		}
	}

	/**
	 * mixin config を required=false にしてあるので、ジャンプ抑制ミキシンが適用できなくても
	 * ゲームは落ちない。代わりに「効いていない」ことを 1 回だけ警告する。
	 */
	private static void warnIfJumpHookMissing(MinecraftClient client) {
		if (client.player == null) {
			worldTicks = 0;
			return;
		}
		if (jumpHookWarned || ScaffoldModule.jumpHookAlive()) {
			return;
		}
		if (++worldTicks > 60) {
			jumpHookWarned = true;
			LOGGER.warn("[ifuto-fastplace] jump-suppression mixin is not applied; "
				+ "Scaffold will not block jumping. Mixin config is non-fatal (required=false).");
		}
	}
}
