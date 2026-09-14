package com.ifuto.replay.gui;

import com.ifuto.replay.audio.AudioMode;
import com.ifuto.replay.audio.SystemAudioCapture;
import com.ifuto.replay.config.CompressionMode;
import com.ifuto.replay.config.IndicatorPosition;
import com.ifuto.replay.config.ReplayConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.LayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Mod Menu から開く設定画面。バニラのスクロールレイアウトを使っているので、ウィンドウの縦が短くても全部届く。
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

	public ReplayConfigScreen(Screen parent) {
		super(Text.translatable("ifuto-replay.config.title"));
		this.parent = parent;
		this.config = ReplayConfig.get();
	}

	@Override
	protected void init() {
		this.layout = new ThreePartsLayoutWidget(this);
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical());
		body.add(new TextWidget(Text.translatable("ifuto-replay.config.hint"), this.textRenderer),
				positioner -> positioner.marginBottom(6));

		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(ROW_SPACING);

		// 1行目: いつ録るか
		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.autoRecord)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.auto_record.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.auto_record"),
								(button, value) -> this.config.autoRecord = value),
				CyclingButtonWidget.onOffBuilder(this.config.recordClientPackets)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.record_client_packets.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.record_client_packets"),
								(button, value) -> this.config.recordClientPackets = value)));

		// 2行目: 軽さの調整
		content.add(row(
				CyclingButtonWidget.<CompressionMode>builder(CompressionMode::getText, this.config.compression)
						.values(CompressionMode.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.compression.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.compression"),
								(button, value) -> this.config.compression = value),
				CyclingButtonWidget.onOffBuilder(this.config.skipKeepAlive)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.skip_keep_alive.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.skip_keep_alive"),
								(button, value) -> this.config.skipKeepAlive = value)));

		// 3行目: 再生とプライバシー
		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.saveRegistries)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.save_registries.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.save_registries"),
								(button, value) -> this.config.saveRegistries = value),
				CyclingButtonWidget.onOffBuilder(this.config.maskServerAddress)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.mask_address.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.mask_address"),
								(button, value) -> this.config.maskServerAddress = value)));

		// 4行目: 自動停止
		content.add(row(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.max_file_size",
						0, 4096, this.config.maxFileSizeMb,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: value + " MB",
						value -> this.config.maxFileSizeMb = value)
						.tooltip("ifuto-replay.config.max_file_size.tooltip"),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.max_duration",
						0, 360, this.config.maxDurationMinutes,
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: value + " " + Text.translatable("ifuto-replay.config.minutes").getString(),
						value -> this.config.maxDurationMinutes = value)
						.tooltip("ifuto-replay.config.max_duration.tooltip")));

		// 4.5行目: メモリと容量の見張り
		content.add(row(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.memory",
						0, 128, Math.min(this.config.queuedMegaBytes, 128),
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.memory.auto").getString()
								: Text.translatable("ifuto-replay.config.memory.value", value).getString(),
						value -> this.config.queuedMegaBytes = value)
						.tooltip("ifuto-replay.config.memory.tooltip"),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.flush_interval",
						1, 10, Math.max(1, this.config.flushIntervalMs / 1000),
						value -> Text.translatable("ifuto-replay.config.flush_interval.value", value).getString(),
						value -> this.config.flushIntervalMs = value * 1000)
						.tooltip("ifuto-replay.config.flush_interval.tooltip")));

		content.add(row(
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.low_disk_space",
						0, 4096, Math.min(this.config.lowDiskSpaceMb, 4096),
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: Text.translatable("ifuto-replay.config.memory.value", value).getString(),
						value -> this.config.lowDiskSpaceMb = value)
						.tooltip("ifuto-replay.config.low_disk_space.tooltip"),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.critical_disk_space",
						0, 1024, Math.min(this.config.criticalDiskSpaceMb, 1024),
						value -> value <= 0
								? Text.translatable("ifuto-replay.config.unlimited").getString()
								: Text.translatable("ifuto-replay.config.memory.value", value).getString(),
						value -> this.config.criticalDiskSpaceMb = value)
						.tooltip("ifuto-replay.config.critical_disk_space.tooltip")));

		// 5行目: 見た目
		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.showIndicator)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.show_indicator.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.show_indicator"),
								(button, value) -> this.config.showIndicator = value),
				CyclingButtonWidget.<IndicatorPosition>builder(IndicatorPosition::getText, this.config.indicatorPosition)
						.values(IndicatorPosition.values())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.indicator_position.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.indicator_position"),
								(button, value) -> this.config.indicatorPosition = value)));

		// 6行目: お知らせ / 途中から録るときの世界の写し
		content.add(row(
				CyclingButtonWidget.onOffBuilder(this.config.notifyChat)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.notify_chat.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.notify_chat"),
								(button, value) -> this.config.notifyChat = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.snapshot_radius",
						0, 16, this.config.snapshotRadius,
						value -> {
							if (value <= 0) {
								return Text.translatable("ifuto-replay.config.snapshot_radius.off").getString();
							}

							return Text.translatable("ifuto-replay.config.snapshot_radius.value", value).getString();
						},
						value -> this.config.snapshotRadius = value)
						.tooltip("ifuto-replay.config.snapshot_radius.tooltip")));

		// 7行目: 保存先（全幅）
		TextFieldWidget folderField = new TextFieldWidget(this.textRenderer,
				0, 0, WIDGET_WIDTH * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.save_folder"));
		folderField.setMaxLength(120);
		folderField.setText(this.config.saveFolder);
		folderField.setPlaceholder(Text.translatable("ifuto-replay.config.save_folder.placeholder"));
		folderField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.save_folder.tooltip")));
		folderField.setChangedListener(value -> this.config.saveFolder = value.trim());
		content.add(row(folderField, null));

		// 7.5行目: 音声
		content.add(row(
				CyclingButtonWidget.<AudioMode>builder(mode -> Text.translatable(mode.translationKey()),
								this.config.audioMode)
						.values(AudioMode.available())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.config.audio_mode.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
								Text.translatable("ifuto-replay.config.audio_mode"),
								(button, value) -> this.config.audioMode = value),
				new OptionSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.config.audio_bitrate",
						32, 256, this.config.audioBitrateKbps,
						value -> value + " kbps",
						value -> this.config.audioBitrateKbps = value)
						.tooltip("ifuto-replay.config.audio_bitrate.tooltip")));

		TextFieldWidget deviceField = new TextFieldWidget(this.textRenderer,
				0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.audio_device"));
		deviceField.setMaxLength(120);
		deviceField.setText(this.config.audioDevice);
		deviceField.setPlaceholder(Text.translatable("ifuto-replay.config.audio_device.placeholder"));
		deviceField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.audio_device.tooltip")));
		deviceField.setChangedListener(value -> this.config.audioDevice = value.trim());
		ButtonWidget detectButton = ButtonWidget.builder(
						Text.translatable("ifuto-replay.config.audio_device.detect"),
						button -> this.detectAudioDevice(deviceField))
				.width(WIDGET_WIDTH)
				.build();
		detectButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.config.audio_device.detect.tooltip")));
		content.add(row(deviceField, detectButton));

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
				CyclingButtonWidget.<Integer>builder(
								value -> Text.translatable("ifuto-replay.export.fps.value", value), this.config.exportFps)
						.values(24, 30, 50, 60, 120, 144, 240)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.export.fps.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.export_fps"),
								(button, value) -> this.config.exportFps = value),
				CyclingButtonWidget.<String>builder(Text::literal, currentResolution)
						.values(resolutions.toArray(new String[0]))
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.export.resolution.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.config.export_resolution"),
								(button, value) -> this.applyResolution(value))));

		// 9行目: ビットレート
		TextFieldWidget bitrateField = new TextFieldWidget(this.textRenderer, 0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
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
		TextFieldWidget ffmpegField = new TextFieldWidget(this.textRenderer,
				0, 0, WIDGET_WIDTH * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.config.ffmpeg_path"));
		ffmpegField.setMaxLength(240);
		ffmpegField.setText(this.config.ffmpegPath);
		ffmpegField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.export.ffmpeg.tooltip")));
		ffmpegField.setChangedListener(value -> this.config.ffmpegPath = value.trim().isEmpty()
				? "ffmpeg" : value.trim());
		content.add(row(ffmpegField, null));

		this.scrollable = new ScrollableLayoutWidget(this.client, content, SCROLL_MIN_HEIGHT);
		this.scrollable.setWidth(WIDGET_WIDTH * 2 + COLUMN_GAP + 8);
		body.add(this.scrollable);

		DirectionalLayoutWidget footer = this.layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP));
		footer.add(ButtonWidget.builder(Text.translatable("ifuto-replay.config.open_folder"), button -> openFolder())
				.width(WIDGET_WIDTH).build());
		footer.add(ButtonWidget.builder(Text.translatable("ifuto-replay.config.reset"), button -> {
			this.config.resetToDefaults();
			this.config.save();
			this.clearAndInit();
		}).width(WIDGET_WIDTH).build());
		footer.add(ButtonWidget.builder(Text.translatable("gui.done"), button -> this.close())
				.width(WIDGET_WIDTH).build());

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
	}

	/**
	 * 音声を取れる機器を探す。
	 *
	 * <p>ffmpeg（や pactl）を起動するので画面を止めないように別スレッドで探し、
	 * 見つかったら項目へ入れる。見つかった機器はツールチップに一覧で出しておく。
	 */
	private void detectAudioDevice(TextFieldWidget field) {
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

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawTextWithShadow(this.textRenderer, "Made by Ifuto_mitai", 4, this.height - 12, 0xFF808080);
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

	/** 0-1 の位置を整数の範囲に変換するだけのスライダー */
	private static class OptionSlider extends SliderWidget {
		private final String labelKey;
		private final int min;
		private final int max;
		private final ValueFormatter formatter;
		private final IntConsumer setter;

		OptionSlider(int x, int y, int width, int height, String labelKey, int min, int max, int initialValue,
					 ValueFormatter formatter, IntConsumer setter) {
			super(x, y, width, height, Text.empty(), (double) (initialValue - min) / (double) (max - min));
			this.labelKey = labelKey;
			this.min = min;
			this.max = max;
			this.formatter = formatter;
			this.setter = setter;
			this.updateMessage();
		}

		OptionSlider tooltip(String key) {
			this.setTooltip(Tooltip.of(Text.translatable(key)));
			return this;
		}

		private int intValue() {
			return this.min + (int) Math.round((this.max - this.min) * this.value);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Text.translatable(this.labelKey, Text.literal(this.formatter.format(this.intValue()))));
		}

		@Override
		protected void applyValue() {
			this.setter.accept(this.intValue());
		}
	}

	@FunctionalInterface
	private interface ValueFormatter {
		String format(int value);
	}
}
