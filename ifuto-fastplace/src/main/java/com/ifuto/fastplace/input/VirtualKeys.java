package com.ifuto.fastplace.input;

import net.minecraft.client.option.KeyBinding;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 「押していないけど、押したことにする」キーの管理。
 *
 * <p>爆弾（クリスタル）のように<b>モジュールが代わりに操作する</b>場面でも、
 * キー表示 Mod（CPS 表示・キーボード表示）にちゃんと押した状態を見せたい。
 * そこで {@link KeyBinding#isPressed()} にだけ細工をして、数フレームのあいだ
 * true を返させる（{@code mixin/KeyBindingMixin}）。</p>
 *
 * <p>本物のキー状態（{@code setPressed}）は<b>触らない</b>。触ると、実際に押している
 * キーを離したことにしてしまい、入力が壊れる。壊すのは「読み取り」だけ。</p>
 */
public final class VirtualKeys {
	/** 既定の押下時間 [ms]（描画 5〜7 フレームぶん。HUD が拾うには十分） */
	private static final long HOLD_MS = 120L;

	private static final Map<KeyBinding, Long> UNTIL = new IdentityHashMap<>();

	private VirtualKeys() {
	}

	/** 押したことにする（{@link #HOLD_MS} のあいだ） */
	public static void press(KeyBinding binding) {
		press(binding, HOLD_MS);
	}

	public static void press(KeyBinding binding, long holdMs) {
		if (binding == null) {
			return;
		}

		synchronized (UNTIL) {
			UNTIL.put(binding, System.currentTimeMillis() + holdMs);
		}
	}

	/** いま「押したことになっている」か */
	public static boolean isVirtual(KeyBinding binding) {
		if (binding == null) {
			return false;
		}

		synchronized (UNTIL) {
			Long until = UNTIL.get(binding);

			if (until == null) {
				return false;
			}

			if (System.currentTimeMillis() > until) {
				UNTIL.remove(binding);
				return false;
			}

			return true;
		}
	}

	/**
	 * 本物の指で押されているか（モジュール側の判定用）。
	 * ここで {@code isPressed()} を使うと自分の細工を拾ってしまうので、必ず仮想ぶんを差し引く。
	 */
	public static boolean physical(KeyBinding binding) {
		return binding != null && binding.isPressed() && !isVirtual(binding);
	}

	/** 押下の記録を全部捨てる（モジュール OFF / 画面を開いたときなど） */
	public static void clear() {
		synchronized (UNTIL) {
			UNTIL.clear();
		}
	}
}
