package com.ifuto.f3.mixin;

import com.ifuto.f3.F3Config;
import com.ifuto.f3.SpoofBridge;
import com.ifuto.f3.SpoofEngine;
import net.minecraft.client.gui.hud.debug.DebugHudLines;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * バニラ F3 の全行はここを通る。文字列を書き換えて入れ直す。
 */
@Mixin(DebugHudLines.class)
public abstract class DebugHudLinesMixin {
	private static final ThreadLocal<Boolean> GUARD = ThreadLocal.withInitial(() -> false);

	@Inject(method = "addLine", at = @At("HEAD"), cancellable = true)
	private void ifutoF3$onAddLine(String line, CallbackInfo ci) {
		if (GUARD.get()) {
			return;
		}
		GUARD.set(true);
		try {
			((DebugHudLines) (Object) this).addLine(spoof("", line));
		} finally {
			GUARD.set(false);
		}
		ci.cancel();
	}

	@Inject(method = "addLineToSection", at = @At("HEAD"), cancellable = true)
	private void ifutoF3$onAddLineToSection(Identifier sectionId, String line, CallbackInfo ci) {
		if (GUARD.get()) {
			return;
		}
		GUARD.set(true);
		try {
			((DebugHudLines) (Object) this).addLineToSection(sectionId, spoof(sectionId.toString(), line));
		} finally {
			GUARD.set(false);
		}
		ci.cancel();
	}

	@Inject(method = "addLinesToSection", at = @At("HEAD"), cancellable = true)
	private void ifutoF3$onAddLinesToSection(Identifier sectionId, Collection<String> lines, CallbackInfo ci) {
		if (GUARD.get()) {
			return;
		}
		GUARD.set(true);
		try {
			String section = sectionId.toString();
			List<String> out = new ArrayList<>(lines.size());
			for (String line : lines) {
				out.add(line == null ? null : spoof(section, line));
			}
			((DebugHudLines) (Object) this).addLinesToSection(sectionId, out);
		} finally {
			GUARD.set(false);
		}
		ci.cancel();
	}

	private static String spoof(String section, String line) {
		try {
			F3Config cfg = F3Config.get();
			if (!cfg.masterEnabled) {
				return line;
			}
			return SpoofEngine.rewriteVanilla(cfg, section, line, SpoofBridge.capture(SpoofBridge.realFps));
		} catch (Exception e) {
			// 書き換えで落とさない。本物を通す。
			return line;
		}
	}
}
