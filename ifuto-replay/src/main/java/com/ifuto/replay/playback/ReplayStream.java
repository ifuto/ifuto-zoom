package com.ifuto.replay.playback;

import com.ifuto.replay.recording.ReplayFormat;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * .ifreplay を先頭から順に読む。
 *
 * <p>録画と同じく「先頭から順に 1 回読むだけ」。巻き戻しはファイルを最初から読み直す。
 * 本体（パケットの中身）は必要になった分だけ展開するので、大きなファイルでも
 * 先読みでメモリが膨らまない。
 */
public final class ReplayStream implements Closeable {
	/** ファイルの先頭にある情報 */
	public record Header(int version, int flags, String mcVersion, long startedAt, String serverName, String playerName) {
		public boolean hasC2S() {
			return (this.flags & ReplayFormat.FLAG_HAS_C2S) != 0;
		}
	}

	/** しおり */
	public record Marker(long timeMs, String name) {
	}

	/** 再生前にわかる情報（マーカー・総時間など） */
	public record Metadata(Header header, long durationMs, List<Marker> markers, boolean hasRegistries) {
	}

	/** 読み進めた結果を受け取る側 */
	public interface Sink {
		/** パケット種類の定義が出てきた */
		void packetType(int index, int direction, String name);

		/** パケット本体が出てきた */
		void packet(long timeMs, int typeIndex, byte[] payload, int length);

		/** しおりが出てきた */
		void marker(long timeMs, String name);

		/** パケットにならない操作（マウス・キー・画面）が出てきた */
		void input(long timeMs, int subtype, byte[] data, int length);

		/** クライアントの内側でだけ起きた出来事（パーティクルなど）が出てきた */
		default void local(long timeMs, int subtype, byte[] data, int length) {
			// 古い再生側はそのまま読み飛ばす
		}
	}

	private final InputStream source;
	private final PushbackInputStream pushback;
	private final DataInputStream in;
	private final Header header;
	private final NbtCompound registries;

	/** 圧縮がパケットをまたいで辞書を共有しているか（古い形式。このときは順番に展開する必要がある） */
	private final boolean sharedWindow;

	/** パケットがかたまり（{@link ReplayFormat#TAG_BLOCK}）にまとまっているか（新しい形式） */
	private final boolean blocked;

	/** 共有窓のときの展開器（ファイルの先頭から1本つながっている） */
	private Inflater inflater;

	/** いま読んでいるかたまりの、展開済みの中身 */
	private byte[] blockBytes;

	/** かたまりの、次に読む場所 */
	private int blockPos;

	/** かたまりの終わり */
	private int blockLimit;

	private final byte[] scratch = new byte[512];

	private long timeMs;
	private boolean ended;

	/**
	 * 本番の読み込みの前に、マーカーと総時間だけ拾う。
	 *
	 * <p>パケットの中身は読み飛ばす（展開もしない）ので、ファイルが大きくてもそれなりに速い。
	 */
	public static Metadata preflight(Path file) throws IOException {
		try (ReplayStream stream = new ReplayStream(file)) {
			List<Marker> markers = new ArrayList<>();
			long durationMs = 0L;

			while (!stream.ended) {
				int tag = stream.in.read();

				if (tag < 0 || tag == ReplayFormat.TAG_END) {
					break;
				}

				switch (tag) {
					case ReplayFormat.TAG_PACKET_TYPE -> {
						stream.readVarInt();
						stream.in.readByte();
						stream.readString();
					}
					case ReplayFormat.TAG_PACKET -> {
						stream.timeMs += stream.readVarInt();
						stream.readVarInt();
						int length = stream.readVarInt();
						int method = stream.in.readByte();

						if (method == ReplayFormat.METHOD_DEFLATE) {
							stream.consumeDeflated(length, stream.readVarInt());
						} else {
							stream.skipExactly(length);
						}
					}
					case ReplayFormat.TAG_MARKER -> {
						stream.timeMs += stream.readVarInt();
						markers.add(new Marker(stream.timeMs, stream.readString()));
					}
					case ReplayFormat.TAG_INPUT -> {
						stream.timeMs += stream.readVarInt();
						stream.in.readByte();
						stream.skipExactly(stream.readVarInt());
					}
					case ReplayFormat.TAG_LOCAL -> {
						stream.timeMs += stream.readVarInt();
						stream.readVarInt();
						stream.skipExactly(stream.readVarInt());
					}
					case ReplayFormat.TAG_BLOCK -> {
						int rawLength = stream.readVarInt();
						int method = stream.in.readByte();
						int stored = method == ReplayFormat.METHOD_DEFLATE ? stream.readVarInt() : rawLength;

						// かたまりは1個で完結しているので、時刻を知りたいだけなら展開しなくてよい
						stream.skipExactly(stored);
					}
					case ReplayFormat.TAG_INDEX -> durationMs = stream.readIndex();
					case ReplayFormat.TAG_REGISTRIES -> {
						int packed = stream.readVarInt();
						stream.consumeDeflated(packed, stream.readVarInt());
					}
					default -> throw new IOException("不明なフレーム: " + tag);
				}
			}

			return new Metadata(stream.header, Math.max(durationMs, stream.timeMs), markers,
					stream.registries != null);
		}
	}

	public ReplayStream(Path file) throws IOException {
		this.source = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
		this.pushback = new PushbackInputStream(this.source, 8);
		this.in = new DataInputStream(this.pushback);

		// --- ヘッダ ---
		byte[] magic = new byte[ReplayFormat.MAGIC.length];
		this.in.readFully(magic);

		for (int i = 0; i < ReplayFormat.MAGIC.length; i++) {
			if (magic[i] != ReplayFormat.MAGIC[i]) {
				throw new IOException("ifuto-replay のファイルではありません");
			}
		}

		int version = this.readVarInt();
		int flags = this.readVarInt();
		this.blocked = (flags & ReplayFormat.FLAG_BLOCK_DEFLATE) != 0;
		this.sharedWindow = !this.blocked && (flags & ReplayFormat.FLAG_SHARED_DEFLATE) != 0;

		if (this.blocked || this.sharedWindow) {
			this.inflater = new Inflater();
		}

		String mcVersion = this.readString();
		long startedAt = this.in.readLong();
		String serverName = this.readString();
		String playerName = this.readString();
		this.header = new Header(version, flags, mcVersion, startedAt, serverName, playerName);

		// --- レジストリ（あれば） ---
		NbtCompound registries = null;
		int tag = this.in.read();

		if (tag == ReplayFormat.TAG_REGISTRIES) {
			byte[] packed = new byte[this.readVarInt()];
			this.in.readFully(packed);
			int rawLength = this.readVarInt();
			byte[] raw = inflate(packed, rawLength);

			try (DataInputStream nbtIn = new DataInputStream(new ByteArrayInputStream(raw))) {
				registries = NbtIo.readCompound(nbtIn);
			}
		} else if (tag >= 0) {
			this.pushback.unread(tag);
		}

		this.registries = registries;
	}

	public Header header() {
		return this.header;
	}

	public NbtCompound registries() {
		return this.registries;
	}

	public boolean isEnded() {
		return this.ended;
	}

	/**
	 * 次の「パケット」を 1 つ読む。種類定義・しおりなどはその場で sink に流して続ける。
	 *
	 * @return パケットを 1 つ読めたら true。終わりまで来たら false
	 */
	public boolean readNext(Sink sink) throws IOException {
		while (!this.ended) {
			// かたまりの中身が残っていたら、まずそれを出す（ファイルを読まない）
			if (this.blockPos < this.blockLimit) {
				return this.readFromBlock(sink);
			}

			int tag = this.in.read();

			if (tag < 0 || tag == ReplayFormat.TAG_END) {
				this.ended = true;
				return false;
			}

			switch (tag) {
				case ReplayFormat.TAG_PACKET_TYPE -> sink.packetType(this.readVarInt(), this.in.readByte(), this.readString());
				case ReplayFormat.TAG_MARKER -> {
					this.timeMs += this.readVarInt();
					sink.marker(this.timeMs, this.readString());
				}
				case ReplayFormat.TAG_PACKET -> {
					this.timeMs += this.readVarInt();
					int typeIndex = this.readVarInt();
					int length = this.readVarInt();
					int method = this.in.readByte();
					byte[] payload;

					if (method == ReplayFormat.METHOD_DEFLATE) {
						int rawLength = this.readVarInt();
						byte[] packed = new byte[length];
						this.in.readFully(packed);
						payload = inflate(packed, rawLength);
					} else {
						payload = new byte[length];
						this.in.readFully(payload);
					}

					sink.packet(this.timeMs, typeIndex, payload, payload.length);
					return true;
				}
				case ReplayFormat.TAG_INPUT -> {
					this.timeMs += this.readVarInt();
					int subtype = this.in.readByte();
					int length = this.readVarInt();
					byte[] data = new byte[length];
					this.in.readFully(data);
					sink.input(this.timeMs, subtype, data, length);
				}
				case ReplayFormat.TAG_LOCAL -> {
					this.timeMs += this.readVarInt();
					int subtype = this.readVarInt();
					int length = this.readVarInt();
					byte[] data = new byte[length];
					this.in.readFully(data);
					sink.local(this.timeMs, subtype, data, length);
				}
					case ReplayFormat.TAG_BLOCK -> this.readBlock();
					case ReplayFormat.TAG_INDEX -> this.readIndex();
				case ReplayFormat.TAG_REGISTRIES -> {
					int packed = this.readVarInt();
					this.consumeDeflated(packed, this.readVarInt());
				}
				default -> throw new IOException("不明なフレーム: " + tag);
			}
		}

		return false;
	}

	@Override
	public void close() {
		Inflater opened = this.inflater;
		this.inflater = null;

		if (opened != null) {
			opened.end();
		}

		try {
			this.in.close();
		} catch (IOException e) {
			// 閉じるだけなので無視
		}
	}

	// --- 中身 ---

	/**
	 * かたまり（{@link ReplayFormat#TAG_BLOCK}）を読んで、中身を記憶する。
	 *
	 * <p>ここで展開した中身は {@link #readFromBlock(Sink)} が1個ずつ読む。
	 * かたまりは1個で完結しているので、読み飛ばしてもあとに影響しない。
	 */
	private void readBlock() throws IOException {
		int rawLength = this.readVarInt();
		int method = this.in.readByte();
		byte[] raw;

		if (method == ReplayFormat.METHOD_DEFLATE) {
			int packedLength = this.readVarInt();
			byte[] packed = new byte[packedLength];
			this.in.readFully(packed);
			raw = this.inflate(packed, rawLength);
		} else {
			raw = new byte[rawLength];
			this.in.readFully(raw);
		}

		this.blockBytes = raw;
		this.blockPos = 0;
		this.blockLimit = rawLength;
	}

	/** かたまりの中身からパケットを1個読む */
	private boolean readFromBlock(Sink sink) throws IOException {
		this.timeMs += this.readBlockVarInt();
		int typeIndex = this.readBlockVarInt();
		int length = this.readBlockVarInt();

		if (length < 0 || length > this.blockLimit - this.blockPos) {
			throw new IOException("かたまりが途中で終わっています");
		}

		byte[] payload = new byte[length];
		System.arraycopy(this.blockBytes, this.blockPos, payload, 0, length);
		this.blockPos += length;
		sink.packet(this.timeMs, typeIndex, payload, length);
		return true;
	}

	/** かたまりの中から可変長intを読む */
	private int readBlockVarInt() throws IOException {
		int result = 0;

		for (int shift = 0; shift < 35; shift += 7) {
			if (this.blockPos >= this.blockLimit) {
				throw new IOException("かたまりが途中で終わっています");
			}

			int b = this.blockBytes[this.blockPos++];
			result |= (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				return result;
			}
		}

		throw new IOException("可変長intが壊れています");
	}

	/** シーク用の目印（総時間が欲しいので読む） */
	private long readIndex() throws IOException {
		int count = this.readVarInt();

		for (int i = 0; i < count; i++) {
			this.readVarInt();
			this.readVarLong();
		}

		return this.readVarLong();
	}

	private void skipExactly(int count) throws IOException {
		int skipped = 0;

		while (skipped < count) {
			int read = this.in.skipBytes(count - skipped);

			if (read <= 0) {
				throw new IOException("ファイルが途中で終わっています");
			}

			skipped += read;
		}
	}

	private String readString() throws IOException {
		byte[] bytes = new byte[this.readVarInt()];
		this.in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private int readVarInt() throws IOException {
		int result = 0;

		for (int shift = 0; shift < 35; shift += 7) {
			int b = this.in.readByte();
			result |= (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				return result;
			}
		}

		throw new IOException("可変長intが壊れています");
	}

	private long readVarLong() throws IOException {
		long result = 0L;

		for (int shift = 0; shift < 70; shift += 7) {
			int b = this.in.readByte();
			result |= (long) (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				return result;
			}
		}

		throw new IOException("可変長longが壊れています");
	}

	/** 中身は要らないが、**共有窓の状態を進める** ために展開する（読み飛ばすとずれる） */
	void consumeDeflated(int packedLength, int rawLength) throws IOException {
		if (!this.sharedWindow) {
			this.skipExactly(packedLength);
			return;
		}

		byte[] packed = new byte[packedLength];
		this.in.readFully(packed);
		this.inflate(packed, rawLength);
	}

	private byte[] inflate(byte[] packed, int rawLength) throws IOException {
		if (this.blocked) {
			// かたまりは1個で完結した deflate なので、使う前に必ずまっさらにする
			Inflater inflater = this.inflater;
			inflater.reset();
			inflater.setInput(packed);
			return this.inflateInto(inflater, rawLength);
		}

		if (!this.sharedWindow) {
			return inflateStandalone(packed, rawLength);
		}

		Inflater shared = this.inflater;
		shared.setInput(packed);
		return this.inflateInto(shared, rawLength);
	}

	private byte[] inflateInto(Inflater inflater, int rawLength) throws IOException {
		byte[] raw = new byte[rawLength];
		int read = 0;

		while (read < rawLength) {
			int n;

			try {
				n = inflater.inflate(raw, read, rawLength - read);
			} catch (DataFormatException e) {
				throw new IOException("圧縮が壊れています", e);
			}

			if (n == 0) {
				if (inflater.needsInput() || inflater.finished()) {
					throw new IOException("圧縮が途中で終わっています");
				}
			}

			read += n;
		}

		// 区切り（同期マーカー）が入力に残っていることがある。次の setInput で
		// 捨てられてしまうので、ここで読み切っておく
		while (inflater.getRemaining() > 0) {
			int n;

			try {
				n = inflater.inflate(this.scratch);
			} catch (DataFormatException e) {
				throw new IOException("圧縮が壊れています", e);
			}

			if (n == 0) {
				break;
			}
		}

		return raw;
	}

	/** ふるい形式（パケットごとに独立した圧縮） */
	private static byte[] inflateStandalone(byte[] packed, int rawLength) throws IOException {
		byte[] raw = new byte[rawLength];

		try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(packed))) {
			int read = 0;

			while (read < rawLength) {
				int n = inflater.read(raw, read, rawLength - read);

				if (n < 0) {
					throw new IOException("圧縮が途中で終わっています");
				}

				read += n;
			}
		}

		return raw;
	}
}
