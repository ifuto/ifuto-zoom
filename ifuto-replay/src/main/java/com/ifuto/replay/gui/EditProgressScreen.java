package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
import com.ifuto.replay.recording.ClipRemux;
import com.ifuto.replay.recording.ReplayFileReader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * 切り出し中の画面。終わるまで待ち、やめることもできる。
 *
 * <p>終わったら編集側の再生を片付けて一覧へ戻る。
 * 失敗・中断なら編集に戻る（編集中の内容は残っている）。
 */
public class EditProgressScreen extends Screen {
	private static final int BAR_WIDTH = 300;
	private static final int BAR_HEIGHT = 8;

	private final ClipEditorScreen editor;
	private final Job job;

	/** 終わったら書き出し画面へ進むか（mp4で出力） */
	private final boolean thenExport;

	public EditProgressScreen(ClipEditorScreen editor, Path output, List<ClipRemux.Range> ranges) {
		this(editor, output, ranges, false);
	}

	public EditProgressScreen(ClipEditorScreen editor, Path output, List<ClipRemux.Range> ranges, boolean thenExport) {
		super(Text.translatable("ifuto-replay.editor.progress_title"));
		this.editor = editor;
		this.thenExport = thenExport;
		this.job = new Job(editor.sourceFile(), output, List.copyOf(ranges));
		this.job.start();
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int centerY = this.height / 2;

		this.addDrawableChild(new ModernButton(centerX - 60, centerY + 34, 120, 20,
				Text.translatable("gui.cancel"), button -> this.job.cancelRequested = true,
				ModernButton.Style.NORMAL));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);

		int centerX = this.width / 2;
		int centerY = this.height / 2 - 10;

		Text title = Text.translatable("ifuto-replay.editor.progress_title");
		context.drawText(this.textRenderer, title, centerX - this.textRenderer.getWidth(title) / 2,
				centerY - 34, 0xFFFFFF, false);

		Text phase = this.phaseText();
		context.drawText(this.textRenderer, phase, centerX - this.textRenderer.getWidth(phase) / 2,
				centerY - 18, 0xAAAAAA, false);

		int left = centerX - BAR_WIDTH / 2;
		ReplayTheme.fillRound(context, left, centerY, BAR_WIDTH, BAR_HEIGHT, 3, ReplayTheme.SURFACE_INPUT);

		double ratio = this.job.total > 0L ? (double) this.job.done / (double) this.job.total : 0.0;
		int filled = (int) Math.round(BAR_WIDTH * Math.max(0.0, Math.min(1.0, ratio)));

		if (filled > 0) {
			ReplayTheme.fillRound(context, left, centerY, filled, BAR_HEIGHT, 3, ReplayTheme.ACCENT);
		}
	}

	@Override
	public void tick() {
		if (!this.job.finished) {
			return;
		}

		if (this.job.cancelRequested) {
			// やめた（途中の出力は消してある）。編集に戻る
			this.client.setScreen(this.editor);
			return;
		}

		if (this.job.failure != null || this.job.result == null) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 切り出せませんでした", this.job.failure);
			this.client.setScreen(new NoticeScreen(this.editor,
					Text.translatable("ifuto-replay.editor.error_title"),
					Text.translatable("ifuto-replay.editor.error_message")));
			return;
		}

		ClipRemux.Result result = this.job.result;

		if (this.thenExport) {
			// mp4で出力：切った物をそのまま書き出し画面へ（開始位置は自動で合っている）
			ReplayFileReader.Info info = ReplayFileReader.read(result.output());

			if (info == null) {
				this.client.setScreen(new NoticeScreen(this.editor,
						Text.translatable("ifuto-replay.editor.error_title"),
						Text.translatable("ifuto-replay.editor.error_message")));
				return;
			}

			this.editor.playback().stop(new RecordingListScreen(new TitleScreen()));
			this.client.setScreen(new ExportScreen(new RecordingListScreen(new TitleScreen()), info,
					ReplayConfig.get()));
			return;
		}

		this.editor.playback().stop(new RecordingListScreen(new TitleScreen()));

		String fileName = result.output().getFileName().toString();
		Text message = result.truncated()
				? Text.translatable("ifuto-replay.editor.done_truncated", fileName)
				: result.audioFailed()
						? Text.translatable("ifuto-replay.editor.done_audio_failed", fileName)
						: Text.translatable("ifuto-replay.editor.done_message", fileName);

		this.client.setScreen(new NoticeScreen(new RecordingListScreen(new TitleScreen()),
				Text.translatable("ifuto-replay.editor.done_title"), message));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Esc でもやめる（閉じるだけだと裏で動き続けるので）
		this.job.cancelRequested = true;
		return false;
	}

	private Text phaseText() {
		String key = switch (this.job.pass) {
			case ClipRemux.PASS_AUDIO -> "ifuto-replay.editor.phase_audio";
			case ClipRemux.PASS_COPY -> "ifuto-replay.editor.phase_copy";
			default -> "ifuto-replay.editor.phase_scan";
		};

		return Text.translatable(key);
	}

	/** 裏で走る切り出し。進捗だけ画面に見せる */
	private static final class Job implements ClipRemux.Progress {
		private final Path source;
		private final Path output;
		private final List<ClipRemux.Range> ranges;

		private volatile int pass;
		private volatile long done;
		private volatile long total = 1L;
		private volatile boolean cancelRequested;
		private volatile boolean finished;
		private volatile @Nullable Throwable failure;
		private volatile ClipRemux.@Nullable Result result;
		private long lastReportMs;

		Job(Path source, Path output, List<ClipRemux.Range> ranges) {
			this.source = source;
			this.output = output;
			this.ranges = ranges;
		}

		void start() {
			Thread thread = new Thread(() -> {
				try {
					this.result = ClipRemux.remux(this.source, this.output, this.ranges, this);
				} catch (Throwable t) {
					this.failure = t;
				} finally {
					this.finished = true;
				}
			}, "ifuto-replay-remux");

			thread.setDaemon(true);

			try {
				thread.start();
			} catch (Throwable t) {
				// 起き上がらなくても待ちぼうけにしない
				this.failure = t;
				this.finished = true;
			}
		}

		@Override
		public void onProgress(int pass, long done, long total) {
			// 細かく来すぎるので間引く（画面は tick で見るだけ）
			long now = System.currentTimeMillis();

			if (now - this.lastReportMs < 100L && done < total) {
				return;
			}

			this.lastReportMs = now;
			this.pass = pass;
			this.done = done;
			this.total = Math.max(1L, total);
		}

		@Override
		public boolean isCancelled() {
			return this.cancelRequested;
		}
	}
}
