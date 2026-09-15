package com.ifuto.replay.mixin;

import com.ifuto.replay.export.ReplayExporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 書き出し中だけ、**ゲームが見る「前のフレームからの時間」を差し替える**。
 *
 * <p>ふだん Minecraft は「実時間で何ミリ経ったか」で世界を進める。書き出しを
 * 等倍より速くすると、1枚描くあいだに進む実時間が短くなるので、モーションブラー
 * の蓄積・時間で減衰する演出・シェーダーの時刻などが本番よりゆっくり進んでしまう。
 *
 * <p>ここで渡す時刻を「録画のフレーム間隔ぶん進んだ時刻」に差し替えると、
 * 何倍速で書き出しても「録画の1秒ぶん」に進む量が本番と同じになる。
 * （= 蓄積の速度を書き出しの速さに合わせて上げる）
 *
 * <p>書き出しをしていないときは **何もしない**（渡ってきた時刻をそのまま通す）。
 *
 * <p>内側のクラス（`RenderTickCounter$Dynamic`）を直接つついているので、
 * 将来ここが無くなっても落ちないように {@code require = 0} にしてある
 * （見つからなければ、この機能だけ黙って動かなくなる）。
 */
@Mixin(targets = "net.minecraft.client.render.RenderTickCounter$Dynamic")
public class RenderTickCounterMixin {
	/** 前のフレームで「こう進んだことにした」時刻 */
	private long ifutoReplay$virtualTimeMillis;

	/** さっき自分が差し替えた値（同じ物がもう一度渡ってきたら、それは内側の呼び出し） */
	private long ifutoReplay$lastReturned;

	/** さっき差し替えた直後か */
	private boolean ifutoReplay$justReplaced;

	@ModifyVariable(method = "beginRenderTick", at = @At("HEAD"), ordinal = 0, require = 0)
	private long ifutoReplay$beginRenderTick(long timeMillis) {
		// 同じ値が続けて渡ってきたら、それは「引数を増やした版」からの呼び出しなので
		// そのまま通す（二重に進ませない）
		if (this.ifutoReplay$justReplaced && timeMillis == this.ifutoReplay$lastReturned) {
			this.ifutoReplay$justReplaced = false;
			return timeMillis;
		}

		long step = ReplayExporter.desiredFrameMillis();

		if (step <= 0L) {
			// 書き出し中でない（または等倍速）ので、実時間をそのまま使う。
			// 仮想の時計も実時間に合わせておく（あとで急に飛ばないように）
			this.ifutoReplay$virtualTimeMillis = timeMillis;
			this.ifutoReplay$justReplaced = false;
			return timeMillis;
		}

		long previous = this.ifutoReplay$virtualTimeMillis;
		long next = previous <= 0L ? timeMillis : previous + step;

		this.ifutoReplay$virtualTimeMillis = next;
		this.ifutoReplay$lastReturned = next;
		this.ifutoReplay$justReplaced = true;
		return next;
	}
}
