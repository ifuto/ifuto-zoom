package com.ifuto.replay.gui;

import com.ifuto.replay.audio.AudioMode;
import com.ifuto.replay.audio.SystemAudioCapture;
import com.ifuto.replay.config.CompressionMode;
import com.ifuto.replay.config.IndicatorPosition;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
import com.ifuto.replay.gui.widget.ModernCycling;
import com.ifuto.replay.gui.widget.ModernSlider;
import com.ifuto.replay.gui.widget.ModernTextField;
import com.ifuto.replay.gui.widget.ModernToggle;
import com.ifuto.replay.recording.RecordingManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.LayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Mod Menu から開く設定画面。バニラのスクロールレイアウトを使っているので、ウィンドウの縦が短くても全部届く。
 *
 * <p>部品はバニラの絵ではなく {@link ReplayTheme} の角丸の物を使う。
 * 押し心地やキー操作はバニラのままなので、触り方は変わらない。
 */
@Environment(EnvType.CLIENT)
public class ReplayConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 158;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 8;
	private static final int ROW_SPACING = 4;
	private static final int SCROLL_MIN_HEIGHT = 120;

	private final Screen parent;
	private final ReplayConfig config;
	private ScrollableLayoutWidget scrollable;
	private ThreePartsLayoutWidget layout;

	/** 部品の幅（画面が狭いときは細くする。init で決める） */
	private int widgetWidth = WIDGET_WIDTH;

	public ReplayConfigScreen(Screen parent) {
		super(Text.translatable("ifuto-replay.config.title"));
		this.parent = parent;
		this.config = ReplayConfig.get();
	}

	@Override
	protected void init() {
		// 画面が狭い（GUIサイズが大きい）ときは部品を細くしてはみ出さないようにする
		this.widgetWidth = Math.min(WIDGET_WIDTH, Math.max(96, (this.width - 24 - COLUMN_GAP - 8) / 2));
		this.layout = new ThreePartsLayoutWidget(this);
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical());
		body.add(new TextWidget(Text.translatable("ifuto-replay.config.hint"), this.textRenderer),
				positioner -> positioner.marginBottom(6));

		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(ROW_SPACING);

		// 1行目: いつ録るか
		content.add(row(
				toggle("ifuto-replay.config.auto_record", this.config.autoRecord,
						"ifuto-replay.config.auto_record.tooltip",
						value -> this.config.autoRecord = value),
				toggle("ifuto-replay.config.record_client_packets", this.config.recordClientPackets,
						"ifuto-replay.config.record_client_packets.tooltip",
						value -> this.config.recordClientPackets = value)));

		// 1.5行目: Medal みたいな「あとから保存」
		content.add(row(
				toggle("ifuto-replay.config.clip_mode", this.config.clipMode,
						"ifuto-replay.config.clip_mode.tooltip",
						value -> this.config.clipMode = value),
				cycle("ifuto-replay.config.clip_seconds", CLIP_LENGTHS, this.config.clipSeconds,
						RecordingManager::clipLengthText,
						"ifuto-replay.config.clip_seconds.tooltip",
						value -> this.config.clipSeconds = value)));

		// 1.55行目: 一時ファイルの上限（SSD へ書く量もここで抑える）
		content.add(row(
				cycle("ifuto-replay.config.clip_buffer", CLIP_BUFFERS, this.config.clipBufferMb,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.clip_buffer.auto")
								: Text.translatable("ifuto-replay.config.gigabytes.value", value / 1024),
						"ifuto-replay.config.clip_buffer.tooltip",
						value -> this.config.clipBufferMb = value),
				null));

		// 1.6行目: クリップの置き場の整理
		content.add(row(
				cycle("ifuto-replay.config.clip_keep", List.of(0, 1, 4, 12, 24, 72, 168),
						this.config.clipKeepHours,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.clip_keep.never")
								: Text.translatable("ifuto-replay.config.clip_keep.hours", value),
						"ifuto-replay.config.clip_keep.tooltip",
						value -> this.config.clipKeepHours = value),
				null));

		// 1.2行目: クライアントの内側で起きたこと（パケットにならない物）
		content.add(row(
				toggle("ifuto-replay.config.record_particles", this.config.recordParticles,
						"ifuto-replay.config.record_particles.tooltip",
						value -> this.config.recordParticles = value),
				null));

		// 2行目: 軽さの調整
		content.add(row(
				cycle("ifuto-replay.config.compression", List.of(CompressionMode.values()),
						this.config.compression, CompressionMode::getText,
						"ifuto-replay.config.compression.tooltip",
						value -> this.config.compression = value),
				toggle("ifuto-replay.config.skip_keep_alive", this.config.skipKeepAlive,
						"ifuto-replay.config.skip_keep_alive.tooltip",
						value -> this.config.skipKeepAlive = value)));

		// 3行目: 再生とプライバシー
		content.add(row(
				toggle("ifuto-replay.config.save_registries", this.config.saveRegistries,
						"ifuto-replay.config.save_registries.tooltip",
						value -> this.config.saveRegistries = value),
				toggle("ifuto-replay.config.mask_address", this.config.maskServerAddress,
						"ifuto-replay.config.mask_address.tooltip",
						value -> this.config.maskServerAddress = value)));

		// 4行目: 自動停止
		content.add(row(
				slider("ifuto-replay.config.max_file_size", 0, 4096, this.config.maxFileSizeMb,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: value + " MB",
						"ifuto-replay.config.max_file_size.tooltip",
						value -> this.config.maxFileSizeMb = value),
				slider("ifuto-replay.config.max_duration", 0, 360, this.config.maxDurationMinutes,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: value + " " + Text.translatable("ifuto-replay.config.minutes").getString(),
						"ifuto-replay.config.max_duration.tooltip",
						value -> this.config.maxDurationMinutes = value)));

		// 4.5行目: メモリと容量の見張り
		content.add(row(
				slider("ifuto-replay.config.memory", 0, 128, Math.min(this.config.queuedMegaBytes, 128),
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.memory.auto").getString()
								: Text.translatable("ifuto-replay.config.memory.value", value).getString(),
						"ifuto-replay.config.memory.tooltip",
						value -> this.config.queuedMegaBytes = value),
				slider("ifuto-replay.config.flush_interval", 1, 10,
						Math.max(1, this.config.flushIntervalMs / 1000),
						value -> Text.translatable("ifuto-replay.config.flush_interval.value", value).getString(),
						"ifuto-replay.config.flush_interval.tooltip",
						value -> this.config.flushIntervalMs = value * 1000)));

		content.add(row(
				slider("ifuto-replay.config.low_disk_space", 0, 4096, Math.min(this.config.lowDiskSpaceMb, 4096),
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: Text.translatable("ifuto-replay.config.memory.value", value).getString(),
						"ifuto-replay.config.low_disk_space.tooltip",
						value -> this.config.lowDiskSpaceMb = value),
				slider("ifuto-replay.config.critical_disk_space", 0, 1024,
						Math.min(this.config.criticalDiskSpaceMb, 1024),
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: Text.translatable("ifuto-replay.config.memory.value", value).getString(),
						"ifuto-replay.config.critical_disk_space.tooltip",
						value -> this.config.criticalDiskSpaceMb = value)));

		// 5行目: 見た目
		content.add(row(
				toggle("ifuto-replay.config.show_indicator", this.config.showIndicator,
						"ifuto-replay.config.show_indicator.tooltip",
						value -> this.config.showIndicator = value),
				cycle("ifuto-replay.config.indicator_position", List.of(IndicatorPosition.values()),
						this.config.indicatorPosition, IndicatorPosition::getText,
						"ifuto-replay.config.indicator_position.tooltip",
						value -> this.config.indicatorPosition = value)));

		// 6行目: お知らせ / 途中から録るときの世界の写し
		content.add(row(
				toggle("ifuto-replay.config.notify_chat", this.config.notifyChat,
						"ifuto-replay.config.notify_chat.tooltip",
						value -> this.config.notifyChat = value),
				slider("ifuto-replay.config.snapshot_radius", 0, 16, this.config.snapshotRadius,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.snapshot_radius.off").getString()
								: Text.translatable("ifuto-replay.config.snapshot_radius.value", value).getString(),
						"ifuto-replay.config.snapshot_radius.tooltip",
						value -> this.config.snapshotRadius = value)));

		// 7行目: 保存先（全幅）
		ModernTextField folderField = new ModernTextField(this.textRenderer,
				0, 0, this.widgetWidth * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.save_folder"));
		folderField.setMaxLength(120);
		folderField.setText(this.config.saveFolder);
		folderField.setPlaceholder(Text.translatable("ifuto-replay.config.save_folder.placeholder"));
		folderField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.save_folder.tooltip")));
		folderField.setChangedListener(value -> this.config.saveFolder = value.trim());
		content.add(row(folderField, null));

		// 7.5行目: 音声
		content.add(row(
				cycle("ifuto-replay.config.audio_mode", List.of(AudioMode.available()),
						this.config.audioMode, mode -> Text.translatable(mode.translationKey()),
						"ifuto-replay.config.audio_mode.tooltip",
						value -> this.config.audioMode = value),
				slider("ifuto-replay.config.audio_bitrate", 32, 256, this.config.audioBitrateKbps,
						value -> value + " kbps",
						"ifuto-replay.config.audio_bitrate.tooltip",
						value -> this.config.audioBitrateKbps = value)));

		ModernTextField deviceField = new ModernTextField(this.textRenderer,
				0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.audio_device"));
		deviceField.setMaxLength(120);
		deviceField.setText(this.config.audioDevice);
		deviceField.setPlaceholder(Text.translatable("ifuto-replay.config.audio_device.placeholder"));
		deviceField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.audio_device.tooltip")));
		deviceField.setChangedListener(value -> this.config.audioDevice = value.trim());
		ModernButton detectButton = new ModernButton(0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.audio_device.detect"),
				button -> this.detectAudioDevice(deviceField), ModernButton.Style.NORMAL);
		detectButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.audio_device.detect.tooltip")));
		content.add(row(deviceField, detectButton));
		content.add(row(toggle("ifuto-replay.config.record_voice_chat", this.config.recordVoiceChat,
				"ifuto-replay.config.record_voice_chat.tooltip",
				value -> this.config.recordVoiceChat = value, this.widgetWidth * 2 + COLUMN_GAP), null));

		// 8行目: 書き出しの既定値（FPS / 解像度）
		String currentResolution = this.config.exportWidth + "x" + this.config.exportHeight;
		List<String> resolutions = new ArrayList<>();
		resolutions.add("1280x720");
		resolutions.add("1920x1080");
		resolutions.add("2560x1440");
		resolutions.add("3840x2160");

		if (!resolutions.contains(currentResolution)) {
			resolutions.add(currentResolution);
		}

		content.add(row(
				cycle("ifuto-replay.config.export_fps", List.of(24, 30, 50, 60, 120, 144, 240),
						this.config.exportFps,
						value -> Text.translatable("ifuto-replay.export.fps.value", value),
						"ifuto-replay.export.fps.tooltip",
						value -> this.config.exportFps = value),
				cycle("ifuto-replay.config.export_resolution", resolutions, currentResolution,
						Text::literal, "ifuto-replay.export.resolution.tooltip",
						value -> this.applyResolution(value))));

		// 9行目: ビットレート
		ModernTextField bitrateField = new ModernTextField(this.textRenderer, 0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.export_bitrate"));
		bitrateField.setMaxLength(9);
		bitrateField.setText(String.valueOf(this.config.exportBitrateKbps));
		bitrateField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.export.bitrate.tooltip")));
		bitrateField.setChangedListener(value -> {
			try {
				int parsed = Integer.parseInt(value.trim());

				if (parsed > 0) {
					this.config.exportBitrateKbps = parsed;
				}
			} catch (NumberFormatException ignored) {
				// 打ちかけの数字は無視する
			}
		});
		content.add(row(bitrateField, null));

		// 10行目: ffmpeg（全幅）
		ModernTextField ffmpegField = new ModernTextField(this.textRenderer,
				0, 0, this.widgetWidth * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.ffmpeg_path"));
		ffmpegField.setMaxLength(240);
		ffmpegField.setText(this.config.ffmpegPath);
		ffmpegField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.export.ffmpeg.tooltip")));
		ffmpegField.setChangedListener(value -> this.config.ffmpegPath = value.trim().isEmpty()
				? "ffmpeg" : value.trim());
		content.add(row(ffmpegField, null));

		this.scrollable = new ScrollableLayoutWidget(this.client, content, SCROLL_MIN_HEIGHT);
		this.scrollable.setWidth(this.widgetWidth * 2 + COLUMN_GAP + 8);
		body.add(this.scrollable);

		DirectionalLayoutWidget footer = this.layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP));
		footer.add(new ModernButton(0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.open_folder"), button -> openFolder(),
				ModernButton.Style.NORMAL));
		footer.add(new ModernButton(0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.reset"), button -> {
					this.config.resetToDefaults();
					this.config.save();
					this.clearAndInit();
				}, ModernButton.Style.DANGER));
		footer.add(new ModernButton(0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable("gui.done"), button -> this.close(), ModernButton.Style.PRIMARY));

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
	}

	// --- 部品を作る（同じ形を何度も書かないためのまとめ） ---

	private ModernToggle toggle(String labelKey, boolean initial, String tooltipKey,
			Consumer<Boolean> setter) {
		return toggle(labelKey, initial, tooltipKey, setter, this.widgetWidth);
	}

	private ModernToggle toggle(String labelKey, boolean initial, String tooltipKey,
			Consumer<Boolean> setter, int width) {
		ModernToggle toggle = new ModernToggle(0, 0, width, WIDGET_HEIGHT,
				Text.translatable(labelKey), initial, setter);
		toggle.setTooltip(Tooltip.of(Text.translatable(tooltipKey)));
		return toggle;
	}

	private <T> ModernCycling<T> cycle(String labelKey, List<T> values, T initial,
											  Function<T, Text> formatter, String tooltipKey,
											  Consumer<T> setter) {
		ModernCycling<T> cycling = new ModernCycling<>(0, 0, this.widgetWidth, WIDGET_HEIGHT,
				Text.translatable(labelKey), values, initial, formatter, setter);
		cycling.setTooltip(Tooltip.of(Text.translatable(tooltipKey)));
		return cycling;
	}

	private ModernSlider slider(String labelKey, int min, int max, int initial,
									   ModernSlider.ValueFormatter formatter, String tooltipKey,
									   IntConsumer setter) {
		ModernSlider slider = new ModernSlider(0, 0, this.widgetWidth, WIDGET_HEIGHT, labelKey, min, max, initial,
				formatter, setter);
		slider.setTooltip(Tooltip.of(Text.translatable(tooltipKey)));
		return slider;
	}

	/** クリップの長さとして選べる物（秒） */
	private static final List<Integer> CLIP_LENGTHS = List.of(15, 30, 60, 180, 300, 600, 1800, 3600, 7200, 14400);

	/** クリップの一時ファイルの上限として選べる物（MB。0 = 自動） */
	private static final List<Integer> CLIP_BUFFERS = List.of(0, 1024, 2048, 4096, 8192, 16384);

	/**
	 * 音声を取れる機器を探す。
	 *
	 * <p>ffmpeg（や pactl）を起動するので画面を止めないように別スレッドで探し、
	 * 見つかったら項目へ入れる。見つかった機器はツールチップに一覧で出しておく。
	 */
	private void detectAudioDevice(ModernTextField field) {
		field.setText(Text.translatable("ifuto-replay.config.audio_device.detecting").getString());
		MinecraftClient client = MinecraftClient.getInstance();
		String ffmpegPath = this.config.ffmpegPath;

		Thread thread = new Thread(() -> {
			List<String> devices = SystemAudioCapture.detectDevices(ffmpegPath);
			String best = SystemAudioCapture.autoDevice(ffmpegPath);

			client.execute(() -> {
				if (best == null) {
					field.setText("");
					field.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.audio_device.not_found")));
					return;
				}

				field.setText(best);
				StringBuilder list = new StringBuilder(best);

				for (int i = 0; i < devices.size() && i < 8; i++) {
					list.append("\n").append(devices.get(i));
				}

				field.setTooltip(Tooltip.of(Text.literal(list.toString())));
			});
		}, "ifuto-replay-audio-devices");

		thread.setDaemon(true);
		thread.start();
	}

	private void openFolder() {
		this.config.save();

		try {
			Util.getOperatingSystem().open(ReplayConfig.getSaveDirectory());
		} catch (Throwable t) {
			// 開けない環境でも設定画面が壊れないようにするだけ
		}
	}

	private void applyResolution(String value) {
		int separator = value.indexOf('x');

		if (separator <= 0) {
			return;
		}

		try {
			this.config.exportWidth = Math.max(16, Integer.parseInt(value.substring(0, separator)));
			this.config.exportHeight = Math.max(16, Integer.parseInt(value.substring(separator + 1)));
		} catch (NumberFormatException ignored) {
			// 変な値は無視する
		}
	}

	private static LayoutWidget row(ClickableWidget left, ClickableWidget right) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP);
		row.add(left);

		if (right != null) {
			row.add(right);
		}

		return row;
	}

	@Override
	protected void refreshWidgetPositions() {
		this.scrollable.setHeight(SCROLL_MIN_HEIGHT);
		this.layout.refreshPositions();
		// 余った分だけスクロール領域を伸ばす（バニラのワールド生成画面と同じやり方）
		int extra = this.height - this.layout.getFooterHeight() - this.scrollable.getNavigationFocus().getBottom();
		this.scrollable.setHeight(this.scrollable.getHeight() + extra);
	}

	/** うっすら暗くして、設定の一覧のうしろに丸い面を敷く */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.renderBackground(context, mouseX, mouseY, deltaTicks);
		ReplayTheme.veil(context, this.width, this.height);

		if (this.scrollable != null) {
			ScreenRect rect = this.scrollable.getNavigationFocus();
			int left = rect.getLeft() - 8;
			int top = rect.getTop() - 8;
			ReplayTheme.panel(context, left, top, rect.getRight() - rect.getLeft() + 16,
					rect.getBottom() - rect.getTop() + 16);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawText(this.textRenderer, "Made by Ifuto_mitai", 4, this.height - 12, 0xFF6B7784, false);
	}

	@Override
	public void close() {
		this.config.save();
		MinecraftClient.getInstance().setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.config.save();
		super.removed();
	}
}
