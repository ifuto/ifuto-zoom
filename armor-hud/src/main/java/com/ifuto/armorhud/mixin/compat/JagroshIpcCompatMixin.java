package com.ifuto.armorhud.mixin.compat;

import com.ifuto.armorhud.discord.DiscordBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * よくある Discord RPC ライブラリ (com.jagrosh.discordipc) 向けの抑止。
 * サーバー管理のプレゼンスを表示中（DiscordBridge のロック中）は、
 * 他modからの接続・更新を止める。サーバーから抜ければ解除されるので
 * 向こう側の再送信ロジックがそのまま復帰できる。
 */
@Pseudo
@Mixin(targets = "com.jagrosh.discordipc.IPCClient", remap = false)
public abstract class JagroshIpcCompatMixin {
	@Inject(method = { "connect", "sendRichPresence" }, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void ifuto$holdWhileServerOwned(CallbackInfo ci) {
		if (DiscordBridge.isServerLocked()) {
			ci.cancel();
		}
	}
}
