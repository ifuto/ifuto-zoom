package com.ifuto.replay.gui;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.export.ReplayExporter;
import com.ifuto.replay.gui.theme.ReplayTheme;
import com.ifuto.replay.gui.widget.ModernButton;
import com.ifuto.replay.playback.ReplayPlayback;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;

/**
 * 書き出し中の画面。
 *
 * <p>実時間とは関係なく「1フレーム描く → 時刻を進める」を繰り返すので、重いシーンでは
 * ゆっくり進む（そのぶん絵はカクつかない）。終わったら ffmpeg を閉じて元の画面に戻る。
 */
@Environment(EnvType.CLIENT)
public class ExportProgressScreen extends Screen {
	private static final int MAX_TEXT_WIDTH = 340;

	private final ReplayExporter exporter;
	private final ReplayPlayback playback;
	private final Screen parent;
	private final ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this);

	private TextWidget statusText;
	private TextWidget detailText;
	private boolean handled;
	/** 残り時間の推定に使う窓（20Hz tick で100件 = 5秒ぶん。固定長で溜めない） */
	private static final int SAMPLE_WINDOW = 100;
	private final long[] sampleTimes = new long[SAMPLE_WINDOW];
	private final int[] sampleFrames = new int[SAMPLE_WINDOW];
	private int sampleCount;

	public ExportProgressScreen(ReplayExporter exporter, ReplayPlayback playback, Screen parent) {
		super(Text.translatable("ifuto-replay.export.progress_title"));
		this.exporter = exporter;
		this.playback = playback;
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.layout.addHeader(this.title, this.textRenderer);

		DirectionalLayoutWidget body = this.layout.addBody(DirectionalLayoutWidget.vertical().spacing(8));
		this.statusText = new TextWidget(Text.translatable("ifuto-replay.export.preparing"), this.textRenderer);
		this.detailText = new TextWidget(Text.empty(), this.textRenderer);
		int textWidth = Math.min(MAX_TEXT_WIDTH, this.width - 40);
		this.statusText.setMaxWidth(textWidth);
		this.detailText.setMaxWidth(textWidth);
		body.add(this.statusText);
		body.add(this.detailText);

		this.layout.addFooter(new ModernButton(0, 0, Math.min(200, this.width - 40), 20,
				Text.translatable("gui.cancel"), button -> this.cancel(), ModernButton.Style.DANGER));

		this.layout.forEachChild(this::addDrawableChild);
		this.refreshWidgetPositions();
	}

	/** うっすら暗くするだけ（書き出し中は世界が見えたほうが安心なので薄め） */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.renderBackground(context, mouseX, mouseY, deltaTicks);
		ReplayTheme.veil(context, this.width, this.height);
	}

	/** 進みぐあいを棒で出す（数字だけだと待っている間つらいので） */
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);

		int total = this.exporter.totalFrames();
		int frames = Math.max(0, this.exporter.frameIndex() - 1);
		int barWidth = Math.min(this.width - 120, 320);
		int x = this.width / 2 - barWidth / 2;
		int y = this.height / 2 + 30;
		ReplayTheme.progress(context, x, y, barWidth, 6,
				total <= 0 ? 0F : Math.min(1F, frames / (float) total));
	}

	@Override
	public void tick() {
		super.tick();

		if (this.handled) {
			return;
		}

		// 3分以上まったく進まなければ中断する（そのまま待たせないための保険）
		this.exporter.checkStalled(180_000L);

		switch (this.exporter.state()) {
			case RUNNING -> this.updateProgress();
			case FINISHING -> this.updateFinishing();
			case DONE -> this.finish();
			case FAILED -> this.fail();
			case CANCELLED -> this.handled = true;
		}
	}

	private void updateProgress() {
		int frames = Math.max(0, this.exporter.frameIndex() - 1);
		int total = this.exporter.totalFrames();
		int percent = total <= 0 ? 0 : Math.min(100, frames * 100 / total);
		this.sample(System.currentTimeMillis(), frames);

		if (this.statusText != null) {
			this.statusText.setMessage(Text.translatable("ifuto-replay.export.progress",
					Text.literal(String.valueOf(frames)),
					Text.literal(String.valueOf(total)),
					Text.literal(percent + "%")));
		}

		long remaining = estimatedRemaining(frames, total);

		if (this.detailText != null) {
			this.detailText.setMessage(Text.translatable("ifuto-replay.export.progress_detail",
					Text.literal(formatElapsed(this.exporter.elapsedMs())),
					Text.literal(remaining < 0L ? "―" : formatElapsed(remaining)),
					Text.literal(this.exporter.output().toString())));
		}
	}

	/** 速さの標本を1件足す（古いのは捨てる） */
	private void sample(long now, int frames) {
		if (this.sampleCount >= SAMPLE_WINDOW) {
			System.arraycopy(this.sampleTimes, 1, this.sampleTimes, 0, SAMPLE_WINDOW - 1);
			System.arraycopy(this.sampleFrames, 1, this.sampleFrames, 0, SAMPLE_WINDOW - 1);
			this.sampleCount = SAMPLE_WINDOW - 1;
		}

		this.sampleTimes[this.sampleCount] = now;
		this.sampleFrames[this.sampleCount] = frames;
		this.sampleCount++;
	}

	/** 絵は出し切った。ffmpeg がまとめ終わるのを待っているあいだの表示 */
	private void updateFinishing() {
		if (this.statusText != null) {
			this.statusText.setMessage(Text.translatable("ifuto-replay.export.finishing"));
		}

		if (this.detailText != null) {
			this.detailText.setMessage(Text.translatable("ifuto-replay.export.progress_detail",
					Text.literal(formatElapsed(this.exporter.elapsedMs())),
					Text.literal(formatElapsed(0L)),
					Text.literal(this.exporter.output().toString())));
		}
	}

	/**
	 * あと何msか。まだ出せないときは -1（「―」と出す）。
	 *
	 * <p>直近5秒の速さで割る。最初からの平均だと、序盤の遅さ（シェーダーの
	 * 組み直し等）をずっと引きずって「残り5時間」とか出てしまう。
	 */
	private long estimatedRemaining(int frames, int total) {
		if (total <= frames) {
			return 0L;
		}

		// 序盤は速さが安定しないので出さない
		if (frames < 30) {
			return -1L;
		}

		long now = System.currentTimeMillis();
		int first = 0;

		while (first + 1 < this.sampleCount && now - this.sampleTimes[first] > 5000L) {
			first++;
		}

		long dt = now - this.sampleTimes[first];
		int df = frames - this.sampleFrames[first];

		if (dt < 500L || df <= 0) {
			return -1L;
		}

		return (long) ((total - frames) * (dt / (double) df));
	}

	private static String formatElapsed(long ms) {
		long totalSeconds = Math.max(0L, ms / 1000L);
		long hours = totalSeconds / 3600L;
		long minutes = totalSeconds % 3600L / 60L;
		long seconds = totalSeconds % 60L;

		if (hours > 0L) {
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}

		return String.format("%d:%02d", minutes, seconds);
	}

	private void finish() {
		this.handled = true;
		// 先に世界を片付けてから、結果の画面を出す（順番を逆にすると切断の画面で上書きされる）
		this.stopPlayback();
		MinecraftClient.getInstance().setScreen(new NoticeScreen(new RecordingListScreen(this.parent),
				Text.translatable("ifuto-replay.export.done_title"),
				Text.translatable("ifuto-replay.export.done_message",
						Text.literal(String.valueOf(Math.max(0, this.exporter.frameIndex() - 1))),
						Text.literal(this.exporter.output().toString()))));
	}

	private void fail() {
		this.handled = true;
		String reason = this.exporter.failureMessage();

		if (reason == null || reason.isEmpty()) {
			reason = Text.translatable("ifuto-replay.export.error_unknown").getString();
		}

		this.stopPlayback();
		MinecraftClient.getInstance().setScreen(new NoticeScreen(new RecordingListScreen(this.parent),
				Text.translatable("ifuto-replay.export.error_title"),
				Text.translatable("ifuto-replay.export.error_message", Text.literal(reason))));
	}

	private void cancel() {
		if (this.handled) {
			return;
		}

		this.handled = true;
		this.exporter.cancel();
		this.stopPlayback();
		MinecraftClient.getInstance().setScreen(new RecordingListScreen(this.parent));
	}

	private void stopPlayback() {
		try {
			this.playback.stop(null);
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 再生の片付けに失敗しました", t);
		}
	}

	@Override
	public void close() {
		if (!this.handled) {
			this.cancel();
			return;
		}

		MinecraftClient.getInstance().setScreen(this.parent);
	}

	@Override
	public boolean shouldPause() {
		// 書き出し中は世界を動かしたままにしたい
		return false;
	}

	@Override
	protected void refreshWidgetPositions() {
		this.layout.refreshPositions();
	}
}
