package com.ifuto.replay.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.debug.DebugHudProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * デバッグ画面（F3）の現在の設定へ触る口。
 *
 * <p>1.21.11 から F3 の状態は「デバッグ画面の設定」という別の場所に分かれていて、
 * バニラの設定画面からは直接読めない。録っていた本人と同じ見え方にするために使う。
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
	@Accessor("debugHudEntryList")
	DebugHudProfile ifutoReplay$getDebugHudProfile();
}
