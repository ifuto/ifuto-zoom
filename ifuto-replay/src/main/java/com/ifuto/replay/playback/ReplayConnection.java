package com.ifuto.replay.playback;

import com.ifuto.replay.IfutoReplayClient;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

/**
 * 再生専用の「何も送らない接続」。
 *
 * <p>バニラの {@code ClientPlayNetworkHandler} は、位置情報の返信やコマンドなど
 * 何かにつけてパケットを送ろうとする。ここで全部捨てれば、サーバーとつながっていなくても
 * そのまま世界を作れる（= 録ったパケットを流し込むだけで再生できる）。
 *
 * <p>送らないだけでなく、チャンネルを持たないので「開いていない」扱いになる。
 * キューに溜めないように {@code send} を潰してある。
 */
final class ReplayConnection extends ClientConnection {
	ReplayConnection() {
		super(NetworkSide.CLIENTBOUND);
	}

	@Override
	public void send(Packet<?> packet) {
	}

	@Override
	public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
	}

	@Override
	public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
	}

	@Override
	public void tick() {
	}

	@Override
	public void disconnect(Text reason) {
		IfutoReplayClient.LOGGER.info("[ifuto-replay] 再生中に切断要求がありました（無視します）: {}", reason.getString());
	}

	@Override
	public boolean isOpen() {
		return false;
	}
}
