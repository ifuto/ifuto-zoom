package com.ifuto.replay.mixin;

import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * スクリーンショットの画素を「複写なし」で読む口。
 *
 * <p>書き出しでは1枚ごとに数十MBの絵を ffmpeg へ渡す。公開 API
 * （{@code copyPixelsAbgr}）だと1枚ごとに int 配列を作り直すので、
 * 中身の番地を直接もらって使い回しの置き場へ流し込む。
 * 読むだけ・書かない（書き換えるとバニラ側が壊れる）。
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
	@Accessor("pointer")
	long ifutoReplay$getPointer();
}
