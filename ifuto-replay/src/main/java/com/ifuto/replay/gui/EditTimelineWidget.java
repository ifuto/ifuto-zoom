package com.ifuto.replay.gui;

import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.playback.ReplayPlayback;
import com.ifuto.replay.playback.ReplayStream;
import com.ifuto.replay.recording.ClipRemux;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * 編集用のバー。残す所（青）と落とす所（赤）を見せ、つまみで飛べる。
 *
 * <p>飛ぶのは再生と同じ（前に戻るときは世界を作り直す）。
 */
final class EditTimelineWidget extends ClickableWidget {
	private static final int TRACK_HEIGHT = 8;
	private static final int MARKER_COLOR = 0xFFFFEB3B;

	private final ClipEditorScreen editor;
	private boolean dragging;

	EditTimelineWidget(int x, int y, int width, int height, ClipEditorScreen editor) {
		super(x, y, width, height, Text.empty());
		this.editor = editor;
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int x = this.getX();
		int y = this.getY();
		int width = this.getWidth();
		int height = this.getHeight();
		ReplayPlayback playback = this.editor.playback();
		long duration = playback.durationMs();
		int middle = y + height / 2;
		int trackTop = middle - TRACK_HEIGHT / 2;

		// 土台（落とす所の色）
		ReplayTheme.fillRound(context, x, trackTop, width, TRACK_HEIGHT, 2, ReplayTheme.RECORD_SOFT);

		if (duration <= 0L) {
			return;
		}

		// 残す所
		for (ClipRemux.Range range : this.editor.keepRanges()) {
			int from = this.positionOf(range.startMs(), duration);
			int to = this.positionOf(range.endMs(), duration);

			if (to > from) {
				context.fill(from, trackTop, to, trackTop + TRACK_HEIGHT, ReplayTheme.ACCENT);
			}
		}

		// 作業中の区間（白い枠）
		long in = Math.min(this.editor.inMs(), this.editor.outMs());
		long out = Math.max(this.editor.inMs(), this.editor.outMs());

		if (out > in) {
			int from = this.positionOf(in, duration);
			int to = this.positionOf(out, duration);
			context.fill(from, trackTop - 1, to, trackTop, 0xFFFFFFFF);
			context.fill(from, trackTop + TRACK_HEIGHT, to, trackTop + TRACK_HEIGHT + 1, 0xFFFFFFFF);
		}

		// しおり
		for (ReplayStream.Marker marker : playback.markers()) {
			int mx = this.positionOf(marker.timeMs(), duration);
			context.fill(mx, trackTop - 2, mx + 1, trackTop + TRACK_HEIGHT + 2, MARKER_COLOR);
		}

		// つまみ（丸い）
		int progress = this.positionOf(playback.timeMs(), duration);
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
		long duration = this.editor.playback().durationMs();

		if (duration <= 0L) {
			return;
		}

		double ratio = MathHelper.clamp((mouseX - this.getX()) / this.getWidth(), 0.0, 1.0);
		this.editor.playback().jumpTo((long) Math.round(ratio * duration));
	}
}
