package com.ifuto.replay.mixin;

import com.ifuto.replay.audio.MinecraftAudioCapture;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.Buffer;
import java.nio.IntBuffer;

/**
 * Minecraft が音を鳴らす装置を **ループバック装置** に差し替える。
 *
 * <p>差し替えるのは「録り始めたとき」だけ（{@link MinecraftAudioCapture} が合図を出している間だけ）。
 * ふだんは何もしないので、この Mod を入れているだけで音が変わることはない。
 *
 * <p>ループバック装置は音を外へ出さないので、そのままでは無音になる。
 * 取り出した音は {@link MinecraftAudioCapture} がファイルへ書きつつ、実機へ流して聞こえるようにする。
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {
	/** ループバック装置に「48kHz / ステレオ / 16bit」で鳴らしてもらうための指定 */
	private static final IntBuffer ATTRIBUTES = createAttributes();

	@Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true, require = 0)
	private static void ifutoReplay$openLoopback(String specifier, CallbackInfoReturnable<Long> cir) {
		if (!MinecraftAudioCapture.get().isArmed()) {
			return;
		}

		long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((String) null);

		if (device != 0L) {
			MinecraftAudioCapture.get().setDevice(device);
			cir.setReturnValue(device);
		}
	}

	@ModifyArg(method = "init",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"),
			index = 1, require = 0)
	private IntBuffer ifutoReplay$loopbackAttributes(IntBuffer attributes) {
		if (!MinecraftAudioCapture.get().isArmed()) {
			return attributes;
		}

		return ATTRIBUTES;
	}

	private static IntBuffer createAttributes() {
		IntBuffer buffer = BufferUtils.createIntBuffer(7);
		buffer.put(ALC10.ALC_FREQUENCY).put(MinecraftAudioCapture.SAMPLE_RATE)
				.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
				.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
				.put(0);
		((Buffer) buffer).flip();
		return buffer;
	}
}
