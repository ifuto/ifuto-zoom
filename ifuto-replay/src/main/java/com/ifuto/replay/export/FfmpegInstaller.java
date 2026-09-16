package com.ifuto.replay.export;

import com.ifuto.replay.IfutoReplayClient;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ffmpeg を探す・無ければ落としてくる。
 *
 * <p>音声の録音も mp4 の書き出しも、実際の仕事は外の ffmpeg に任せている。
 * 入っていない環境ではどちらも動かないので、設定の場所になければ
 * PATH・同梱の順に探し、それでも無ければ公式ビルドを落として
 * 設定フォルダの中（`config/ifuto-replay/ffmpeg/`）に置く。
 *
 * <p>Windows は gyan.dev の公式ビルド（essentials）、macOS は evermeet.cx の
 * 公式ビルドを使う。どちらも開発元が案内している物。Linux の公式ビルドは
 * tar.xz 形式で、Java だけでは展開できないので自動では入れない
 * （パッケージマネージャで入れてもらう）。
 *
 * <p>ダウンロードは少々大きい（100MB 前後）ので、呼ぶ側はかならず
 * 裏スレッドで回し、進捗を見せること。録画の開始は止めない。
 */
public final class FfmpegInstaller {
	/** Windows: gyan.dev の公式ビルド（.sha256 も同じ場所にある） */
	private static final String WINDOWS_URL =
			"https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

	/** macOS: evermeet.cx の公式ビルド（zip の直下に ffmpeg が1個） */
	private static final String MAC_URL = "https://evermeet.cx/ffmpeg/getrelease/zip";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15L);
	private static final long VERIFY_TIMEOUT_SECONDS = 10L;

	private static final Object INSTALL_LOCK = new Object();
	private static final AtomicBoolean INSTALLING = new AtomicBoolean();

	private FfmpegInstaller() {
	}

	/** 進捗の受け口（ダウンロードのバイト数） */
	public interface Progress {
		void update(long done, long total);

		boolean isCancelled();
	}

	/** 用意に失敗したとき（理由はそのまま人に見せられる文） */
	public static final class InstallException extends IOException {
		public InstallException(String message) {
			super(message);
		}

		public InstallException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * 使う ffmpeg の場所。設定の物 → PATH → 同梱の順に探す。
	 *
	 * @return 見つかればパス（無ければ null）
	 */
	public static @Nullable String resolve(@Nullable String configPath) {
		if (configPath != null && !configPath.isBlank()) {
			String trimmed = configPath.trim();

			// 名前だけ（"ffmpeg"）なら PATH 探しへ。場所つきならそのまま見る
			if (!isBareName(trimmed)) {
				if (Files.isRegularFile(Path.of(trimmed))) {
					return trimmed;
				}
			}
		}

		String onPath = findOnPath();

		if (onPath != null) {
			return onPath;
		}

		Path bundled = bundledExecutable();

		if (Files.isRegularFile(bundled)) {
			return bundled.toAbsolutePath().toString();
		}

		return null;
	}

	/** 実際に動くか（`-version` が返ってくるか） */
	public static boolean isWorking(@Nullable String path) {
		if (path == null || path.isBlank()) {
			return false;
		}

		try {
			Process process = new ProcessBuilder(path, "-version").redirectErrorStream(true).start();
			byte[] buffer = new byte[8192];

			try (InputStream in = process.getInputStream()) {
				while (in.read(buffer) > 0) {
					// 読むだけ
				}
			}

			if (!process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}

			return process.exitValue() == 0;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			return false;
		}
	}

	/** この OS に自動インストールできるか（Linux は案内だけ） */
	public static boolean canInstall() {
		return downloadUrl() != null;
	}

	/**
	 * 無ければ裏で落としてくる（録画の開始を止めないため）。
	 *
	 * <p>すでにある・ダウンロード中のときは、ある物だけ渡して終わる。
	 * 失敗しても例外は出さない（ログだけ）。
	 */
	public static void ensureInBackground(@Nullable String configPath, Consumer<String> onInstalled) {
		String found = resolve(configPath);

		if (found != null) {
			onInstalled.accept(found);
			return;
		}

		if (downloadUrl() == null) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg が無いので音声は録れません"
					+ "（Linux はパッケージマネージャで入れて設定で場所を指定してください）");
			return;
		}

		if (!INSTALLING.compareAndSet(false, true)) {
			IfutoReplayClient.LOGGER.info("[ifuto-replay] ffmpeg をダウンロード中です");
			return;
		}

		Thread thread = new Thread(() -> {
			try {
				String installed = install(new QuietProgress());
				onInstalled.accept(installed);
			} catch (Throwable t) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg を用意できませんでした", t);
			} finally {
				INSTALLING.set(false);
			}
		}, "ifuto-replay-ffmpeg-install");

		thread.setDaemon(true);
		thread.start();
	}

	/** ダウンロード中か */
	public static boolean isInstalling() {
		return INSTALLING.get();
	}

	/**
	 * 公式ビルドを落として展開し、動くことを確かめる（重いので裏スレッドで）。
	 *
	 * @return 置いた ffmpeg のパス
	 */
	public static String install(Progress progress) throws InstallException {
		synchronized (INSTALL_LOCK) {
			return installLocked(progress);
		}
	}

	private static String installLocked(Progress progress) throws InstallException {
		String url = downloadUrl();

		if (url == null) {
			throw new InstallException("この OS には自動で入れられません"
					+ "（ffmpeg を入れて設定で場所を指定してください）");
		}

		// 別口で先に入っていたらそれを使う
		Path target = bundledExecutable();
		String already = resolve(null);

		if (already != null && isWorking(already)) {
			return already;
		}

		try {
			Files.createDirectories(target.getParent());
		} catch (IOException e) {
			throw new InstallException("置き場を作れませんでした", e);
		}

		Path temporary = target.getParent().resolve("ffmpeg-download.tmp");

		try {
			progress.update(0L, 1L);
			download(url, temporary, progress);

			if (progress.isCancelled()) {
				throw new InstallException("やめました");
			}

			verifyChecksum(url, temporary);
			extract(target, temporary, progress);

			if (progress.isCancelled()) {
				deleteQuietly(target);
				throw new InstallException("やめました");
			}

			if (!isWorking(target.toAbsolutePath().toString())) {
				deleteQuietly(target);
				throw new InstallException("置いた ffmpeg が動きませんでした");
			}

			IfutoReplayClient.LOGGER.info("[ifuto-replay] ffmpeg を用意しました: {}", target);
			return target.toAbsolutePath().toString();
		} catch (InstallException e) {
			throw e;
		} catch (IOException e) {
			throw new InstallException("ダウンロードできませんでした（" + e.getMessage() + "）", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InstallException("やめました", e);
		} finally {
			deleteQuietly(temporary);
		}
	}

	// --- 中身 ---

	private static @Nullable String downloadUrl() {
		return switch (os()) {
			case WINDOWS -> WINDOWS_URL;
			case MACOS -> MAC_URL;
			case LINUX -> null;
		};
	}

	/** ストリームで受けてそのまま書く（100MB をメモリに溜めない） */
	private static void download(String url, Path temporary, Progress progress)
			throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "ifuto-replay (ffmpeg auto-install)")
				.GET()
				.build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (response.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + response.statusCode());
		}

		long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

		try (InputStream in = response.body();
				OutputStream out = Files.newOutputStream(temporary,
						java.nio.file.StandardOpenOption.CREATE,
						java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			byte[] buffer = new byte[1 << 15];
			long done = 0L;
			int read;

			while ((read = in.read(buffer)) > 0) {
				if (progress.isCancelled()) {
					return;
				}

				out.write(buffer, 0, read);
				done += read;
				progress.update(done, total);
			}

			progress.update(done, Math.max(done, total));
		}
	}

	/** gyan.dev は .sha256 を置いているので、あれば照合する（無ければ素通り） */
	private static void verifyChecksum(String url, Path temporary) throws InstallException {
		if (os() != Os.WINDOWS) {
			return;
		}

		String text;

		try {
			HttpClient client = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.ALWAYS)
					.connectTimeout(CONNECT_TIMEOUT)
					.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(url + ".sha256"))
					.header("User-Agent", "ifuto-replay (ffmpeg auto-install)")
					.timeout(Duration.ofSeconds(15L))
					.GET()
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() / 100 != 2) {
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] sha256 が取れなかったので照合を省きます");
				return;
			}

			text = response.body();
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			IfutoReplayClient.LOGGER.warn("[ifuto-replay] sha256 が取れなかったので照合を省きます");
			return;
		}

		String expected = text.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
		String actual;

		try (InputStream in = Files.newInputStream(temporary)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 15];
			int read;

			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}

			StringBuilder hex = new StringBuilder();

			for (byte b : digest.digest()) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}

			actual = hex.toString();
		} catch (Exception e) {
			throw new InstallException("壊れていないか確かめられませんでした", e);
		}

		if (!actual.equals(expected)) {
			throw new InstallException("落とした物が壊れています（照合が合いません）");
		}
	}

	/** zip の中から ffmpeg 本体だけ取り出す（他はいらない） */
	private static void extract(Path target, Path temporary, Progress progress) throws InstallException {
		boolean windows = os() == Os.WINDOWS;

		try (InputStream fileIn = Files.newInputStream(temporary);
				ZipInputStream zip = new ZipInputStream(fileIn)) {
			ZipEntry entry;

			while ((entry = zip.getNextEntry()) != null) {
				if (progress.isCancelled()) {
					return;
				}

				if (entry.isDirectory() || !isWanted(entry.getName(), windows)) {
					continue;
				}

				Path parent = target.getParent();

				if (parent != null) {
					Files.createDirectories(parent);
				}

				try (OutputStream out = Files.newOutputStream(target,
						java.nio.file.StandardOpenOption.CREATE,
						java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
					byte[] buffer = new byte[1 << 15];
					int read;

					while ((read = zip.read(buffer)) > 0) {
						if (progress.isCancelled()) {
							return;
						}

						out.write(buffer, 0, read);
					}
				}

				afterExtract(target);
				return;
			}
		} catch (IOException e) {
			throw new InstallException("展開できませんでした（" + e.getMessage() + "）", e);
		}

		throw new InstallException("落とした物の中に ffmpeg がありませんでした");
	}

	private static boolean isWanted(String name, boolean windows) {
		String lower = name.toLowerCase(Locale.ROOT);

		if (windows) {
			return lower.endsWith("bin/ffmpeg.exe");
		}

		return lower.endsWith("/ffmpeg") || lower.equals("ffmpeg");
	}

	/** macOS は実行権限と隔離の解除が要る（できなくても後で動くか確かめる） */
	private static void afterExtract(Path target) {
		if (os() == Os.WINDOWS) {
			return;
		}

		try {
			Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
		} catch (IOException | UnsupportedOperationException e) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg に実行権限を付けられませんでした", e);
		}

		if (os() == Os.MACOS) {
			try {
				new ProcessBuilder("xattr", "-dr", "com.apple.quarantine",
						target.toAbsolutePath().toString()).start().waitFor(10L, TimeUnit.SECONDS);
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}

				IfutoReplayClient.LOGGER.warn("[ifuto-replay] ffmpeg の隔離を外せませんでした", e);
			}
		}
	}

	private static Path bundledExecutable() {
		String file = os() == Os.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
		return FabricLoader.getInstance().getConfigDir()
				.resolve(IfutoReplayClient.MOD_ID).resolve("ffmpeg").resolve(file);
	}

	private static boolean isBareName(String value) {
		return value.indexOf('/') < 0 && value.indexOf('\\') < 0
				&& !value.contains(File.pathSeparator) && !value.contains(":");
	}

	/** PATH の中から探す（Windows は .exe 付きも） */
	private static @Nullable String findOnPath() {
		String path = System.getenv("PATH");

		if (path == null || path.isBlank()) {
			return null;
		}

		boolean windows = os() == Os.WINDOWS;

		for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
			if (directory.isBlank()) {
				continue;
			}

			Path candidate = Path.of(directory.trim(), windows ? "ffmpeg.exe" : "ffmpeg");

			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath().toString();
			}
		}

		return null;
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// 消せなくても害はない
		}
	}

	private enum Os {
		WINDOWS,
		MACOS,
		LINUX
	}

	private static Os os() {
		String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		if (name.contains("win")) {
			return Os.WINDOWS;
		}

		if (name.contains("mac") || name.contains("darwin")) {
			return Os.MACOS;
		}

		return Os.LINUX;
	}

	/** 裏DL用（進捗を見せない・やめない） */
	private static final class QuietProgress implements Progress {
		@Override
		public void update(long done, long total) {
			// 見せない
		}

		@Override
		public boolean isCancelled() {
			return false;
		}
	}
}
