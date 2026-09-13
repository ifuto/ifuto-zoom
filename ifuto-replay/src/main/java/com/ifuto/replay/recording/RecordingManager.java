package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 録画の開始・停止・しおり付けをまとめて持つ。
 *
 * <p>開始/停止はクライアントスレッドから、パケットの記録は Netty（受信）とクライアント（送信）
 * の両方から来るので、現在の録画は volatile な1個のフィールドで持ち替えるだけにしている。
 */
public final class RecordingManager {
	public static final RecordingManager INSTANCE = new RecordingManager();

	private volatile RecordingSession session;

	private RecordingManager() {
	}

	public RecordingSession getSession() {
		return this.session;
	}

	public boolean isRecording() {
		return this.session != null;
	}

	// --- パケットの受け口（Mixin から呼ばれる） ---

	/** サーバー → クライアント */
	public void onInboundPacket(ClientConnection connection, Packet<?> packet) {
		RecordingSession current = this.session;

		if (current == null || packet == null || connection == null) {
			return;
		}

		PacketListener listener = connection.getPacketListener();

		if (listener instanceof ClientPlayNetworkHandler handler) {
			current.capture(packet, false, handler);
		}
	}

	/** クライアント → サーバー（自分の操作） */
	public void onOutboundPacket(ClientConnection connection, Packet<?> packet) {
		RecordingSession current = this.session;

		if (current == null || packet == null || connection == null) {
			return;
		}

		PacketListener listener = connection.getPacketListener();

		if (listener instanceof ClientPlayNetworkHandler handler) {
			current.capture(packet, true, handler);
		}
	}

	// --- 操作 ---

	/** サーバーに入ったとき。自動録画がオンなら開始する */
	public void onJoin(MinecraftClient client, ClientPlayNetworkHandler handler) {
		if (ReplayConfig.get().autoRecord) {
			start(client, handler);
		}
	}

	public synchronized boolean start(MinecraftClient client, ClientPlayNetworkHandler handler) {
		if (this.session != null) {
			return false;
		}

		if (handler == null || client == null) {
			notify(client, "ifuto-replay.message.not_in_game");
			return false;
		}

		ReplayConfig config = ReplayConfig.get();
		Path directory = ReplayConfig.getSaveDirectory();

		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] 保存フォルダ {} を作れませんでした", directory, e);
			notify(client, "ifuto-replay.message.io_error");
			return false;
		}

		long now = System.currentTimeMillis();
		Path file = uniqueFile(directory, now);
		RecordingSession created;

		try {
			created = new RecordingSession(file, config, minecraftVersion(), serverName(client), playerName(client), now);
			created.bind(handler);

			if (!created.hasEncoder()) {
				notify(client, "ifuto-replay.message.io_error");
				return false;
			}

			created.start();
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] {} を開けませんでした", file, e);
			notify(client, "ifuto-replay.message.io_error");
			return false;
		}

		this.session = created;
		notify(client, "ifuto-replay.message.started", Text.literal(file.getFileName().toString()));
		return true;
	}

	public synchronized void stop(MinecraftClient client) {
		RecordingSession current = this.session;

		if (current == null) {
			return;
		}

		this.session = null;
		RecordingSession.Stats stats = current.finish();
		IfutoReplayClient.LOGGER.info("[ifuto-replay] {} を保存しました ({} パケット, 破棄 {}, 失敗 {})",
				stats.file().getFileName(), stats.packets(), stats.dropped(), stats.errors());

		notify(client, "ifuto-replay.message.saved",
				Text.literal(stats.file().getFileName().toString()),
				Text.literal(formatSize(stats.bytes())),
				Text.literal(formatDuration(stats.durationMs())),
				Text.literal(String.valueOf(stats.packets())));
	}

	public void toggle(MinecraftClient client) {
		if (this.isRecording()) {
			this.stop(client);
		} else {
			this.start(client, client == null ? null : client.getNetworkHandler());
		}
	}

	/** しおりを付ける（あとで再生・書き出しの起点にする） */
	public void addMarker(MinecraftClient client, String name) {
		RecordingSession current = this.session;

		if (current == null) {
			return;
		}

		current.addMarker(name);
		notify(client, "ifuto-replay.message.marker");
	}

	/** 毎ティックの上限チェック */
	public void tick(MinecraftClient client) {
		RecordingSession current = this.session;

		if (current == null) {
			return;
		}

		if (current.isOverDuration() || current.isLimitReached()) {
			this.stop(client);
		}
	}

	// --- おしらせ ---

	private static void notify(MinecraftClient client, String key, Object... args) {
		if (client == null || client.player == null || !ReplayConfig.get().notifyChat) {
			return;
		}

		client.player.sendMessage(Text.translatable(key, args), false);
	}

	// --- いろいろ ---

	private static Path uniqueFile(Path directory, long now) {
		LocalDateTime time = LocalDateTime.now();
		String base = String.format(Locale.ROOT, "%04d-%02d-%02d_%02d-%02d-%02d",
				time.getYear(), time.getMonthValue(), time.getDayOfMonth(),
				time.getHour(), time.getMinute(), time.getSecond());

		Path file = directory.resolve(base + ReplayFormat.FILE_EXTENSION);
		int suffix = 2;

		while (Files.exists(file) && suffix < 1000) {
			file = directory.resolve(base + "_" + suffix + ReplayFormat.FILE_EXTENSION);
			suffix++;
		}

		return file;
	}

	private static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private static String serverName(MinecraftClient client) {
		ServerInfo info = client.getCurrentServerEntry();

		if (info != null && info.address != null && !info.address.isBlank()) {
			return info.address;
		}

		return "singleplayer";
	}

	private static String playerName(MinecraftClient client) {
		try {
			return client.getSession().getUsername();
		} catch (Throwable t) {
			return "unknown";
		}
	}

	/** 12.3 MB みたいな表示 */
	public static String formatSize(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}

		if (bytes < 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0D);
		}

		if (bytes < 1024L * 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0D * 1024.0D));
		}

		return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0D * 1024.0D * 1024.0D));
	}

	/** 1:02:03 みたいな表示 */
	public static String formatDuration(long millis) {
		long totalSeconds = Math.max(0L, millis) / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;

		if (hours > 0L) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}

		return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}
}
