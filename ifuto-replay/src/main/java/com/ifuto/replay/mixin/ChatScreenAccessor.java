package com.ifuto.replay.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * チャット欄の「いま入力している文字」を読む口。
 *
 * <p>送信するまでサーバーには一切届かないので、パケットには残らない。
 * だからこそ別に記録する（書き込みはしないので通信には影響しない）。
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
	@Accessor("chatField")
	TextFieldWidget ifutoReplay$getChatField();
}
