package com.ifuto.fastgenerate.pipeline;

/**
 * 「いま何本まで同時に走らせてよいか」を決める制御器。
 *
 * <p>弱い CPU で並列度を上げすぎると 1 tick が伸びてカクつく。逆に強い CPU では
 * 遠慮しすぎてコアが遊ぶ。そこで <b>AIMD</b>（加算増加・乗算減少。Jacobson 1988 の
 * 輻輳制御と同じ形）で、tick 作業時間とフレーム時間を見ながらウィンドウを動かす。</p>
 *
 * <ul>
 *   <li>目標を超えたら <b>×0.75（切り捨て）</b>（速く引き締める）</li>
 *   <li>余裕があるときだけ <b>+1</b>（ゆっくり広げる）</li>
 *   <li>変更後は {@link #HOLD_TICKS} tick 様子を見る（振動しないように）</li>
 * </ul>
 *
 * <p>計測は連続時間の指数移動平均（EWMA, τ = {@value #TAU_MS} ms）。時定数を長めに
 * 取るのは、1 回のスパイク（GC・チャンク I/O）でウィンドウを落とさないため。
 * フィードバック制御としての定式化は Hellerstein et al. (2004) に従う。</p>
 *
 * <p>Minecraft に依存しない（テストできる）。</p>
 */
public final class TickController {
	/** EWMA の時定数 [ms] */
	public static final double TAU_MS = 300.0;
	/** 1 tick の想定間隔 [ms] */
	public static final double TICK_MS = 50.0;
	/** 変更後に何 tick 待つか */
	public static final int HOLD_TICKS = 10;
	/** 乗算減少の係数 */
	public static final double DECREASE = 0.75;
	/** 目標のこの割合を切ったら増やす */
	public static final double ROOMY = 0.70;

	private final int minWindow;
	private final int maxWindow;
	private double mspt;
	private double frameMs;
	private int window;
	private int hold;

	public TickController(int minWindow, int maxWindow, int startWindow) {
		this.minWindow = Math.max(1, minWindow);
		this.maxWindow = Math.max(this.minWindow, maxWindow);
		this.window = Math.max(this.minWindow, Math.min(this.maxWindow, startWindow));
		this.mspt = 20.0;
		this.frameMs = 0.0;
	}

	/** 1 tick ぶんの計測値を入れる（frameMs が 0 以下なら「信号なし」） */
	public void sample(double workMs, double frameMsSample) {
		double alpha = 1.0 - Math.exp(-TICK_MS / TAU_MS);
		mspt += alpha * (workMs - mspt);
		if (frameMsSample > 0.0) {
			frameMs += alpha * (frameMsSample - frameMs);
		}
	}

	/**
	 * ウィンドウを更新して返す。1 tick に 1 回呼ぶ。
	 *
	 * @param targetMspt   サーバーの 1 tick 作業時間の目標 [ms]
	 * @param targetFrameMs クライアントのフレーム時間の目標 [ms]（0 なら無視）
	 */
	public int update(double targetMspt, double targetFrameMs) {
		if (hold > 0) {
			hold--;
			return window;
		}
		boolean frameSlow = targetFrameMs > 0.0 && frameMs > targetFrameMs;
		boolean frameRoomy = targetFrameMs <= 0.0 || frameMs < targetFrameMs * ROOMY;
		boolean tooSlow = mspt > targetMspt || frameSlow;
		boolean roomy = mspt < targetMspt * ROOMY && frameRoomy;

		int next = window;
		if (tooSlow) {
			next = Math.max(minWindow, (int) Math.floor(window * DECREASE));
		} else if (roomy) {
			next = Math.min(maxWindow, window + 1);
		}
		if (next != window) {
			window = next;
			hold = HOLD_TICKS;
		}
		return window;
	}

	public int window() {
		return window;
	}

	public int minWindow() {
		return minWindow;
	}

	public int maxWindow() {
		return maxWindow;
	}

	public double mspt() {
		return mspt;
	}

	public double frameMs() {
		return frameMs;
	}

	public void reset() {
		window = Math.max(minWindow, Math.min(maxWindow, window));
		hold = 0;
	}
}
