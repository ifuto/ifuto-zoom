package com.ifuto.kb.mixin;

import com.ifuto.kb.gui.KbMenuHook;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void ifutoKb$addButton(MinecraftClient client, int width, int height, CallbackInfo ci) {
		addDrawableChild(KbMenuHook.makeKbButton((Screen) (Object) this, width));
	}
}
