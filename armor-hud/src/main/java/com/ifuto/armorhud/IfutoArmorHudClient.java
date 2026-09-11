package com.ifuto.armorhud;

import com.ifuto.armorhud.config.ArmorHudConfig;
import com.ifuto.armorhud.discord.DiscordBridge;
import com.ifuto.armorhud.discord.DiscordRichPresence;
import com.ifuto.armorhud.hud.ArmorHudRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armor HUD の入口。防具ウィジェットを HUD レイヤーに載せて、表示切替キー（既定: V）を登録する。
 */
@Environment(EnvType.CLIENT)
public class IfutoArmorHudClient implements ClientModInitializer {
	public static final String MOD_ID = "ifuto-armor-hud";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// キー設定画面に出るカテゴリ名（言語キー: key.category.ifuto-armor-hud.armor_hud）
	public static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "armor_hud"));

	private static KeyBinding toggleKey;

	/** 案内チャットをこのセッションで送ったか */
	private static boolean joinNoticeSent;

	/** 壊れ通知用に、前 tick の装備状態（耐久ありのものだけ） */
	private static final BreakWatch[] breakWatch = new BreakWatch[ArmorHudRenderer.SLOTS.length];

	public static KeyBinding getToggleKey() {
		return toggleKey;
	}

	// 直前の耐久・名前を覚えておくための軽いメモ
	private record BreakWatch(String name, int maxDamage, int lastDamage) {
		static BreakWatch of(ItemStack stack) {
			return new BreakWatch(stack.getName().getString(), stack.getMaxDamage(), stack.getDamage());
		}
	}

	@Override
	public void onInitializeClient() {
		ArmorHudConfig.get();

		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ifuto-armor-hud.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				KEY_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Discord 連携のハートビート（ロック中だけ中で動く）
			DiscordBridge.tick();

			if (toggleKey == null) {
				return;
			}

			while (toggleKey.wasPressed()) {
				ArmorHudConfig config = ArmorHudConfig.get();
				config.showHud = !config.showHud;
				config.save();
			}

			// 導入直後の人向け案内（最初の5回の参加まで。シングル・マルチどちらでも）
			if (client.player == null) {
				joinNoticeSent = false;
			} else if (!joinNoticeSent) {
				joinNoticeSent = true;
				ArmorHudConfig config = ArmorHudConfig.get();

				if (config.joinCount < 5) {
					config.joinCount++;
					config.save();
					client.player.sendMessage(Text.translatable("ifuto-armor-hud.join_notice"), false);
				}
			}

			// 装備が壊れた瞬間を検知して音とチャットで知らせる
			if (ArmorHudConfig.get().breakAlert) {
				checkBrokenArmor(client);
			}
		});

		// 常に最後に描けば他の MOD の HUD とだいたい共存できる
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "armor_hud"), new ArmorHudRenderer());


		// Discord 連携用チャンネル（他modと共有。登録済みならそのまま使う）
		DiscordBridge.registerPayloadType();

		// サーバーから抜けたら Rich Presence の占有を解放して他modに返す
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> DiscordBridge.onDisconnect());

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> DiscordRichPresence.get().shutdown());

		LOGGER.info("[ifuto-armor-hud] initialized");
	}

	// 前 tick は装備していた耐久アイテムが消えたら「壊れた」とみなす。
	// 手持ちに戻しただけの場合は lastDamage < 最大直前なので誤爆しない（最大-1 まで削れたものが消えたら壊れ判定）
	private static void checkBrokenArmor(net.minecraft.client.MinecraftClient client) {
		var player = client.player;

		if (player == null) {
			for (int i = 0; i < breakWatch.length; i++) {
				breakWatch[i] = null;
			}
			return;
		}

		for (int i = 0; i < ArmorHudRenderer.SLOTS.length; i++) {
			EquipmentSlot slot = ArmorHudRenderer.SLOTS[i];
			ItemStack stack = player.getEquippedStack(slot);

			if (stack.isEmpty() || !stack.isDamageable()) {
				BreakWatch watched = breakWatch[i];

				if (stack.isEmpty() && watched != null && watched.lastDamage() >= watched.maxDamage() - 1) {
					player.sendMessage(Text.translatable("ifuto-armor-hud.break_notice", watched.name()), false);
				}

				breakWatch[i] = null;
			} else {
				breakWatch[i] = BreakWatch.of(stack);
			}
		}
	}
}
