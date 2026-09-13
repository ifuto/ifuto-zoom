package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.recording.RecordingManager;
import com.ifuto.replay.recording.ReplayFileReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.LayoutWidget;
import net.minecraft.client.gui.widget.ScrollableLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * 保存済みの録画の一覧。
 *
 * <p>読むのはヘッダと巻末だけなので、本体が何GBあっても一覧はすぐ出る。
 */
@Environment(EnvType.CLIENT)
public class RecordingListScreen extends Screen {
	private static final int LIST_WIDTH = 320;
	private static final int LABEL_WIDTH = 176;
	private static final int SMALL_BUTTON_WIDTH = 62;
	private static final int BUTTON_HEIGHT = 20;
	private static final int COLUMN_GAP = 6;
	private static final int SCROLL_MIN_HEIGHT = 140;

	/** 「削除」の二段階確認を何ミリ秒で解除するか */
	private static final long CONFIRM_TIMEOUT_MS = 4000L;

	private final Screen parent;
	private ScrollableLayoutWidget scrollable;
	private ThreePartsLayoutWidget layout;

	private ButtonWidget armedDeleteButton;
	private long armedUntil;

	public RecordingListScreen(Screen parent) {
		super(Text.translatable("ifuto-replay.list.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.layout = new ThreePartsLayoutWidget(this);
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical());
		body.add(new TextWidget(Text.translatable("ifuto-replay.list.hint"), this.textRenderer),
				positioner -> positioner.marginBottom(6));

		DirectionalLayoutWidget content = DirectionalLayoutWidget.vertical().spacing(4);
		List<ReplayFileReader.Info> recordings = ReplayFileReader.listRecordings();

		if (recordings.isEmpty()) {
			content.add(new TextWidget(Text.translatable("ifuto-replay.list.empty"), this.textRenderer));
		} else {
			for (ReplayFileReader.Info info : recordings) {
				content.add(this.row(info));
			}
		}

		this.scrollable = new ScrollableLayoutWidget(this.client, content, SCROLL_MIN_HEIGHT);
		this.scrollable.setWidth(LIST_WIDTH);
		body.add(this.scrollable);

		DirectionalLayoutWidget footer = this.layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP));
		footer.add(ButtonWidget.builder(Text.translatable("ifuto-replay.list.refresh"),
				button -> this.clearAndInit()).width(SMALL_BUTTON_WIDTH).build());
		footer.add(ButtonWidget.builder(Text.translatable("ifuto-replay.config.open_folder"),
				button -> this.openFolder()).width(SMALL_BUTTON_WIDTH).build());
		footer.add(ButtonWidget.builder(Text.translatable("gui.done"), button -> this.close())
				.width(SMALL_BUTTON_WIDTH).build());

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
	}

	private LayoutWidget row(ReplayFileReader.Info info) {
		DirectionalLayoutWidget row = DirectionalLayoutWidget.horizontal().spacing(COLUMN_GAP);

		DirectionalLayoutWidget labels = DirectionalLayoutWidget.vertical().spacing(1);
		TextWidget when = new TextWidget(Text.literal(formatDateTime(info.startedAt())), this.textRenderer);
		TextWidget details = new TextWidget(Text.literal(describe(info)), this.textRenderer);
		when.setMaxWidth(LABEL_WIDTH);
		details.setMaxWidth(LABEL_WIDTH);
		details.setTooltip(Tooltip.of(Text.literal(info.fileName()
				+ "\n" + info.mcVersion()
				+ "\n" + Text.translatable("ifuto-replay.list.player", info.playerName()).getString())));
		labels.add(when);
		labels.add(details);

		ButtonWidget deleteButton = ButtonWidget.builder(Text.translatable("ifuto-replay.list.delete"),
						button -> this.onDelete(button, info))
				.width(SMALL_BUTTON_WIDTH).build();
		deleteButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.list.delete.tooltip")));

		// 再生・書き出しは次の段階。置き場所を見せておくだけ
		ButtonWidget playButton = ButtonWidget.builder(Text.translatable("ifuto-replay.list.play"), button -> {
		}).width(SMALL_BUTTON_WIDTH).build();
		playButton.active = false;
		playButton.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.list.play.tooltip")));

		row.add(labels);
		row.add(deleteButton);
		row.add(playButton);
		return row;
	}

	private void onDelete(ButtonWidget button, ReplayFileReader.Info info) {
		long now = System.currentTimeMillis();

		if (this.armedDeleteButton == button && now < this.armedUntil) {
			this.armedDeleteButton = null;

			try {
				Files.delete(info.file());
			} catch (IOException e) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] {} を消せませんでした", info.file(), e);
			}

			this.clearAndInit();
			return;
		}

		this.armedDeleteButton = button;
		this.armedUntil = now + CONFIRM_TIMEOUT_MS;
		button.setMessage(Text.translatable("ifuto-replay.list.delete_confirm"));
	}

	private void openFolder() {
		try {
			Util.getOperatingSystem().open(ReplayConfig.getSaveDirectory());
		} catch (Throwable t) {
			// 開けない環境でも画面が壊れないようにするだけ
		}
	}

	@Override
	protected void refreshWidgetPositions() {
		this.scrollable.setHeight(SCROLL_MIN_HEIGHT);
		this.layout.refreshPositions();
		int extra = this.height - this.layout.getFooterHeight() - this.scrollable.getNavigationFocus().getBottom();
		this.scrollable.setHeight(this.scrollable.getHeight() + extra);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		// 二段階確認は時間で戻す
		ButtonWidget armed = this.armedDeleteButton;

		if (armed != null && System.currentTimeMillis() > this.armedUntil) {
			armed.setMessage(Text.translatable("ifuto-replay.list.delete"));
			this.armedDeleteButton = null;
		}
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(this.parent);
	}

	// --- 表示用の文字列 ---

	private static String describe(ReplayFileReader.Info info) {
		String time = info.hasDuration()
				? RecordingManager.formatDuration(info.durationMs())
				: "?";
		String size = RecordingManager.formatSize(info.fileSize());
		String server = info.serverName() == null || info.serverName().isBlank() ? "-" : info.serverName();

		return truncate(time + "  " + size + "  " + server, 34);
	}

	private static String formatDateTime(long epochMillis) {
		LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMillis(epochMillis),
				ZoneId.systemDefault());

		return String.format(Locale.ROOT, "%04d/%02d/%02d %02d:%02d",
				time.getYear(), time.getMonthValue(), time.getDayOfMonth(), time.getHour(), time.getMinute());
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + "…";
	}
}
