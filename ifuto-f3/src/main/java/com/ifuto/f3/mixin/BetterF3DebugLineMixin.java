package com.ifuto.f3.mixin;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.SpoofBridge;
import com.ifuto.f3.SpoofEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.lang.reflect.Field;

/**
 * BetterF3 の DebugLine.value() に渡される値を書き換える。
 * BetterF3 が無ければ @Pseudo で静かに外れる。
 */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.utils.DebugLine")
public abstract class BetterF3DebugLineMixin {
	private static Field idField;
	private static boolean idTried;

	@ModifyVariable(method = "value", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Object ifutoF3$spoofValue(Object value) {
		SpoofBridge.BetterF3State.hooked = true;
		try {
			F3Config cfg = F3Config.get();
			if (!cfg.masterEnabled) {
				return value;
			}
			return SpoofEngine.rewriteBetterF3(cfg, reflectId(), value, SpoofBridge.capture(SpoofBridge.realFps));
		} catch (Exception e) {
			return value;
		}
	}

	/** DebugLine の private な id をリフレクションで読む。取れなければ null。 */
	private String reflectId() {
		if (!idTried) {
			idTried = true;
			try {
				Field f = this.getClass().getDeclaredField("id");
				f.setAccessible(true);
				idField = f;
			} catch (Exception ignored) {
				idField = null;
			}
		}
		if (idField == null) {
			return null;
		}
		try {
			Object id = idField.get(this);
			return id == null ? null : id.toString();
		} catch (Exception ignored) {
			return null;
		}
	}
}
