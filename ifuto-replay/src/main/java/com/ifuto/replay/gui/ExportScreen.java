package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.export.ExportOptions;
import com.ifuto.replay.export.ReplayExporter;
import com.ifuto.replay.playback.ReplayPlayback;
import com.ifuto.replay.recording.ReplayFileReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 書き出しの設定画面。
 *
 * <p>「録画には時刻の入ったパケットしか無い」ので、FPS も解像度もビットレートも
 * あとからいくらでも変えられる（画面録画と違って実時間に縛られない）。
 */
@Environment(EnvType.CLIENT)
public class ExportScreen extends Screen {
	private static final int WIDGET_WIDTH = 158;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 8;
	private static final int ROW_SPACING = 4;
	private static final int SCROLL_MIN_HEIGHT = 120;
	private static final String RESOLUTION_SCREEN = "screen";
	private static final Integer[] FPS_VALUES = {24, 30, 50, 60, 120, 144, 240};

	private final Screen parent;
	private final ReplayFileReader.Info info;
	private final long durationMs;
	private final int durationSec;
	private final ReplayConfig config;

	private ScrollableLayoutWidget scrollable;
	private ThreePartsLayoutWidget layout;
	private TextFieldWidget widthField;
	private TextFieldWidget heightField;
	private TextFieldWidget bitrateField;
	private TextFieldWidget ffmpegField;
	private TextFieldWidget nameField;
	private TextWidget summaryText;
	private int startSec;
	private int endSec;
	private int fps;

	public ExportScreen(Screen parent, ReplayFileReader.Info info, ReplayConfig config) {
		super(Text.translatable("ifuto-replay.export.title"));
		this.parent = parent;
		this.info = info;
		this.config = config;
		this.durationMs = info.hasDuration() ? Math.max(0L, info.durationMs()) : 0L;
		this.durationSec = (int) Math.max(0L, this.durationMs / 1000L);
		this.startSec = 0;
		this.endSec = this.durationSec;
		this.fps = config.exportFps;
	}

	@Override
	protected void init() {
		this.layout = new ThreePartsLayoutWidget(this);
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical());
		body.add(new TextWidget(Text.translatable("ifuto-replay.export.hint",
						Text.literal(ReplayConfig.get().displayAddress(this.info.serverName()))), this.textRenderer),
				positioner -> positioner.marginBottom(6));

		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(ROW_SPACING);

		// 1行目: FPS と解像度のプリセット
		content.add(row(
				CyclingButtonWidget.<Integer>builder(value -> Text.translatable("ifuto-replay.export.fps.value", value), this.fps)
						.values(FPS_VALUES)
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.export.fps.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.export.fps"),
								(button, value) -> {
									this.fps = value;
									this.updateSummary();
								}),
				CyclingButtonWidget.<String>builder(this::resolutionText, this.initialResolution())
						.values(this.resolutionValues())
						.tooltip(value -> Tooltip.of(Text.translatable("ifuto-replay.export.resolution.tooltip")))
						.build(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Text.translatable("ifuto-replay.export.resolution"),
								(button, value) -> this.applyResolution(value))));

		// 2行目: 幅・高さ（プリセットを選んだあとでも書き換えられる）
		this.widthField = new TextFieldWidget(this.textRenderer, 0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.export.width"));
		this.widthField.setText(String.valueOf(this.config.exportWidth));
		this.widthField.setChangedListener(text -> this.updateSummary());
		this.heightField = new TextFieldWidget(this.textRenderer, 0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.export.height"));
		this.heightField.setText(String.valueOf(this.config.exportHeight));
		this.heightField.setChangedListener(text -> this.updateSummary());
		content.add(row(this.widthField, this.heightField));

		// 3行目: 開始・終了（秒）
		if (this.durationSec > 0) {
			content.add(row(
					new TimeSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.export.start",
							0, this.durationSec, this.startSec, value -> {
								this.startSec = value;
								this.updateSummary();
							})
							.tooltip("ifuto-replay.export.start.tooltip"),
					new TimeSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, "ifuto-replay.export.end",
							0, this.durationSec, this.endSec, value -> {
								this.endSec = value;
								this.updateSummary();
							})
							.tooltip("ifuto-replay.export.end.tooltip")));
		}

		// 4行目: ビットレートと ffmpeg
		this.bitrateField = new TextFieldWidget(this.textRenderer, 0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.export.bitrate"));
		this.bitrateField.setText(String.valueOf(this.config.exportBitrateKbps));
		this.bitrateField.setChangedListener(text -> this.updateSummary());
		this.ffmpegField = new TextFieldWidget(this.textRenderer, 0, 0, WIDGET_WIDTH, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.export.ffmpeg"));
		this.ffmpegField.setText(this.config.ffmpegPath);
		this.ffmpegField.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.export.ffmpeg.tooltip")));
		content.add(row(this.bitrateField, this.ffmpegField));

		// 5行目: ファイル名
		this.nameField = new TextFieldWidget(this.textRenderer, 0, 0, WIDGET_WIDTH * 2 + COLUMN_GAP, WIDGET_HEIGHT,
				Text.translatable("ifuto-replay.export.name"));
		this.nameField.setText(this.defaultFileName());
		content.add(row(this.nameField, null));

		this.summaryText = new TextWidget(Text.empty(), this.textRenderer);
		this.summaryText.setMaxWidth(WIDGET_WIDTH * 2 + COLUMN_GAP);
		content.add(this.summaryText);

		this.scrollable = new ScrollableLayoutWidget(this.client, content, SCROLL_MIN_HEIGHT);
		body.add(this.scrollable);

		DirectionalLayoutWidget footer = this.layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP));
		footer.add(ButtonWidget.builder(Text.translatable("ifuto-replay.export.begin"), button -> this.beginExport())
				.width(WIDGET_WIDTH).build());
		footer.add(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> this.close())
				.width(WIDGET_WIDTH).build());

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
		this.updateSummary();
	}

	// --- 設定の組み立て ---

	private String defaultFileName() {
		String name = this.info.fileName();

		if (name.endsWith(".ifreplay")) {
			name = name.substring(0, name.length() - ".ifreplay".length());
		}

		return name + ".mp4";
	}

	private String initialResolution() {
		String current = this.config.exportWidth + "x" + this.config.exportHeight;

		for (String value : new String[] {"1280x720", "1920x1080", "2560x1440", "3840x2160"}) {
			if (value.equals(current)) {
				return value;
			}
		}

		return current;
	}

	private String[] resolutionValues() {
		List<String> values = new ArrayList<>();
		values.add("1280x720");
		values.add("1920x1080");
		values.add("2560x1440");
		values.add("3840x2160");
		values.add(RESOLUTION_SCREEN);

		String current = this.initialResolution();

		if (!values.contains(current)) {
			values.add(current);
		}

		return values.toArray(new String[0]);
	}

	private Text resolutionText(String value) {
		if (RESOLUTION_SCREEN.equals(value)) {
			return Text.translatable("ifuto-replay.export.resolution.screen");
		}

		return Text.literal(value);
	}

	private void applyResolution(String value) {
		if (RESOLUTION_SCREEN.equals(value)) {
			this.widthField.setText(String.valueOf(this.client.getWindow().getFramebufferWidth()));
			this.heightField.setText(String.valueOf(this.client.getWindow().getFramebufferHeight()));
		} else {
			int separator = value.indexOf('x');

			if (separator > 0) {
				this.widthField.setText(value.substring(0, separator));
				this.heightField.setText(value.substring(separator + 1));
			}
		}

		this.updateSummary();
	}

	private void updateSummary() {
		if (this.summaryText == null) {
			return;
		}

		int width = this.intValue(this.widthField, this.config.exportWidth);
		int height = this.intValue(this.heightField, this.config.exportHeight);
		long startMs = this.startSec * 1000L;
		long endMs = this.endSec * 1000L;

		if (endMs <= startMs) {
			endMs = this.durationMs;
		}

		long frames = Math.max(1L, Math.round((endMs - startMs) / 1000.0 * this.fps));
		this.summaryText.setMessage(Text.translatable("ifuto-replay.export.summary",
				Text.literal(width + "x" + height),
				Text.literal(formatDuration(startMs)),
				Text.literal(formatDuration(endMs)),
				Text.literal(String.valueOf(frames))));
	}

	private static int intValue(TextFieldWidget field, int fallback) {
		try {
			int value = Integer.parseInt(field.getText().trim());
			return value > 0 ? value : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** 秒を m:ss / h:mm:ss にする（一覧と同じ見た目） */
	private static String formatDuration(long ms) {
		long totalSeconds = Math.max(0L, ms / 1000L);
		long hours = totalSeconds / 3600L;
		long minutes = totalSeconds % 3600L / 60L;
		long seconds = totalSeconds % 60L;

		if (hours > 0L) {
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}

		return String.format("%d:%02d", minutes, seconds);
	}

	private void beginExport() {
		ReplayConfig config = ReplayConfig.get();
		config.exportFps = Math.max(1, this.fps);
		// yuv420p は縦横が偶数でないと ffmpeg に怒られるので、ここで偶数に丸める
		config.exportWidth = Math.max(16, this.intValue(this.widthField, this.config.exportWidth) / 2 * 2);
		config.exportHeight = Math.max(16, this.intValue(this.heightField, this.config.exportHeight) / 2 * 2);
		config.exportBitrateKbps = Math.max(100, this.intValue(this.bitrateField, this.config.exportBitrateKbps));
		config.ffmpegPath = this.ffmpegField.getText().trim().isEmpty()
				? "ffmpeg" : this.ffmpegField.getText().trim();
		config.save();

		long startMs = this.startSec * 1000L;
		long endMs = this.endSec * 1000L;

		if (endMs <= startMs || endMs > this.durationMs) {
			startMs = 0L;
			endMs = this.durationMs;
		}

		Path output = ExportOptions.uniqueOutput(ReplayConfig.getSaveDirectory().resolve("exports")
				.resolve(this.fileName()));
		ExportOptions options = new ExportOptions(config.exportFps, config.exportWidth, config.exportHeight,
				config.exportBitrateKbps, config.ffmpegPath, startMs, endMs, output);

		startExport(this.client, this.info.file(), options, this.parent);
	}

	private String fileName() {
		String name = this.nameField.getText().trim();

		if (name.isEmpty()) {
			name = this.defaultFileName();
		}

		// 変な文字で別のフォルダに書かないように、ファイル名だけ使う
		name = name.replace('\\', '_').replace('/', '_').replace(':', '_');

		if (!name.endsWith(".mp4")) {
			name = name + ".mp4";
		}

		return name;
	}

	/**
	 * 再生を始めて、その絵を ffmpeg に流しはじめる。
	 *
	 * <p>書き出し中は実時間を気にしなくていいので、1フレーム描くたびに時刻を 1/FPS 秒だけ進める。
	 */
	public static void startExport(MinecraftClient client, Path file, ExportOptions options, Screen parent) {
		if (client.world != null || client.getNetworkHandler() != null) {
			client.disconnect(new BlankScreen(), false, true);
		}

		try {
			ReplayPlayback playback = ReplayPlayback.start(client, file);
			ReplayExporter exporter = new ReplayExporter(client, playback, options);
			exporter.start();
			client.setScreen(new ExportProgressScreen(exporter, playback, parent));
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 書き出しを始められませんでした", e);
			Screen back = parent == null ? new RecordingListScreen(null) : new RecordingListScreen(parent);
			client.setScreen(new NoticeScreen(back,
					Text.translatable("ifuto-replay.export.error_title"),
					Text.translatable("ifuto-replay.export.error_start",
							Text.literal(e.getMessage() == null ? e.toString() : e.getMessage()))));
		}
	}

	// --- 画面の部品 ---

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
		int extra = this.height - this.layout.getFooterHeight() - this.scrollable.getNavigationFocus().getBottom();
		this.scrollable.setHeight(this.scrollable.getHeight() + extra);
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(this.parent);
	}

	/** 秒を選ぶだけのスライダー */
	private static class TimeSlider extends SliderWidget {
		private final String labelKey;
		private final int min;
		private final int max;
		private final java.util.function.IntConsumer setter;

		TimeSlider(int x, int y, int width, int height, String labelKey, int min, int max, int initialValue,
				java.util.function.IntConsumer setter) {
			super(x, y, width, height, Text.empty(),
					max == min ? 0.0 : (double) (initialValue - min) / (double) (max - min));
			this.labelKey = labelKey;
			this.min = min;
			this.max = max;
			this.setter = setter;
			this.updateMessage();
		}

		TimeSlider tooltip(String key) {
			this.setTooltip(Tooltip.of(Text.translatable(key)));
			return this;
		}

		@Override
		protected void updateMessage() {
			long seconds = this.min + Math.round((this.max - this.min) * this.value);
			this.setMessage(Text.translatable(this.labelKey, Text.literal(formatDuration(seconds * 1000L))));
		}

		@Override
		protected void applyValue() {
			this.setter.accept((int) Math.round(this.min + (this.max - this.min) * this.value));
		}
	}
}
