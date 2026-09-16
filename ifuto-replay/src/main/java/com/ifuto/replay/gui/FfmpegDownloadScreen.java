package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.export.FfmpegInstaller;
import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * ffmpeg が無いときに落としてくる画面。終わったら続きへ戻る。
 *
 * <p>100MB 前後あるので進捗を見せる。やめたら元の画面に戻るだけ。
 * Linux は自動では入れられないので、入れ方の案内を出すだけ。
 */
public class FfmpegDownloadScreen extends Screen {
	private static final int BAR_WIDTH = 300;
	private static final int BAR_HEIGHT = 8;

	private final Screen parent;
	private final Consumer<String> onInstalled;
	private final boolean guideOnly;
	private final Job job;

	public FfmpegDownloadScreen(Screen parent, Consumer<String> onInstalled) {
		super(Text.translatable("ifuto-replay.ffmpeg.title"));
		this.parent = parent;
		this.onInstalled = onInstalled;
		this.guideOnly = !FfmpegInstaller.canInstall();
		this.job = new Job();

		if (!this.guideOnly) {
			this.job.start();
		}
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int centerY = this.height / 2;

		int buttonWidth = Math.min(120, this.width - 32);
		int buttonX = centerX - buttonWidth / 2;

		if (this.guideOnly) {
			this.addDrawableChild(new ModernButton(buttonX, centerY + 34, buttonWidth, 20,
					Text.translatable("gui.back"), button -> this.close(),
					ModernButton.Style.NORMAL));
			return;
		}

		this.addDrawableChild(new ModernButton(buttonX, centerY + 34, buttonWidth, 20,
				Text.translatable("gui.cancel"), button -> this.job.cancelRequested = true,
				ModernButton.Style.NORMAL));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);

		int centerX = this.width / 2;
		int centerY = this.height / 2 - 10;

		Text title = Text.translatable("ifuto-replay.ffmpeg.title");
		context.drawText(this.textRenderer, title, centerX - this.textRenderer.getWidth(title) / 2,
				centerY - 34, 0xFFFFFF, false);

		if (this.guideOnly) {
			Text guide = Text.translatable("ifuto-replay.ffmpeg.linux_guide");
			context.drawText(this.textRenderer, guide, centerX - this.textRenderer.getWidth(guide) / 2,
					centerY - 14, 0xAAAAAA, false);
			return;
		}

		Text status = Text.translatable("ifuto-replay.ffmpeg.downloading",
				formatSize(this.job.done), formatSize(this.job.total));
		context.drawText(this.textRenderer, status, centerX - this.textRenderer.getWidth(status) / 2,
				centerY - 18, 0xAAAAAA, false);

		int barWidth = Math.min(BAR_WIDTH, this.width - 40);
		int left = centerX - barWidth / 2;
		ReplayTheme.fillRound(context, left, centerY, barWidth, BAR_HEIGHT, 3, ReplayTheme.SURFACE_INPUT);

		double ratio = this.job.total > 0L ? (double) this.job.done / (double) this.job.total : 0.0;
		int filled = (int) Math.round(barWidth * Math.max(0.0, Math.min(1.0, ratio)));

		if (filled > 0) {
			ReplayTheme.fillRound(context, left, centerY, filled, BAR_HEIGHT, 3, ReplayTheme.ACCENT);
		}
	}

	@Override
	public void tick() {
		if (this.guideOnly || !this.job.finished) {
			return;
		}

		if (this.job.cancelRequested) {
			this.client.setScreen(this.parent);
			return;
		}

		if (this.job.failure != null || this.job.installed == null) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg を用意できませんでした", this.job.failure);
			String reason = this.job.failure == null ? "" : String.valueOf(this.job.failure.getMessage());
			this.client.setScreen(new NoticeScreen(this.parent,
					Text.translatable("ifuto-replay.ffmpeg.title"),
					Text.translatable("ifuto-replay.ffmpeg.failed", reason)));
			return;
		}

		this.onInstalled.accept(this.job.installed);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		this.job.cancelRequested = true;
		return false;
	}

	@Override
	public void close() {
		this.job.cancelRequested = true;
		this.client.setScreen(this.parent);
	}

	private static String formatSize(long bytes) {
		if (bytes < 0L) {
			return "?";
		}

		if (bytes < 1024L * 1024L) {
			return (bytes / 1024L) + " KB";
		}

		return String.format("%.1f MB", (double) bytes / (1024.0 * 1024.0));
	}

	/** 裏で落とす。進捗だけ画面に見せる */
	private static final class Job implements FfmpegInstaller.Progress {
		private volatile long done;
		private volatile long total = 1L;
		private volatile boolean cancelRequested;
		private volatile boolean finished;
		private volatile @Nullable Throwable failure;
		private volatile @Nullable String installed;
		private long lastReportMs;

		void start() {
			Thread thread = new Thread(() -> {
				try {
					this.installed = FfmpegInstaller.install(this);
				} catch (Throwable t) {
					this.failure = t;
				} finally {
					this.finished = true;
				}
			}, "ifuto-replay-ffmpeg-install");

			thread.setDaemon(true);

			try {
				thread.start();
			} catch (Throwable t) {
				this.failure = t;
				this.finished = true;
			}
		}

		@Override
		public void update(long done, long total) {
			long now = System.currentTimeMillis();

			if (now - this.lastReportMs < 100L && done < total) {
				return;
			}

			this.lastReportMs = now;
			this.done = done;
			this.total = Math.max(1L, total);
		}

		@Override
		public boolean isCancelled() {
			return this.cancelRequested;
		}
	}
}
