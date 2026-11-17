package com.ifuto.fastgenerate;

import com.ifuto.fastgenerate.command.FastGenerateCommand;
import com.ifuto.fastgenerate.pipeline.GenerationDriver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FastGenerate: 地形生成データパックの生成を先回りして速く見せる Mod。
 *
 * <p>サーバー側（シングルプレイなら統合サーバー）で、プレイヤーの進行方向のチャンクを
 * 先に生成しておく。同時実行数は AIMD で調整するので、弱い CPU では控えめ、
 * 強い CPU ではコアを使い切る。</p>
 */
public final class FastGenerateMod implements ModInitializer {
	public static final String MOD_ID = "fastgenerate";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final String VERSION = FabricLoader.getInstance()
		.getModContainer(MOD_ID)
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("dev");

	@Override
	public void onInitialize() {
		FastGenerateConfig config = FastGenerateConfig.get();

		ServerTickEvents.START_SERVER_TICK.register(server -> GenerationDriver.beginTick());
		ServerTickEvents.END_SERVER_TICK.register(GenerationDriver::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> GenerationDriver.reset());
		FastGenerateCommand.register();

		LOGGER.info("[fastgenerate] v{} loaded (enabled={}, lead={} chunks, window={}..{}, bgThreads={})",
			VERSION, config.enabled, config.leadChunks, config.minWindow, config.maxWindow,
			FastGeneratePreLaunch.bgThreads(Runtime.getRuntime().availableProcessors()));
	}
}
