```
A mod crashed on startup!
net.fabricmc.loader.impl.FormattedException: java.lang.RuntimeException: Could not execute entrypoint stage 'preLaunch' due to errors, provided by 'essential-loader' at 'gg.essential.loader.stage0.EssentialSetupPreLaunch'!
	at net.fabricmc.loader.impl.FormattedException.ofLocalized(FormattedException.java:63)
	at net.fabricmc.loader.impl.launch.knot.Knot.init(Knot.java:158)
	at net.fabricmc.loader.impl.launch.knot.Knot.launch(Knot.java:66)
	at net.fabricmc.loader.impl.launch.knot.KnotClient.main(KnotClient.java:23)
	at org.prismlauncher.launcher.impl.StandardLauncher.launch(StandardLauncher.java:115)
	at org.prismlauncher.EntryPoint.listen(EntryPoint.java:129)
	at org.prismlauncher.EntryPoint.main(EntryPoint.java:70)
Caused by: java.lang.RuntimeException: Could not execute entrypoint stage 'preLaunch' due to errors, provided by 'essential-loader' at 'gg.essential.loader.stage0.EssentialSetupPreLaunch'!
	at net.fabricmc.loader.impl.FabricLoaderImpl.lambda$invokeEntrypoints$0(FabricLoaderImpl.java:413)
	at net.fabricmc.loader.impl.util.ExceptionUtil.gatherExceptions(ExceptionUtil.java:33)
	at net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:411)
	at net.fabricmc.loader.impl.launch.knot.Knot.init(Knot.java:156)
	... 5 more
Caused by: net.fabricmc.loader.api.EntrypointException: Exception while loading entries for entrypoint 'preLaunch' provided by 'essential-loader'
	at net.fabricmc.loader.impl.entrypoint.EntrypointContainerImpl.getEntrypoint(EntrypointContainerImpl.java:56)
	at net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:409)
	... 6 more
Caused by: java.lang.RuntimeException: Mixin transformation of gg.essential.loader.stage0.EssentialSetupPreLaunch failed
	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.getPostMixinClassByteArray(KnotClassDelegate.java:440)
	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.tryLoadClass(KnotClassDelegate.java:336)
	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.loadClass(KnotClassDelegate.java:231)
	at net.fabricmc.loader.impl.launch.knot.KnotClassLoader.loadClass(KnotClassLoader.java:119)
	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:526)
	at java.base/java.lang.Class.forName0(Native Method)
	at java.base/java.lang.Class.forName(Class.java:536)
	at java.base/java.lang.Class.forName(Class.java:515)
	at net.fabricmc.loader.impl.util.DefaultLanguageAdapter.create(DefaultLanguageAdapter.java:53)
	at net.fabricmc.loader.impl.entrypoint.EntrypointStorage$NewEntry.getOrCreate(EntrypointStorage.java:124)
	at net.fabricmc.loader.impl.entrypoint.EntrypointContainerImpl.getEntrypoint(EntrypointContainerImpl.java:53)
	... 7 more
Caused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: Mixin [ifuto-fastplace.mixins.json:MinecraftClientAccessor from mod ifuto-fastplace] from phase [DEFAULT] in config [ifuto-fastplace.mixins.json] FAILED during PREPARE
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.handleMixinError(MixinProcessor.java:685)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.handleMixinPrepareError(MixinProcessor.java:632)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.prepareConfigs(MixinProcessor.java:590)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.select(MixinProcessor.java:509)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.checkSelect(MixinProcessor.java:485)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.lockAndSelect(MixinProcessor.java:437)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.couldTransformClass(MixinProcessor.java:397)
	at org.spongepowered.asm.mixin.transformer.MixinTransformer.couldTransformClass(MixinTransformer.java:265)
	at org.spongepowered.asm.mixin.transformer.MixinTransformer.transformClass(MixinTransformer.java:233)
	at org.spongepowered.asm.mixin.transformer.MixinTransformer.transformClassBytes(MixinTransformer.java:202)
	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.getPostMixinClassByteArray(KnotClassDelegate.java:435)
	... 17 more
Caused by: org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException: The specified mixin 'com.ifuto.fastplace.mixin.MinecraftClientAccessor' was not found
	at org.spongepowered.asm.mixin.transformer.MixinInfo.<init>(MixinInfo.java:886)
	at org.spongepowered.asm.mixin.transformer.MixinConfig.prepareMixins(MixinConfig.java:933)
	at org.spongepowered.asm.mixin.transformer.MixinConfig.prepare(MixinConfig.java:862)
	at org.spongepowered.asm.mixin.transformer.MixinProcessor.prepareConfigs(MixinProcessor.java:587)
	... 25 more
Caused by: java.lang.ClassNotFoundException: The specified mixin 'com.ifuto.fastplace.mixin.MinecraftClientAccessor' was not found
	at org.spongepowered.asm.mixin.transformer.MixinInfo.loadMixinClass(MixinInfo.java:1334)
	at org.spongepowered.asm.mixin.transformer.MixinInfo.<init>(MixinInfo.java:879)
	... 28 more
```
