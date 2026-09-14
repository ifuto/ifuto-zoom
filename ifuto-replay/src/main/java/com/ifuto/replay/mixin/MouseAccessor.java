package com.ifuto.replay.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 再生中にカーソルを「録っていた位置」へ戻す口。
 *
 * <p>インベントリのどこを指していたかが再現されるので、
 * プレビューでも書き出しでも、バニラのカーソルがその場所に出る。
 */
@Mixin(Mouse.class)
public interface MouseAccessor {
	@Accessor("x")
	void ifutoReplay$setX(double x);

	@Accessor("y")
	void ifutoReplay$setY(double y);
}
