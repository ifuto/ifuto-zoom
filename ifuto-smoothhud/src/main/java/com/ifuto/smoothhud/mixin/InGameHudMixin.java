package com.ifuto.smoothhud.mixin;

import com.ifuto.smoothhud.ConfigManager;
import com.ifuto.smoothhud.Slide;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 選択枠を滑らかに動かす（元 Mod の移植＋追い越し禁止）。
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	@Unique
	private float currentX;
	@Unique
	private long lastTickTime;

	@Inject(method = "renderHotbar", at = @At("HEAD"))
	private void onRenderHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;

		if (player == null) {
			return;
		}

		float targetX = player.getInventory().getSelectedSlot() * Slide.SLOT;
		long now = System.currentTimeMillis();

		if (this.lastTickTime == 0) {
			this.currentX = targetX;
			this.lastTickTime = now;
			return;
		}

		float deltaTime = (now - this.lastTickTime) / 1000.0f;
		this.lastTickTime = now;
		this.currentX = Slide.toward(this.currentX, targetX, deltaTime, ConfigManager.getConfig().speed);
	}

	@ModifyArgs(method = "renderHotbar", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V",
			ordinal = 1))
	private void moveSelection(Args args) {
		int baseX = (MinecraftClient.getInstance().getWindow().getScaledWidth() - 182) / 2 - 1;
		args.set(2, Math.round(baseX + this.currentX));
	}
}
