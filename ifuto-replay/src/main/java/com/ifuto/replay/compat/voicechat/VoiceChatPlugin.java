package com.ifuto.replay.compat.voicechat;

import com.ifuto.replay.audio.VoiceChatBridge;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Simple Voice Chat から **再生される直前の声** をもらう。
 *
 * <p>VC Mod は Minecraft とは別の出力機器を開くので、Minecraft の音だけを録っても声は入らないし、
 * PC 全体の音を録ると声以外の音まで入ってしまう。そこで公式のプラグイン API を使い、
 * **声だけ** を別の音声として録る（あとで自由に足したり外したりできる）。
 *
 * <p>このクラスは Simple Voice Chat が入っているときだけ読み込まれる。
 * 入っていなければ呼ばれないので、この Mod 単体で動かしても壊れない。
 */
public class VoiceChatPlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "ifuto-replay";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		VoiceChatBridge.markAvailable();
		registration.registerEvent(ClientReceiveSoundEvent.class,
				event -> VoiceChatBridge.get().offer(event.getRawAudio()));
	}
}
