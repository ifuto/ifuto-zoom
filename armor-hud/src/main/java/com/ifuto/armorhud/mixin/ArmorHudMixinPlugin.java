package com.ifuto.armorhud.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * compat.* の mixin は「対象の Discord RPC ライブラリが環境にいる時だけ」当てる。
 * （対象クラスが無い環境で当てようとするとログが汚れるので、事前に存在確認する）
 */
public final class ArmorHudMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.contains(".compat.")) {
			return classExists(targetClassName);
		}

		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	private static boolean classExists(String targetClassName) {
		try {
			MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
			return true;
		} catch (ClassNotFoundException | IOException e) {
			return false;
		}
	}
}
