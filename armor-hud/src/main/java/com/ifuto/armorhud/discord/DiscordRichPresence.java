package com.ifuto.armorhud.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Discord のローカル IPC (discord-ipc-N) に直接つないで Rich Presence を更新する。
 * 外部ライブラリ無しの素の実装（Windows は名前付きパイプ、それ以外は UNIX ドメインソケット）。
 *
 * Client ID はパケット側から渡される。途中で変わったら古い方を消してから繋ぎ直す。
 * 送信はワーカースレッドで間引き（0.8秒に1回・最新値だけ送る）。
 */
public final class DiscordRichPresence {
	private static final Logger LOGGER = LoggerFactory.getLogger("ifuto-armor-hud");

	/** 今プレイしているゲームのアイコンに使う画像（Discord 側の mp:external 経由で取得される） */
	private static final String ICON_URL = "https://a12a12a12.pages.dev/icon.png";

	private static final long MIN_SEND_INTERVAL_MS = 800L;
	private static final long RETRY_INTERVAL_MS = 15_000L;

	private static final DiscordRichPresence INSTANCE = new DiscordRichPresence();

	public static DiscordRichPresence get() {
		return INSTANCE;
	}

	private final Object lock = new Object();

	private String requestedClientId; // 次に使いたい Client ID（null = 握っていない）
	private String latestText;
	private String sentText;
	private String activeClientId; // いまの接続で handshake した Client ID
	private boolean forceSend;
	private boolean started;
	private volatile boolean running;
	private Connection connection;
	private long nextConnectAt;
	private final String iconSessionKey = UUID.randomUUID().toString().replace("-", "");

	private DiscordRichPresence() {
	}

	/** サーバーから (clientId, text) が届いたときに呼ぶ（text が空ならクリア）。 */
	public void submit(String clientId, String text) {
		synchronized (this.lock) {
			this.requestedClientId = clientId;
			this.latestText = text;

			if (!this.started) {
				this.started = true;
				this.running = true;
				Thread worker = new Thread(this::loop, "ifuto-armor-hud-discord-rpc");
				worker.setDaemon(true);
				worker.start();
			}

			this.lock.notifyAll();
		}
	}

	/** いま出している内容を強制的に送り直す。他modに上書きされたときの押し戻し用。 */
	public void refresh() {
		synchronized (this.lock) {
			if (this.sentText == null || this.sentText.isEmpty()) {
				return;
			}

			this.forceSend = true;
			this.lock.notifyAll();
		}
	}

	/** サーバーから抜けたときに呼ぶ。プレゼンスを消して接続は畳む。 */
	public void release() {
		synchronized (this.lock) {
			this.requestedClientId = null;
			this.latestText = null;
			this.lock.notifyAll();
		}
	}

	/** ゲーム終了時に呼ぶ。 */
	public void shutdown() {
		this.running = false;

		synchronized (this.lock) {
			this.lock.notifyAll();
		}
	}

	private void loop() {
		while (this.running) {
			String clientId;
			String text;
			boolean force;

			synchronized (this.lock) {
				clientId = this.requestedClientId;
				text = this.latestText;
				force = this.forceSend;
			}

			// 出すものが無い間はプレゼンスを消して接続も畳んで待つ
			if (clientId == null || text == null) {
				this.clearPresence();
				this.closeConnection();
				this.activeClientId = null;

				if (!this.sleepUninterrupted(500L)) {
					break;
				}

				continue;
			}

			// Client ID が変わったら古い内容を消してから繋ぎ直す
			if (this.connection != null && !clientId.equals(this.activeClientId)) {
				this.clearPresence();
				this.closeConnection();
				this.activeClientId = null;
			}

			if (!force && text.equals(this.sentText)) {
				if (!this.sleepUninterrupted(500L)) {
					break;
				}

				continue;
			}

			long now = System.currentTimeMillis();

			if (this.connection == null) {
				if (now < this.nextConnectAt) {
					if (!this.sleepUninterrupted(this.nextConnectAt - now)) {
						break;
					}

					continue;
				}

				this.connection = this.tryConnect(clientId);

				if (this.connection == null) {
					this.nextConnectAt = System.currentTimeMillis() + RETRY_INTERVAL_MS;
					continue;
				}

				this.activeClientId = clientId;
				LOGGER.info("[ifuto-armor-hud] Discord に接続しました");
			}

			try {
				this.connection.send(this.buildSetActivityJson(text));

				synchronized (this.lock) {
					this.sentText = text;
					this.forceSend = false;
				}

				if (!this.sleepUninterrupted(MIN_SEND_INTERVAL_MS)) {
					break;
				}
			} catch (IOException e) {
				LOGGER.info("[ifuto-armor-hud] Discord との接続が切れました ({})", e.toString());
				this.closeConnection();
				this.activeClientId = null;
				this.nextConnectAt = System.currentTimeMillis() + RETRY_INTERVAL_MS;
			}
		}

		// 終了時はプレゼンスを消してから閉じる
		this.clearPresence();
		this.closeConnection();
	}

	private void clearPresence() {
		Connection conn = this.connection;

		if (conn == null || this.sentText == null || this.sentText.isEmpty()) {
			return;
		}

		try {
			conn.send(this.buildSetActivityJson(""));
		} catch (IOException ignored) {
			// 終了時なので失敗しても気にしない
		}

		this.sentText = null;
	}

	private void closeConnection() {
		if (this.connection != null) {
			try {
				this.connection.close();
			} catch (IOException ignored) {
			}

			this.connection = null;
		}
	}

	private boolean sleepUninterrupted(long millis) {
		synchronized (this.lock) {
			try {
				this.lock.wait(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			return this.running;
		}
	}

	private Connection tryConnect(String clientId) {
		String id = clientId.trim();

		if (id.isEmpty()) {
			return null;
		}

		for (int i = 0; i < 10; i++) {
			Connection conn = this.openPipe(i);

			if (conn == null) {
				continue;
			}

			try {
				conn.handshake(id);
				return conn;
			} catch (IOException e) {
				try {
					conn.close();
				} catch (IOException ignored) {
				}
			}
		}

		return null;
	}

	private Connection openPipe(int index) {
		String os = System.getProperty("os.name", "").toLowerCase();

		if (os.contains("win")) {
			try {
				return new WindowsConnection("\\\\.\\pipe\\discord-ipc-" + index);
			} catch (IOException e) {
				return null;
			}
		}

		// Linux/macOS はこのへんのディレクトリに discord-ipc-N が並ぶ
		String[] dirs = {
				System.getenv("XDG_RUNTIME_DIR"),
				System.getenv("TMPDIR"),
				System.getenv("TMP"),
				System.getenv("TEMP"),
				"/tmp"
		};

		for (String dir : dirs) {
			if (dir == null || dir.isEmpty()) {
				continue;
			}

			Path path = Path.of(dir, "discord-ipc-" + index);

			if (!Files.exists(path)) {
				continue;
			}

			try {
				return new UnixConnection(path);
			} catch (IOException ignored) {
			}
		}

		return null;
	}

	// SET_ACTIVITY コマンドの JSON。空文字なら activity=null でクリア
	private String buildSetActivityJson(String text) {
		long pid = ProcessHandle.current().pid();
		String nonce = UUID.randomUUID().toString();
		StringBuilder activity;

		if (text.isEmpty()) {
			activity = new StringBuilder("null");
		} else {
			activity = new StringBuilder("{");
			activity.append("\"details\":\"").append(jsonEscape(text)).append("\"");
			activity.append(",\"assets\":{\"large_image\":\"").append(this.externalIconKey()).append("\",\"large_text\":\"ifuto mods\"}");
			activity.append("}");
		}

		return "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid + ",\"activity\":" + activity + "},\"nonce\":\"" + nonce + "\"}";
	}

	// Discord の外部画像プロキシ形式。アプリ側にアセット登録しなくても URL の画像が使える
	private String externalIconKey() {
		return "mp:external/" + this.iconSessionKey + "/" + URLEncoder.encode(ICON_URL, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String jsonEscape(String text) {
		StringBuilder sb = new StringBuilder(text.length() + 16);

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}

		return sb.toString();
	}

	private abstract static class Connection {
		abstract void sendFrame(int op, String json) throws IOException;
		abstract String readFrame(long deadlineMillis) throws IOException;
		abstract void close() throws IOException;

		void handshake(String clientId) throws IOException {
			sendFrame(0, "{\"v\":1,\"client_id\":\"" + clientId + "\",\"nonce\":\"" + UUID.randomUUID() + "\"}");
			// Discord がREADYを返すのを少しだけ待つ。返らなくても一応続行（書けているならOK）
			this.readFrame(1000L);
		}

		void send(String json) throws IOException {
			sendFrame(1, json);
		}

		static ByteBuffer frameHeader(int op, int length) {
			ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			header.putInt(op);
			header.putInt(length);
			header.flip();
			return header;
		}
	}

	private static final class UnixConnection extends Connection {
		private final SocketChannel channel;

		UnixConnection(Path path) throws IOException {
			this.channel = SocketChannel.open(UnixDomainSocketAddress.of(path));
			this.channel.configureBlocking(false);
		}

		@Override
		void sendFrame(int op, String json) throws IOException {
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			ByteBuffer buf = ByteBuffer.allocate(8 + bytes.length);
			buf.put(frameHeader(op, bytes.length));
			buf.put(bytes);
			buf.flip();

			while (buf.hasRemaining()) {
				if (this.channel.write(buf) < 0) {
					throw new IOException("channel closed");
				}
			}
		}

		@Override
		String readFrame(long deadlineMillis) throws IOException {
			ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

			if (!readFully(header, deadlineMillis)) {
				return null;
			}

			header.flip();
			header.getInt(); // op
			int length = header.getInt();

			if (length <= 0 || length > 1_000_000) {
				throw new IOException("bad frame length: " + length);
			}

			ByteBuffer body = ByteBuffer.allocate(length);

			if (!readFully(body, deadlineMillis)) {
				return null;
			}

			return new String(body.array(), StandardCharsets.UTF_8);
		}

		private boolean readFully(ByteBuffer buf, long deadlineMillis) throws IOException {
			long deadline = System.currentTimeMillis() + deadlineMillis;

			while (buf.hasRemaining()) {
				int read = this.channel.read(buf);

				if (read < 0) {
					throw new IOException("channel closed");
				}

				if (read == 0) {
					if (System.currentTimeMillis() >= deadline) {
						return false;
					}

					try {
						Thread.sleep(10L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return false;
					}
				}
			}

			return true;
		}

		@Override
		void close() throws IOException {
			this.channel.close();
		}
	}

	private static final class WindowsConnection extends Connection {
		private final RandomAccessFile pipe;

		WindowsConnection(String path) throws IOException {
			this.pipe = new RandomAccessFile(path, "rw");
		}

		@Override
		void sendFrame(int op, String json) throws IOException {
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			this.pipe.write(frameHeader(op, bytes.length).array());
			this.pipe.write(bytes);
		}

		@Override
		String readFrame(long deadlineMillis) {
			// Windows 側は書けていれば十分。読み取りは省略（エラーは書き込み時に出る）
			return null;
		}

		@Override
		void close() throws IOException {
			this.pipe.close();
		}
	}
}
