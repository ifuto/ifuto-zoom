package com.ifuto.replay.gui;

import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.playback.ReplayPlayback;
import com.ifuto.replay.playback.ReplayStream;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * 再生位置のバー。つまみをドラッグして好きな場所へ飛べる。
 *
 * <p>録画は「先頭から順に流す」作りなので、前に戻るときは世界を作り直す
 * （作り直しの予算は {@link ReplayPlayback} 側で 1 フレームずつ抑えている）。
 */
final class ReplayTimelineWidget extends ClickableWidget {
	private static final int TRACK_HEIGHT = 4;
	private static final int MARKER_COLOR = 0xFFFFEB3B;

	private final ReplayPlayback playback;
	private boolean dragging;

	ReplayTimelineWidget(int x, int y, int width, int height, ReplayPlayback playback) {
		super(x, y, width, height, Text.empty());
		this.playback = playback;
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int x = this.getX();
		int y = this.getY();
		int width = this.getWidth();
		int height = this.getHeight();
		long duration = this.playback.durationMs();
		int middle = y + height / 2;

		int trackTop = middle - TRACK_HEIGHT / 2;

		// 線（まだ進んでいないぶん）
		ReplayTheme.fillRound(context, x, trackTop, width, TRACK_HEIGHT, 2, ReplayTheme.SURFACE_INPUT);

		if (duration <= 0L) {
			return;
		}

		// 進んだ分
		int progress = this.positionOf(this.playback.timeMs(), duration);
		int filled = progress - x;

		if (filled > 0) {
			ReplayTheme.fillRound(context, x, trackTop, filled, TRACK_HEIGHT, 2, ReplayTheme.ACCENT);
		}

		// しおり
		for (ReplayStream.Marker marker : this.playback.markers()) {
			int mx = this.positionOf(marker.timeMs(), duration);
			context.fill(mx, trackTop - 2, mx + 1, trackTop + TRACK_HEIGHT + 2, MARKER_COLOR);
		}

		// つまみ（丸い）
		ReplayTheme.fillRound(context, progress - 3, middle - 5, 7, 10, 3, ReplayTheme.TEXT);
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		this.dragging = true;
		this.seekTo(click.x());
	}

	@Override
	public void onRelease(Click click) {
		if (this.dragging) {
			this.dragging = false;
			this.seekTo(click.x());
		}
	}

	@Override
	protected void onDrag(Click click, double offsetX, double offsetY) {
		if (this.dragging) {
			this.seekTo(click.x());
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		this.appendDefaultNarrations(builder);
	}

	private int positionOf(long timeMs, long duration) {
		double ratio = MathHelper.clamp((double) timeMs / (double) duration, 0.0, 1.0);
		return this.getX() + (int) Math.round(ratio * this.getWidth());
	}

	private void seekTo(double mouseX) {
		long duration = this.playback.durationMs();

		if (duration <= 0L) {
			return;
		}

		double ratio = MathHelper.clamp((mouseX - this.getX()) / this.getWidth(), 0.0, 1.0);
		this.playback.jumpTo((long) Math.round(ratio * duration));
	}
}
