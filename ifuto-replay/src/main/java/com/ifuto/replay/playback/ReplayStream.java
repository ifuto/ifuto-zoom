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
		boolean hasC2S() {
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
	}

	private final InputStream source;
	private final PushbackInputStream pushback;
	private final DataInputStream in;
	private final Header header;
	private final NbtCompound registries;

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
							stream.readVarInt();
						}

						stream.skipExactly(length);
					}
					case ReplayFormat.TAG_MARKER -> {
						stream.timeMs += stream.readVarInt();
						markers.add(new Marker(stream.timeMs, stream.readString()));
					}
					case ReplayFormat.TAG_INDEX -> durationMs = stream.readIndex();
					case ReplayFormat.TAG_REGISTRIES -> {
						int packed = stream.readVarInt();
						stream.readVarInt();
						stream.skipExactly(packed);
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
				case ReplayFormat.TAG_INDEX -> this.readIndex();
				case ReplayFormat.TAG_REGISTRIES -> {
					int packed = this.readVarInt();
					this.readVarInt();
					this.skipExactly(packed);
				}
				default -> throw new IOException("不明なフレーム: " + tag);
			}
		}

		return false;
	}

	@Override
	public void close() {
		try {
			this.in.close();
		} catch (IOException e) {
			// 閉じるだけなので無視
		}
	}

	// --- 中身 ---

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

	private static byte[] inflate(byte[] packed, int rawLength) throws IOException {
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
