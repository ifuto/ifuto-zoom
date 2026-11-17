package com.ifuto.fastgenerate.command;

import com.ifuto.fastgenerate.FastGenerateConfig;
import com.ifuto.fastgenerate.pipeline.GenerationDriver;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * {@code /fastgenerate} — 状態の確認と ON/OFF。
 *
 * <p>速くなっているかは数字で見るしかないので、同時実行数・発行数・完了数・
 * tick 時間・フレーム時間をそのまま出す。</p>
 */
public final class FastGenerateCommand {
	private FastGenerateCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			register(dispatcher));
	}

	private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("fastgenerate")
			.then(CommandManager.literal("stats").executes(FastGenerateCommand::stats))
			.then(CommandManager.literal("on").executes(ctx -> setEnabled(ctx, true)))
			.then(CommandManager.literal("off").executes(ctx -> setEnabled(ctx, false)))
			.executes(FastGenerateCommand::stats));
	}

	private static int stats(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		List<String> lines = GenerationDriver.describe(source.getServer());
		for (String line : lines) {
			source.sendFeedback(() -> Text.literal(line), false);
		}
		return 1;
	}

	private static int setEnabled(CommandContext<ServerCommandSource> ctx, boolean enabled) {
		FastGenerateConfig config = FastGenerateConfig.get();
		config.enabled = enabled;
		config.save();
		if (!enabled) {
			GenerationDriver.releaseAll(ctx.getSource().getServer());
		}
		ctx.getSource().sendFeedback(() -> Text.literal("FastGenerate: " + (enabled ? "ON" : "OFF"))
			.formatted(enabled ? Formatting.GREEN : Formatting.RED), true);
		return 1;
	}
}
