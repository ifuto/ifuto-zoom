package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
import com.ifuto.replay.playback.ReplayPlayback;
import com.ifuto.replay.playback.ReplayStream;
import com.ifuto.replay.recording.ClipRemux;
import com.ifuto.replay.recording.ReplayFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * クリップの編集画面。見ながら範囲を決め、再レンダなしで切り出す。
 *
 * <p>キー操作は持たず、全部ボタン。背景を暗くしないので、うしろで世界がそのまま動いている。
 * 範囲の決め方は2つ。「I 始点」「O 終点」で区間を決め、「残す」で足すか「カット」で抜く。
 */
public class ClipEditorScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int TIMELINE_HEIGHT = 18;
	private static final int HEADER_LINES = 2;
	private static final int LINE_HEIGHT = 12;
	private static final int MAX_RANGE_ROWS = 3;

	private final ReplayPlayback playback;
	private final List<ClipRemux.Range> keep = new ArrayList<>();
	private long inMs;
	private long outMs;

	private ModernButton playPause;
	private ModernButton speedButton;

	public ClipEditorScreen(ReplayPlayback playback) {
		this(playback, null, 0L, 0L);
	}

	public ClipEditorScreen(ReplayPlayback playback, List<ClipRemux.Range> keep, long inMs, long outMs) {
		super(Text.translatable("ifuto-replay.editor.title"));
		this.playback = playback;
		this.playback.setRestartHandler(this::restartAt);

		if (keep == null || keep.isEmpty()) {
			this.keep.add(new ClipRemux.Range(0L, playback.durationMs()));
		} else {
			this.keep.addAll(ClipRemux.normalize(keep, playback.durationMs()));
		}

		this.inMs = Math.max(0L, inMs);
		this.outMs = outMs > 0L ? outMs : playback.durationMs();
	}

	/** 作業用（プレビュー・進行画面から読む） */
	ReplayPlayback playback() {
		return this.playback;
	}

	List<ClipRemux.Range> keepRanges() {
		return this.keep;
	}

	long inMs() {
		return this.inMs;
	}

	long outMs() {
		return this.outMs;
	}

	Path sourceFile() {
		return this.playback.file();
	}

	// --- 画面 ---

	@Override
	protected void init() {
		int barWidth = Math.min(this.width - 40, 560);
		int left = this.width / 2 - barWidth / 2;
		int bottom = this.height - 8;
		int footerY = bottom - ROW_HEIGHT;
		int rangesY = footerY - GAP - MAX_RANGE_ROWS * ROW_HEIGHT - (MAX_RANGE_ROWS - 1) * GAP;
		int editY = rangesY - GAP - ROW_HEIGHT;
		int transportY = editY - GAP - ROW_HEIGHT;
		int timelineY = transportY - GAP - TIMELINE_HEIGHT;

		this.addDrawableChild(new EditTimelineWidget(left, timelineY, barWidth, TIMELINE_HEIGHT, this));

		Row transport = new Row(left, transportY, left + barWidth);

		transport.add(Text.literal("⏮"), 26, button -> this.restartAt(0L), ModernButton.Style.NORMAL)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.restart")));

		this.playPause = transport.add(this.playPauseText(), 40, button -> {
			this.playback.setPaused(!this.playback.isPaused());
			this.playPause.setMessage(this.playPauseText());
		}, ModernButton.Style.PRIMARY);

		this.speedButton = transport.add(this.speedText(), 62, button -> {
			this.playback.cycleSpeed();
			this.speedButton.setMessage(this.speedText());
		}, ModernButton.Style.NORMAL);

		transport.add(Text.literal("⚑ ◀"), 52, button -> this.jumpMarker(false), ModernButton.Style.NORMAL)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.marker_prev")));

		transport.add(Text.literal("⚑ ▶"), 52, button -> this.jumpMarker(true), ModernButton.Style.NORMAL)
				.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.preview.marker_next")));

		Row edit = new Row(left, editY, left + barWidth);

		edit.add(Text.translatable("ifuto-replay.editor.in"), 72, button -> {
			this.inMs = this.playback.timeMs();
			this.clearAndInit();
		}, ModernButton.Style.NORMAL);

		edit.add(Text.translatable("ifuto-replay.editor.out"), 72, button -> {
			this.outMs = this.playback.timeMs();
			this.clearAndInit();
		}, ModernButton.Style.NORMAL);

		edit.add(Text.translatable("ifuto-replay.editor.add_keep"), 72, button -> {
			this.keep.clear();
			this.keep.addAll(ClipRemux.normalize(
					append(this.keep, new ClipRemux.Range(this.inMs, this.outMs)), this.playback.durationMs()));
			this.clearAndInit();
		}, ModernButton.Style.NORMAL);

		edit.add(Text.translatable("ifuto-replay.editor.cut"), 72, button -> this.cutInterval(),
				ModernButton.Style.NORMAL);

		edit.add(Text.translatable("ifuto-replay.editor.reset"), 72, button -> {
			this.keep.clear();
			this.keep.add(new ClipRemux.Range(0L, this.playback.durationMs()));
			this.clearAndInit();
		}, ModernButton.Style.NORMAL);

		// 範囲の一覧（消すボタンだけ置き、文字は下で描く。溢れたら最終行は「他 N 件」）
		int rows = Math.min(this.keep.size(), MAX_RANGE_ROWS);

		if (this.keep.size() > MAX_RANGE_ROWS) {
			rows = MAX_RANGE_ROWS - 1;
		}

		for (int i = 0; i < rows; i++) {
			int index = i;
			int rowY = rangesY + i * (ROW_HEIGHT + GAP);
			ModernButton delete = new ModernButton(left + barWidth - 30, rowY, 30, ROW_HEIGHT,
					Text.literal("×"), button -> this.deleteRange(index), ModernButton.Style.DANGER);
			delete.setTooltip(Tooltip.of(Text.translatable("ifuto-replay.editor.delete_range")));
			this.addDrawableChild(delete);
		}

		Row footer = new Row(left, footerY, left + barWidth);

		footer.add(Text.translatable("ifuto-replay.editor.output"), 200, button -> this.startOutput(),
				ModernButton.Style.PRIMARY);

		footer.add(Text.translatable("ifuto-replay.editor.output_mp4"), 160, button -> this.startMp4Output(),
				ModernButton.Style.NORMAL);

		footer.add(Text.literal("✕"), 26, button -> this.close(), ModernButton.Style.DANGER)
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
		int bottom = this.height - 8;
		int footerY = bottom - ROW_HEIGHT;
		int rangesY = footerY - GAP - MAX_RANGE_ROWS * ROW_HEIGHT - (MAX_RANGE_ROWS - 1) * GAP;
		int editY = rangesY - GAP - ROW_HEIGHT;
		int transportY = editY - GAP - ROW_HEIGHT;
		int timelineY = transportY - GAP - TIMELINE_HEIGHT;
		int headerY = timelineY - GAP - HEADER_LINES * LINE_HEIGHT - LINE_HEIGHT;

		ReplayTheme.panel(context, left - 6, headerY - 4, barWidth + 12, this.height - 2 - (headerY - 4));

		// 1行目: 時刻 / 長さ、残り
		String time = timeText(this.playback.timeMs()) + " / " + timeText(this.playback.durationMs());
		context.drawText(this.textRenderer, time, left, headerY, 0xFFFFFF, false);

		String kept = Text.translatable("ifuto-replay.editor.kept",
				Text.literal(timeText(this.keptTotalMs())), this.keep.size()).getString();
		int keptWidth = this.textRenderer.getWidth(kept);
		context.drawText(this.textRenderer, kept, left + barWidth - keptWidth, headerY, 0x38BDF8, false);

		// 2行目: 作業中の区間
		long from = Math.min(this.inMs, this.outMs);
		long to = Math.max(this.inMs, this.outMs);
		String interval = Text.translatable("ifuto-replay.editor.interval",
				Text.literal(timeText(from)), Text.literal(timeText(to))).getString();
		context.drawText(this.textRenderer, interval, left, headerY + LINE_HEIGHT, 0xAAAAAA, false);

		// 3行目: つなぎ目の注意（真ん中を抜いたときだけ）
		if (this.hasSplice()) {
			String note = Text.translatable("ifuto-replay.editor.splice_note").getString();
			context.drawText(this.textRenderer, note, left, headerY + LINE_HEIGHT * 2, 0xFFEB3B, false);
		}

		// 範囲の一覧
		if (this.keep.isEmpty()) {
			String empty = Text.translatable("ifuto-replay.editor.no_ranges").getString();
			context.drawText(this.textRenderer, empty, left, rangesY + 6, 0xF43F5E, false);
			return;
		}

		int shown = Math.min(this.keep.size(), MAX_RANGE_ROWS);
		boolean overflow = this.keep.size() > MAX_RANGE_ROWS;

		if (overflow) {
			shown = MAX_RANGE_ROWS - 1;
		}

		for (int i = 0; i < shown; i++) {
			ClipRemux.Range range = this.keep.get(i);
			String row = (i + 1) + "  " + timeText(range.startMs()) + "–" + timeText(range.endMs());
			context.drawText(this.textRenderer, row, left, rangesY + i * (ROW_HEIGHT + GAP) + 6, 0xE6EDF3, false);
		}

		if (overflow) {
			String more = Text.translatable("ifuto-replay.editor.more_ranges",
					this.keep.size() - shown).getString();
			context.drawText(this.textRenderer, more, left,
					rangesY + shown * (ROW_HEIGHT + GAP) + 6, 0x8B98A5, false);
		}
	}

	// --- ボタンの中身 ---

	private void cutInterval() {
		long from = Math.min(this.inMs, this.outMs);
		long to = Math.max(this.inMs, this.outMs);
		List<ClipRemux.Range> cut = ClipRemux.normalize(
				ClipRemux.subtract(this.keep, new ClipRemux.Range(from, to)), this.playback.durationMs());

		if (cut.isEmpty()) {
			this.client.setScreen(new NoticeScreen(this,
					Text.translatable("ifuto-replay.editor.empty_title"),
					Text.translatable("ifuto-replay.editor.empty_message")));
			return;
		}

		this.keep.clear();
		this.keep.addAll(cut);
		this.clearAndInit();
	}

	private void deleteRange(int index) {
		if (this.keep.size() <= 1) {
			this.client.setScreen(new NoticeScreen(this,
					Text.translatable("ifuto-replay.editor.empty_title"),
					Text.translatable("ifuto-replay.editor.empty_message")));
			return;
		}

		if (index >= 0 && index < this.keep.size()) {
			this.keep.remove(index);
			this.clearAndInit();
		}
	}

	private void startOutput() {
		this.beginOutput(false);
	}

	private void startMp4Output() {
		this.beginOutput(true);
	}

	private void beginOutput(boolean thenExport) {
		if (this.keep.isEmpty()) {
			this.client.setScreen(new NoticeScreen(this,
					Text.translatable("ifuto-replay.editor.empty_title"),
					Text.translatable("ifuto-replay.editor.empty_message")));
			return;
		}

		// 空きが無ければ先に諦める（出力は元と同程度の大きさになる）
		try {
			long usable = Files.getFileStore(this.playback.file()).getUsableSpace();
			long need = Files.size(this.playback.file()) + (256L << 20);

			if (usable < need) {
				this.client.setScreen(new NoticeScreen(this,
						Text.translatable("ifuto-replay.editor.nospace_title"),
						Text.translatable("ifuto-replay.editor.nospace_message")));
				return;
			}
		} catch (IOException ignored) {
			// 取れなくても続ける（書きながら気付く）
		}

		// 出しているあいだは止める（裏で動かし続けても意味がないので）
		this.playback.setPaused(true);
		this.client.setScreen(new EditProgressScreen(this, this.uniqueOutput(), this.keep, thenExport));
	}

	private Path uniqueOutput() {
		Path file = this.playback.file();
		Path directory = file.getParent() == null ? Path.of(".") : file.getParent();
		String name = file.getFileName().toString();
		String base = name.endsWith(ReplayFormat.FILE_EXTENSION)
				? name.substring(0, name.length() - ReplayFormat.FILE_EXTENSION.length())
				: name;

		for (int i = 0; ; i++) {
			String candidate = base + "_edit" + (i == 0 ? "" : "_" + (i + 1)) + ReplayFormat.FILE_EXTENSION;
			Path path = directory.resolve(candidate);

			if (!Files.exists(path)) {
				return path;
			}
		}
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

	/** 前に戻るときは世界を作り直す（編集中の範囲は引き継ぐ） */
	private void restartAt(long targetMs) {
		MinecraftClient client = MinecraftClient.getInstance();

		try {
			this.playback.dispose();
			ReplayPlayback fresh = ReplayPlayback.start(client, this.playback.file());
			client.setScreen(new ClipEditorScreen(fresh, this.keep, this.inMs, this.outMs));
			fresh.jumpTo(targetMs);
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 編集をやり直せませんでした", e);
			client.setScreen(new RecordingListScreen(new TitleScreen()));
		}
	}

	// --- 集計 ---

	private long keptTotalMs() {
		long total = 0L;

		for (ClipRemux.Range range : this.keep) {
			total += Math.max(0L, range.endMs() - range.startMs());
		}

		return total;
	}

	/** 真ん中を抜いた所があるか（つなぎ目は多少乱れるので注意を出す） */
	private boolean hasSplice() {
		for (int i = 1; i < this.keep.size(); i++) {
			if (this.keep.get(i).startMs() > this.keep.get(i - 1).endMs()) {
				return true;
			}
		}

		return false;
	}

	private static List<ClipRemux.Range> append(List<ClipRemux.Range> ranges, ClipRemux.Range extra) {
		List<ClipRemux.Range> result = new ArrayList<>(ranges);
		result.add(new ClipRemux.Range(Math.min(extra.startMs(), extra.endMs()),
				Math.max(extra.startMs(), extra.endMs())));
		return result;
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

		ModernButton add(Text message, int width, Consumer<ModernButton> action, ModernButton.Style style) {
			if (this.x + width > this.right && this.x > this.left) {
				this.x = this.left;
				this.y -= ROW_HEIGHT + GAP;
			}

			ModernButton button = new ModernButton(this.x, this.y, width, ROW_HEIGHT, message, action, style);

			addDrawableChild(button);
			this.x += width + GAP;
			return button;
		}
	}

	// --- 一覧・プレビューから呼ぶ ---

	/** いまの世界を片付けてから編集を始める */
	public static void open(MinecraftClient client, Path file, @org.jspecify.annotations.Nullable Screen parent) {
		if (client.world != null || client.getNetworkHandler() != null) {
			client.setScreen(new ConfirmScreen(accepted -> {
				if (accepted) {
					start(client, file, parent, -1L);
				} else {
					client.setScreen(new RecordingListScreen(parent == null ? new TitleScreen() : parent));
				}
			}, Text.translatable("ifuto-replay.editor.confirm_title"),
					Text.translatable("ifuto-replay.editor.confirm_message")));

			return;
		}

		start(client, file, parent, -1L);
	}

	/** プレビューから移る（見ていた位置から始める。再生はすでに片付けてある） */
	public static void openAt(MinecraftClient client, Path file, long atMs) {
		start(client, file, null, atMs);
	}

	private static void start(MinecraftClient client, Path file, @org.jspecify.annotations.Nullable Screen parent,
			long atMs) {
		if (client.world != null || client.getNetworkHandler() != null) {
			client.disconnect(new BlankScreen(), false, true);
		}

		try {
			ReplayPlayback playback = ReplayPlayback.start(client, file);
			ClipEditorScreen screen = new ClipEditorScreen(playback);
			client.setScreen(screen);

			long at = atMs >= 0L ? atMs : playback.trimStartMs();

			if (at > 0L) {
				playback.jumpTo(at);
			}
		} catch (Exception e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 編集を始められませんでした", e);
			Screen back = parent == null ? new TitleScreen() : parent;
			client.setScreen(new NoticeScreen(new RecordingListScreen(back),
					Text.translatable("ifuto-replay.preview.error_title"),
					Text.translatable("ifuto-replay.preview.error_no_join")));
		}
	}
}
