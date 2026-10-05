package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.compat.IrisCompat;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
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
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

	private final ReplayPlayback playback;

	private ModernButton playPause;
	private ModernButton speedButton;
	private ModernButton addressButton;
	private ModernButton perspectiveButton;

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

		controls.add(Text.literal("⏮"), 26, button -> this.restartAt(0L), ModernButton.Style.NORMAL)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.restart")));

		this.playPause = controls.add(this.playPauseText(), 40, button -> {
			this.playback.setPaused(!this.playback.isPaused());
			this.playPause.setMessage(this.playPauseText());
		}, ModernButton.Style.PRIMARY);

		this.speedButton = controls.add(this.speedText(), 62, button -> {
			this.playback.cycleSpeed();
			this.speedButton.setMessage(this.speedText());
		}, ModernButton.Style.NORMAL);

		controls.add(Text.literal("⚑ ◀"), 52, button -> this.jumpMarker(false), ModernButton.Style.NORMAL)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.marker_prev")));

		controls.add(Text.literal("⚑ ▶"), 52, button -> this.jumpMarker(true), ModernButton.Style.NORMAL)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.marker_next")));
		controls.fit();

		Row tools = new Row(left, toolsY, left + barWidth);

		tools.add(Text.translatable("ifuto-replay.preview.resource_packs"), 96, button -> this.openPackScreen(),
				ModernButton.Style.NORMAL);

		ModernButton shaders = tools.add(Text.translatable("ifuto-replay.preview.shaders"), 80,
				button -> this.openShaderScreen(), ModernButton.Style.NORMAL);

		if (!IrisCompat.isAvailable()) {
			shaders.active = false;
			shaders.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.shaders_missing")));
		}

		this.perspectiveButton = tools.add(this.perspectiveText(), 72, button -> {
			MinecraftClient client = MinecraftClient.getInstance();
			client.options.setPerspective(client.options.getPerspective().next());
			this.perspectiveButton.setMessage(this.perspectiveText());
		}, ModernButton.Style.NORMAL);

		this.addressButton = tools.add(this.addressText(), 104, button -> {
			ReplayConfig config = ReplayConfig.get();
			config.maskServerAddress = !config.maskServerAddress;
			config.save();
			this.addressButton.setMessage(this.addressText());
		}, ModernButton.Style.NORMAL);

		tools.add(Text.translatable("ifuto-replay.preview.edit"), 72, button -> this.openEditor(),
				ModernButton.Style.NORMAL);

		tools.add(Text.literal("✕"), 26, button -> this.close(), ModernButton.Style.DANGER)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.close")));
		tools.fit();
	}

	/** 世界を見せたいので背景を暗くしない */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		this.drawBar(context);
		this.drawInputOverlay(context);
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

		ReplayTheme.panel(context, left - 6, top - 4, barWidth + 12, this.height - 2 - (top - 4));

		// 1行目: 時刻 / 長さ、サーバー名
		String time = timeText(this.playback.timeMs()) + " / " + timeText(this.playback.durationMs());
		context.drawText(this.textRenderer, time, left, top, 0xFFFFFF, false);

		int timeWidth = this.textRenderer.getWidth(time);
		Text state = this.stateText();
		int stateWidth = this.textRenderer.getWidth(state);

		if (left + timeWidth + 8 + stateWidth < left + barWidth) {
			context.drawText(this.textRenderer, state, left + timeWidth + 8, top, 0xAAAAAA, false);
		}

		String address = ReplayConfig.get().displayAddress(this.playback.header().serverName());
		int addressWidth = this.textRenderer.getWidth(address);
		context.drawText(this.textRenderer, address, left + barWidth - addressWidth, top, 0x888888, false);
	}

	/**
	 * 「入力していた文字」と「開いていた画面」を上に出す。
	 *
	 * <p>どちらもパケットには残らない物。録った本人が何をしていたか分かるように、重ねて見せる。
	 */
	private void drawInputOverlay(DrawContext context) {
		String screenId = this.playback.screenId();
		String chatText = this.playback.chatText();

		if (screenId.isEmpty() && chatText.isEmpty()) {
			return;
		}

		int y = 6;

		if (!screenId.isEmpty()) {
			y = this.drawOverlayLine(context, Text.translatable("ifuto-replay.preview.screen",
					Text.translatable("ifuto-replay.screen." + screenId)), y, 0xDDDDDD);
		}

		if (!chatText.isEmpty()) {
			this.drawOverlayLine(context, Text.translatable("ifuto-replay.preview.typing", chatText), y, 0xFFFFFF);
		}
	}

	private int drawOverlayLine(DrawContext context, Text text, int y, int color) {
		int width = this.textRenderer.getWidth(text);
		int left = this.width / 2 - width / 2;
		ReplayTheme.fillRound(context, left - 5, y - 3, width + 10, 13, 6, 0xC00A0F14);
		ReplayTheme.strokeRound(context, left - 5, y - 3, width + 10, 13, 6, ReplayTheme.BORDER);
		context.drawText(this.textRenderer, text, left, y, color, false);
		return y + 15;
	}

	private Text stateText() {
		if (this.playback.isSeeking()) {
			return Text.translatable("ifuto-replay.preview.seeking").formatted(Formatting.YELLOW);
		}

		if (this.playback.isFinished()) {
			return Text.translatable("ifuto-replay.preview.finished").formatted(Formatting.GRAY);
		}

		// v5 からは設定に関わらず移動パケットを残すので、カメラは必ず動く。
		// 古いファイル（C2Sなし）だけ注意を出す。
		if (!this.playback.header().hasC2S() && this.playback.header().version() < 5) {
			return Text.translatable("ifuto-replay.preview.no_input").formatted(Formatting.GRAY);
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

	/** 編集画面へ（いま見ている位置から始める） */
	private void openEditor() {
		MinecraftClient client = MinecraftClient.getInstance();

		try {
			long at = this.playback.timeMs();
			Path file = this.playback.file();
			this.playback.dispose();
			ClipEditorScreen.openAt(client, file, at);
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 編集を開けませんでした", e);
			client.setScreen(new RecordingListScreen(new TitleScreen()));
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
		private final List<ModernButton> buttons = new ArrayList<>();
		private final List<Integer> widths = new ArrayList<>();

		Row(int left, int y, int right) {
			this.left = left;
			this.right = right;
			this.x = left;
			this.y = y;
		}

		ModernButton add(Text message, int width, Consumer<ModernButton> action, ModernButton.Style style) {
			if (this.x + width > this.right && this.x > this.left) {
				this.x = this.left;
				this.y -= ROW_HEIGHT + GAP;
			}

			ModernButton button = new ModernButton(this.x, this.y, width, ROW_HEIGHT, message, action, style);

			addDrawableChild(button);
			this.x += width + GAP;
			this.buttons.add(button);
			this.widths.add(width);
			return button;
		}

		/**
		 * 入り切らなければ全員少しずつ縮めて1行に収める。
		 *
		 * <p>上に折れると上の段と重なるので、文字が少し切れても1行に残すほうを選ぶ。
		 */
		void fit() {
			int count = this.buttons.size();

			if (count == 0) {
				return;
			}

			int total = 0;

			for (int width : this.widths) {
				total += width;
			}

			int gaps = GAP * (count - 1);
			int avail = this.right - this.left - gaps;

			if (total <= avail) {
				return;
			}

			// 比例配分（最低24）。最低幅のせいで溢れたら、大きい物から削る
			int[] fitted = new int[count];
			int used = 0;

			for (int i = 0; i < count; i++) {
				fitted[i] = Math.max(24, this.widths.get(i) * avail / total);
				used += fitted[i];
			}

			int over = used - avail;

			while (over > 0) {
				int widest = 0;

				for (int j = 1; j < count; j++) {
					if (fitted[j] > fitted[widest]) {
						widest = j;
					}
				}

				if (fitted[widest] <= 24) {
					break;
				}

				int cut = Math.min(over, fitted[widest] - 24);
				fitted[widest] -= cut;
				over -= cut;
			}

			int place = this.left;

			for (int i = 0; i < count; i++) {
				ModernButton button = this.buttons.get(i);
				button.setX(place);
				button.setWidth(fitted[i]);
				place += fitted[i] + GAP;
			}

			this.x = place;
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

			// 編集で先頭を落としたファイルは「ここから見せる」位置へ飛ぶ（前書きは見せない）
			if (playback.trimStartMs() > 0L) {
				playback.jumpTo(playback.trimStartMs());
			}

			client.setScreen(new ReplayPreviewScreen(playback));
		} catch (ReplayPlayback.NoWorldException e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 再生を始められませんでした", e);
			Screen back = parent == null ? new TitleScreen() : parent;
			client.setScreen(new NoticeScreen(new RecordingListScreen(back),
					Text.translatable("ifuto-replay.preview.error_title"),
					Text.translatable("ifuto-replay.preview.error_no_join")));
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 再生を始められませんでした", e);
			Screen back = parent == null ? new TitleScreen() : parent;
			String detail = e.getMessage() == null ? e.toString() : e.getMessage();
			client.setScreen(new NoticeScreen(new RecordingListScreen(back),
					Text.translatable("ifuto-replay.preview.error_title"),
					Text.translatable("ifuto-replay.preview.error_unknown", Text.literal(detail))));
		}
	}
}
