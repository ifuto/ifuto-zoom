package com.ifuto.fastplace.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * 背景を「プレイ画面そのまま + 軽いブラー + ほんの少しだけ暗く」にする土台。
 *
 * <p>バニラの {@code renderBackground} は 74% の黒（{@code 0xC0101010}）で塗り潰す。
 * それだと世界が見えず、ClickGUI が「別の画面」に見えてしまう。ここでは
 * ブラーだけバニラに任せ、暗さは 20% に落とす。ワールドに居ないとき
 * （タイトル画面など）は、これまで通りバニラの背景に任せる。</p>
 */
public abstract class GlassScreen extends Screen {
	/** 背景を暗くする量（20%）。上げるほど世界が見えなくなる */
	private static final int DIM = 0x33000000;

	protected GlassScreen(Text title) {
		super(title);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		if (this.client != null && this.client.world != null) {
			applyBlur(context);                                        // 軽いブラー（バニラのもの）
			context.fill(0, 0, this.width, this.height, DIM);          // ほんのちょっと暗く
			return;
		}

		super.renderBackground(context, mouseX, mouseY, delta);
	}
}
