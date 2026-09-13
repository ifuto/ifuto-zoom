package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.compat.IrisCompat;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.playback.ReplayPlayback;
import com.ifuto.replay.playback.ReplayStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.List;

/**
 * 再生画面（プレビュー）。
 *
 * <p>キー操作は持たず、全部ボタン。背景を暗くしないので、うしろで世界がそのまま動いている。
 * リソースパックとシェーダーは再生中でも開き直せる（普通の世界として描いているので、
 * 変えたらすぐ見た目に反映される）。
 */
public class ReplayPreviewScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int TIMELINE_HEIGHT = 18;
	private static final int BAR_COLOR = 0x80000000;

	private final ReplayPlayback playback;

	private ButtonWidget playPause;
	private ButtonWidget speedButton;
	private ButtonWidget addressButton;
	private ButtonWidget perspectiveButton;

	public ReplayPreviewScreen(ReplayPlayback playback) {
		super(Text.translatable("ifuto-replay.preview.title"));
		this.playback = playback;
		this.playback.setRestartHandler(this::restartAt);
	}

	// --- 画面 ---

	@Override
	protected void init() {
		int barWidth = Math.min(this.width - 40, 560);
		int left = this.width / 2 - barWidth / 2;
		int bottom = this.height - 8;
		int toolsY = bottom - ROW_HEIGHT;
		int controlsY = toolsY - GAP - ROW_HEIGHT;
		int timelineY = controlsY - GAP - TIMELINE_HEIGHT;

		this.addDrawableChild(new ReplayTimelineWidget(left, timelineY, barWidth, TIMELINE_HEIGHT, this.playback));

		Row controls = new Row(left, controlsY, left + barWidth);

		controls.add(Text.literal("⏮"), 26, button -> this.restartAt(0L))
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.restart")));

		this.playPause = controls.add(this.playPauseText(), 40, button -> {
			this.playback.setPaused(!this.playback.isPaused());
			this.playPause.setMessage(this.playPauseText());
		});

		this.speedButton = controls.add(this.speedText(), 62, button -> {
			this.playback.cycleSpeed();
			this.speedButton.setMessage(this.speedText());
		});

		controls.add(Text.literal("⚑ ◀"), 52, button -> this.jumpMarker(false))
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.marker_prev")));

		controls.add(Text.literal("⚑ ▶"), 52, button -> this.jumpMarker(true))
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.marker_next")));

		Row tools = new Row(left, toolsY, left + barWidth);

		tools.add(Text.translatable("ifuto-replay.preview.resource_packs"), 96, button -> this.openPackScreen());

		ButtonWidget shaders = tools.add(Text.translatable("ifuto-replay.preview.shaders"), 80,
				button -> this.openShaderScreen());

		if (!IrisCompat.isAvailable()) {
			shaders.active = false;
			shaders.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.shaders_missing")));
		}

		this.perspectiveButton = tools.add(this.perspectiveText(), 72, button -> {
			MinecraftClient client = MinecraftClient.getInstance();
			client.options.setPerspective(client.options.getPerspective().next());
			this.perspectiveButton.setMessage(this.perspectiveText());
		});

		this.addressButton = tools.add(this.addressText(), 104, button -> {
			ReplayConfig config = ReplayConfig.get();
			config.maskServerAddress = !config.maskServerAddress;
			config.save();
			this.addressButton.setMessage(this.addressText());
		});

		tools.add(Text.literal("✕"), 26, button -> this.close())
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.close")));
	}

	/** 世界を見せたいので背景を暗くしない */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		this.drawBar(context);
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void tick() {
		// 次元移動などでバニラの「読み込み中」画面が出たら、すぐ戻す
		if (this.client.currentScreen != this && this.client.currentScreen instanceof LevelLoadingScreen) {
			this.client.setScreen(this);
		}

		this.playPause.setMessage(this.playPauseText());
	}

	@Override
	public boolean shouldPause() {
		// 止めない（うしろの世界を動かしたまま操作したい）
		return false;
	}

	@Override
	public void close() {
		this.playback.stop(new RecordingListScreen(new TitleScreen()));
	}

	// --- 描く物 ---

	private void drawBar(DrawContext context) {
		int barWidth = Math.min(this.width - 40, 560);
		int left = this.width / 2 - barWidth / 2;
		int top = this.height - 8 - ROW_HEIGHT * 2 - GAP * 2 - TIMELINE_HEIGHT - 16;

		context.fill(left - 6, top - 4, left + barWidth + 6, this.height - 2, BAR_COLOR);

		// 1行目: 時刻 / 長さ、サーバー名
		String time = timeText(this.playback.timeMs()) + " / " + timeText(this.playback.durationMs());
		context.drawText(this.textRenderer, time, left, top, 0xFFFFFF, true);

		int timeWidth = this.textRenderer.getWidth(time);
		Text state = this.stateText();
		int stateWidth = this.textRenderer.getWidth(state);

		if (left + timeWidth + 8 + stateWidth < left + barWidth) {
			context.drawText(this.textRenderer, state, left + timeWidth + 8, top, 0xAAAAAA, true);
		}

		String address = ReplayConfig.get().displayAddress(this.playback.header().serverName());
		int addressWidth = this.textRenderer.getWidth(address);
		context.drawText(this.textRenderer, address, left + barWidth - addressWidth, top, 0x888888, true);
	}

	private Text stateText() {
		if (this.playback.isSeeking()) {
			return Text.translatable("ifuto-replay.preview.seeking").formatted(Formatting.YELLOW);
		}

		if (this.playback.isFinished()) {
			return Text.translatable("ifuto-replay.preview.finished").formatted(Formatting.GRAY);
		}

		return Text.translatable("ifuto-replay.preview.playing");
	}

	// --- ボタンの中身 ---

	private void togglePlay() {
		this.playback.setPaused(!this.playback.isPaused());
	}

	private void jumpMarker(boolean forward) {
		List<ReplayStream.Marker> markers = this.playback.markers();
		long now = this.playback.timeMs();
		Long target = null;

		if (forward) {
			for (ReplayStream.Marker marker : markers) {
				if (marker.timeMs() > now + 500L) {
					target = marker.timeMs();
					break;
				}
			}
		} else {
			for (ReplayStream.Marker marker : markers) {
				if (marker.timeMs() < now - 500L) {
					target = marker.timeMs();
				}
			}
		}

		if (target != null) {
			this.playback.jumpTo(target);
		} else if (!forward) {
			this.restartAt(0L);
		}
	}

	/** 前に戻るときは世界を作り直す（パケットは前向きにしか流せない） */
	private void restartAt(long targetMs) {
		MinecraftClient client = MinecraftClient.getInstance();

		try {
			this.playback.dispose();
			ReplayPlayback fresh = ReplayPlayback.start(client, this.playback.file());
			client.setScreen(new ReplayPreviewScreen(fresh));
			fresh.jumpTo(targetMs);
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 再生をやり直せませんでした", e);
			client.setScreen(new RecordingListScreen(new TitleScreen()));
		}
	}

	private void openPackScreen() {
		MinecraftClient client = MinecraftClient.getInstance();

		client.setScreen(new PackScreen(
				client.getResourcePackManager(),
				manager -> {
					client.options.refreshResourcePacks(manager);
					client.setScreen(this);
				},
				client.getResourcePackDir(),
				Text.translatable("resourcePack.title")
		));
	}

	private void openShaderScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		Screen screen = IrisCompat.openShaderScreen(this);

		if (screen != null) {
			client.setScreen(screen);
		}
	}

	// --- 表示する文字 ---

	private Text playPauseText() {
		return Text.literal(this.playback.isPaused() ? "▶" : "❚❚");
	}

	private Text speedText() {
		double speed = this.playback.getSpeed();
		String value = speed == (long) speed ? String.valueOf((long) speed) : String.valueOf(speed);
		return Text.literal(value + "×");
	}

	private Text perspectiveText() {
		Perspective perspective = MinecraftClient.getInstance().options.getPerspective();
		return Text.translatable("ifuto-replay.preview.perspective." + perspective.name().toLowerCase(java.util.Locale.ROOT));
	}

	private Text addressText() {
		String key = ReplayConfig.get().maskServerAddress
				? "ifuto-replay.preview.address_hidden"
				: "ifuto-replay.preview.address_shown";
		return Text.translatable(key);
	}

	private static String timeText(long ms) {
		long total = Math.max(0L, ms / 1000L);
		return String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60L, total % 60L);
	}

	// --- ボタンを横に並べる ---

	private final class Row {
		private final int left;
		private final int right;
		private int x;
		private int y;

		Row(int left, int y, int right) {
			this.left = left;
			this.right = right;
			this.x = left;
			this.y = y;
		}

		ButtonWidget add(Text message, int width, ButtonWidget.PressAction action) {
			if (this.x + width > this.right && this.x > this.left) {
				this.x = this.left;
				this.y -= ROW_HEIGHT + GAP;
			}

			ButtonWidget button = ButtonWidget.builder(message, action)
					.dimensions(this.x, this.y, width, ROW_HEIGHT)
					.build();

			addDrawableChild(button);
			this.x += width + GAP;
			return button;
		}
	}

	// --- 一覧の「再生」から呼ぶ ---

	/** いまの世界を片付けてから再生を始める */
	public static void open(MinecraftClient client, Path file, @org.jspecify.annotations.Nullable Screen parent) {
		if (client.world != null || client.getNetworkHandler() != null) {
			client.setScreen(new ConfirmScreen(accepted -> {
				if (accepted) {
					start(client, file, parent);
				} else {
					client.setScreen(new RecordingListScreen(parent == null ? new TitleScreen() : parent));
				}
			}, Text.translatable("ifuto-replay.list.play_confirm_title"),
					Text.translatable("ifuto-replay.list.play_confirm_message")));

			return;
		}

		start(client, file, parent);
	}

	private static void start(MinecraftClient client, Path file, @org.jspecify.annotations.Nullable Screen parent) {
		if (client.world != null || client.getNetworkHandler() != null) {
			client.disconnect(new BlankScreen(), false, true);
		}

		try {
			ReplayPlayback playback = ReplayPlayback.start(client, file);
			client.setScreen(new ReplayPreviewScreen(playback));
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 再生を始められませんでした", e);
			client.setScreen(new RecordingListScreen(parent == null ? new TitleScreen() : parent));
		}
	}
}
