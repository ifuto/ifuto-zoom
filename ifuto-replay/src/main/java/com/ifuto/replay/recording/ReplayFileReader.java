package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.config.ReplayConfig;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 保存済みの .ifreplay を「一覧表示のために少しだけ」読む。
 *
 * <p>読むのはヘッダと、ファイル末尾の巻末データだけ。
 * パケット本体は1バイトも読まないので、何GBあっても一覧は一瞬で出る。
 */
public final class ReplayFileReader {
	/** 壊れたファイルを読んで暴走しないための上限 */
	private static final int MAX_STRING_LENGTH = 4096;
	private static final int MAX_INDEX_ENTRIES = 5_000_000;

	private ReplayFileReader() {
	}

	/** 保存フォルダの中の .ifreplay を新しい順に読む。読めないものは飛ばす */
	public static List<Info> listRecordings(Path directory) {
		List<Info> result = new ArrayList<>();

		if (directory == null || !Files.isDirectory(directory)) {
			return result;
		}

		try (Stream<Path> stream = Files.list(directory)) {
			stream.filter(path -> Files.isRegularFile(path)
							&& path.getFileName().toString().endsWith(ReplayFormat.FILE_EXTENSION))
					.forEach(path -> {
						Info info = read(path);

						if (info != null) {
							result.add(info);
						}
					});
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] {} を一覧できませんでした", directory, e);
		}

		result.sort(Comparator.comparingLong(Info::startedAt).reversed());
		return result;
	}

	/** 設定された保存フォルダから一覧を作る */
	public static List<Info> listRecordings() {
		return listRecordings(ReplayConfig.getSaveDirectory());
	}

	/** ヘッダと巻末だけ読む。壊れていたり形式が違ったら null */
	public static Info read(Path file) {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			long fileSize = raf.length();

			if (fileSize < 16L) {
				return null;
			}

			// --- ヘッダ ---
			byte[] magic = new byte[ReplayFormat.MAGIC.length];
			raf.readFully(magic);

			if (!Arrays.equals(magic, ReplayFormat.MAGIC)) {
				return null;
			}

			int version = readVarInt(raf);
			int flags = readVarInt(raf);
			String mcVersion = readString(raf);
			long startedAt = readFixedLong(raf);
			String serverName = readString(raf);
			String playerName = readString(raf);

			// --- 巻末（末尾12バイト: マジック + インデックスの位置） ---
			long durationMs = -1L;

			if (fileSize >= 16L) {
				raf.seek(fileSize - 12L);

				byte[] tailMagic = new byte[ReplayFormat.MAGIC.length];
				raf.readFully(tailMagic);

				if (Arrays.equals(tailMagic, ReplayFormat.MAGIC)) {
					long indexOffset = readFixedLong(raf);

					if (indexOffset > 0L && indexOffset < fileSize) {
						raf.seek(indexOffset);
						int tag = readVarInt(raf);

						if (tag == ReplayFormat.TAG_INDEX) {
							int count = readVarInt(raf);

							if (count >= 0 && count < MAX_INDEX_ENTRIES) {
								for (int i = 0; i < count; i++) {
									readVarInt(raf);
									readVarLong(raf);
								}

								durationMs = readVarLong(raf);
							}
						}
					}
				}
			}

			return new Info(file, file.getFileName().toString(), version, mcVersion, serverName, playerName,
					startedAt, (flags & ReplayFormat.FLAG_HAS_C2S) != 0, durationMs, fileSize);
		} catch (IOException | RuntimeException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] {} を読めませんでした", file, e);
			return null;
		}
	}

	// --- 読み取り用の小さな道具 ---

	private static int readVarInt(RandomAccessFile raf) throws IOException {
		int result = 0;
		int shift = 0;

		while (true) {
			int value = raf.readByte() & 0xFF;

			if (value == -1) {
				throw new IOException("unexpected end of file");
			}

			result |= (value & 0x7F) << shift;

			if ((value & 0x80) == 0) {
				return result;
			}

			shift += 7;

			if (shift > 35) {
				throw new IOException("varint too long");
			}
		}
	}

	private static long readVarLong(RandomAccessFile raf) throws IOException {
		long result = 0L;
		int shift = 0;

		while (true) {
			int value = raf.readByte() & 0xFF;

			if (value == -1) {
				throw new IOException("unexpected end of file");
			}

			result |= (long) (value & 0x7F) << shift;

			if ((value & 0x80) == 0) {
				return result;
			}

			shift += 7;

			if (shift > 70) {
				throw new IOException("varlong too long");
			}
		}
	}

	private static long readFixedLong(RandomAccessFile raf) throws IOException {
		return raf.readLong();
	}

	private static String readString(RandomAccessFile raf) throws IOException {
		int length = readVarInt(raf);

		if (length < 0 || length > MAX_STRING_LENGTH) {
			throw new IOException("bad string length: " + length);
		}

		byte[] bytes = new byte[length];
		raf.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** 一覧に出す分だけの情報 */
	public record Info(Path file, String fileName, int version, String mcVersion, String serverName,
					   String playerName, long startedAt, boolean hasC2S, long durationMs, long fileSize) {

		/** 時間が分からない古いファイル用 */
		public boolean hasDuration() {
			return this.durationMs >= 0L;
		}
	}
}
