package com.ifuto.fastplace.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * itemUseCooldown への書き込み口。バニラは持っているが他クラスからは触れないので accessor を切る。
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
	@Accessor("itemUseCooldown")
	void ifutoFastplace$setItemUseCooldown(int cooldown);
}
