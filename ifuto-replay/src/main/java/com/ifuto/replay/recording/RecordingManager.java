package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.ifuto.replay.audio.AudioMode;
import com.ifuto.replay.audio.AudioRecorder;
import com.ifuto.replay.recording.LocalEvents;
import com.ifuto.replay.audio.MinecraftAudioCapture;
import com.ifuto.replay.audio.AudioTracks;
import com.ifuto.replay.audio.VoiceChatBridge;
import com.ifuto.replay.config.ReplayConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
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

	/** 最後にワールド/サーバーに入った時刻（途中から録り始めたかを判定するため） */
	private volatile long lastJoinTimeMs;

	/** 世界を作ったパケット（途中から録り始めたときの「世界の写し」に使う） */
	private static volatile @Nullable Packet<?> joinPacket;

	/** 入ったあとに別の次元へ移っていた場合のパケット */
	private static volatile @Nullable Packet<?> respawnPacket;

	/** 「途中から」と判定するまでの猶予（ms） */
	private static final long PARTIAL_START_MS = 5000L;

	/** 空き容量のチェック間隔（ms） */
	private static final long DISK_CHECK_INTERVAL_MS = 5000L;

	/** パケットにならない操作（マウス・キー・画面）の記録 */
	private final InputTracker inputTracker = new InputTracker();

	/** 音声の録音（外の ffmpeg に任せる） */
	private final AudioRecorder audioRecorder = new AudioRecorder();

	/** いま InputTracker が追っている録画（変わったら最初から記録し直す） */
	private @Nullable RecordingSession trackedSession;

	/** 容量の警告は1回でいい */
	private boolean lowDiskWarned;
	private boolean sizeWarned;
	private long lastDiskCheckMs;

	private RecordingManager() {
	}

	/** 世界を作ったパケットを覚える（あとで世界の写しを作るため） */
	public static void rememberJoinPacket(Packet<?> packet) {
		joinPacket = packet;
		respawnPacket = null;
	}

	/** 別の次元へ移ったパケットを覚える */
	public static void rememberRespawnPacket(Packet<?> packet) {
		respawnPacket = packet;
	}

	/** 切断したので忘れる */
	public static void forgetWorldPackets() {
		joinPacket = null;
		respawnPacket = null;
	}

	public static @Nullable Packet<?> getJoinPacket() {
		return joinPacket;
	}

	public static @Nullable Packet<?> getRespawnPacket() {
		return respawnPacket;
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
		this.lastJoinTimeMs = System.currentTimeMillis();

		ReplayConfig config = ReplayConfig.get();

		if (config.autoRecord || config.clipMode) {
			start(client, handler);
		}
	}

	public synchronized boolean start(MinecraftClient client, ClientPlayNetworkHandler handler) {
		if (this.session != null) {
			return false;
		}

		this.lowDiskWarned = false;
		this.sizeWarned = false;
		this.lastDiskCheckMs = System.currentTimeMillis();

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
			created.captureRegistries(handler);

			if (!created.hasEncoder()) {
				notify(client, "ifuto-replay.message.io_error");
				return false;
			}

			created.start();

			if (created.isClipMode()) {
				// クリップ方式: 区間を回し始める（区間の先頭に世界の写しが入る）
				created.startClip(client);
				notify(client, "ifuto-replay.message.clip_started", clipLengthText(config.clipSeconds));
				this.session = created;
				return true;
			}

			if (System.currentTimeMillis() - this.lastJoinTimeMs > PARTIAL_START_MS) {
				// 途中から録り始めたので、いまの世界の写しを先頭に置いて再生できるようにする
				if (created.captureSnapshot(client)) {
					notify(client, "ifuto-replay.message.snapshot");
				} else {
					// 写しが作れない（この Mod を入れる前から入っていた等）ので、このファイルは再生できない
					IfutoReplayClient.LOGGER.warn("[ifuto-replay] 途中から録り始めました。世界の写しが作れないので"
							+ "このファイルは再生できません（サーバーに入り直してから録るか、"
							+ "設定の「自動録画」を ON にしてください）");
					notify(client, "ifuto-replay.message.partial_start");
				}
			} else {
				// 入った直後なのでパケットが最初から揃っている。「ここから完全」の印だけ置く
				created.addMarker(ReplayFormat.SNAP_MARKER);
			}
		} catch (IOException e) {
			IfutoReplayClient.LOGGER.error("[ifuto-replay] {} を開けませんでした", file, e);
			notify(client, "ifuto-replay.message.io_error");
			return false;
		}

		this.session = created;
		notify(client, "ifuto-replay.message.started", Text.literal(file.getFileName().toString()));
		this.startAudio(client, file);
		return true;
	}

	public synchronized void stop(MinecraftClient client) {
		forgetWorldPackets();
		RecordingSession current = this.session;

		if (current == null) {
			return;
		}

		this.session = null;
		this.audioRecorder.stop();
		MinecraftAudioCapture.get().stop(client);
		VoiceChatBridge.get().stop();
		boolean clipMode = current.isClipMode();
		RecordingSession.Stats stats = current.finish();

		if (clipMode) {
			// クリップ方式は「押したときだけ残る」。止めた時点で一時ファイルは消えている
			notify(client, "ifuto-replay.message.clip_stopped");
			return;
		}

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

	/**
	 * 音声の録音を始める。
	 *
	 * <p>取れない環境（ffmpeg が無い、機器が無い等）でも **録画は止めない**。
	 * そのときは理由を出して、音声なしで続ける。
	 */
	private void startAudio(MinecraftClient client, Path recordingFile) {
		ReplayConfig config = ReplayConfig.get();

		if (config.audioMode == null || !config.audioMode.records()) {
			return;
		}

		if (config.audioMode == AudioMode.MINECRAFT) {
			this.startMinecraftAudio(client, config, recordingFile);
			return;
		}

		Path target = AudioTracks.pathFor(recordingFile, config.audioMode);

		if (this.audioRecorder.start(target)) {
			this.startVoiceChat(config, recordingFile);
			return;
		}

		String failure = this.audioRecorder.failure();

		if (!failure.isEmpty()) {
			notify(client, "ifuto-replay.message.audio_failed", Text.literal(failure));
		}
	}

	/**
	 * Minecraft の音だけを録る（OpenAL のループバック）。
	 *
	 * <p>環境によっては取り出せない（対応していない OpenAL、機器を2つ開けない等）。
	 * そのときは **無音にせず PC 全体の音へ回す**（設定で「録らない」にもできる）。
	 */
	private void startMinecraftAudio(MinecraftClient client, ReplayConfig config, Path recordingFile) {
		Path target = AudioTracks.pathFor(recordingFile, AudioMode.MINECRAFT);

		if (MinecraftAudioCapture.get().start(client, target, config.audioBitrateKbps, config.ffmpegPath)) {
			this.startVoiceChat(config, recordingFile);
			return;
		}

		String failure = MinecraftAudioCapture.get().failure();
		IfutoReplayClient.LOGGER.warn("[ifuto-replay] Minecraft の音を録れないので PC 全体の音で代用します: {}", failure);

		Path fallback = AudioTracks.systemTrack(recordingFile);

		if (this.audioRecorder.start(fallback)) {
			notify(client, "ifuto-replay.message.audio_fallback", Text.literal(String.valueOf(failure)));
			this.startVoiceChat(config, recordingFile);
			return;
		}

		if (failure != null && !failure.isEmpty()) {
			notify(client, "ifuto-replay.message.audio_failed", Text.literal(failure));
		}
	}

	/**
	 * VC の声だけを別に録り始める（Simple Voice Chat が入っているときだけ）。
	 *
	 * <p>VC Mod は Minecraft とは別の出力機器を開くので、こうしないと声だけを分けられない。
	 */
	private void startVoiceChat(ReplayConfig config, Path recordingFile) {
		if (!config.recordVoiceChat || !VoiceChatBridge.isAvailable()) {
			return;
		}

		VoiceChatBridge.get().start(AudioTracks.voiceTrack(recordingFile), config.audioBitrateKbps,
				config.ffmpegPath);
	}

	/**
	 * クリップ方式: 音声を録り始める。
	 *
	 * <p>区間（映像）とは違って、**音声は止めて録り直すと途切れる**
	 * （とくに Minecraft の音は出力機器を開き直す必要がある）。
	 * なのでクリップ方式ではずっと1本で録っておき、保存するときに
	 * **いちばん後ろのぶんだけ** を切り出している（{@code AudioTracks#tail}）。
	 */
	public synchronized boolean startClipAudio(MinecraftClient client, Path target) {
		this.stopClipAudio(client);

		ReplayConfig config = ReplayConfig.get();

		if (config.audioMode == null || !config.audioMode.records()) {
			return false;
		}

		if (config.audioMode == AudioMode.MINECRAFT) {
			Path track = AudioTracks.pathFor(target, AudioMode.MINECRAFT);

			if (!MinecraftAudioCapture.get().start(client, track, config.audioBitrateKbps, config.ffmpegPath)) {
				// 取れない環境では PC 全体の音で代用する（ふつうの録画と同じ考え方）
				this.audioRecorder.start(AudioTracks.pathFor(target, AudioMode.SYSTEM));
			}

			this.startVoiceChat(config, target);
			return this.isClipAudioRunning();
		}

		this.audioRecorder.start(AudioTracks.pathFor(target, config.audioMode));
		this.startVoiceChat(config, target);
		return this.isClipAudioRunning();
	}

	/**
	 * **音を止めずに**、音声の書き出し先を次のファイルへ移す（クリップを保存したあとに使う）。
	 *
	 * <p>止めて録り直すと「いままでの音」が消えてしまう。2時間のクリップを2回保存したら
	 * 2回めには音が付かない、ということになる。
	 *
	 * <p>Minecraft の音は装置（ループバック）を開いたまま **出し先だけ** を入れ替えるので、
	 * 音は途切れない。PC 全体の音は ffmpeg が機器を読んでいるので入れ替えの瞬間だけ途切れるが、
	 * それ以前の音は別のファイルとして残る。
	 *
	 * @return 音声を録れている状態なら true（設定で録らないとき / 落ちているときは false）
	 */
	public synchronized boolean rotateClipAudio(MinecraftClient client, Path target) {
		ReplayConfig config = ReplayConfig.get();

		if (config.audioMode == null || !config.audioMode.records()) {
			return false;
		}

		if (MinecraftAudioCapture.get().isRunning()) {
			if (!MinecraftAudioCapture.get().rotate(AudioTracks.pathFor(target, AudioMode.MINECRAFT),
					config.audioBitrateKbps, config.ffmpegPath)) {
				return false;
			}
		} else if (this.audioRecorder.isRunning()) {
			this.audioRecorder.rotate(AudioTracks.pathFor(target, AudioMode.SYSTEM));
		} else {
			return false;
		}

		if (config.recordVoiceChat && VoiceChatBridge.isAvailable()) {
			VoiceChatBridge.get().rotate(AudioTracks.voiceTrack(target), config.audioBitrateKbps,
					config.ffmpegPath);
		}

		return true;
	}

	/** クリップ用の音声を録れているか */
	private boolean isClipAudioRunning() {
		return MinecraftAudioCapture.get().isRunning() || this.audioRecorder.isRunning();
	}

	/** クリップをまとめる前に、いま録っている音声を閉じる（クライアントスレッドから） */
	public void stopClipAudio(MinecraftClient client) {
		this.audioRecorder.stop();
		MinecraftAudioCapture.get().stop(client);
		VoiceChatBridge.get().stop();
	}

	/**
	 * クリップを保存する（いま残っている区間を1つにまとめる）。
	 *
	 * <p>Medal と同じで「さっきの出来事をあとから残す」操作。
	 * 実際の処理は別スレッドでやるので、ここはすぐ戻る。
	 */
	public void saveClip(MinecraftClient client) {
		RecordingSession current = this.session;

		if (current == null || !current.isClipMode()) {
			notify(client, "ifuto-replay.message.clip_disabled");
			return;
		}

		if (current.clipBufferedMillis() <= 0L) {
			notify(client, "ifuto-replay.message.clip_empty");
			return;
		}

		notify(client, "ifuto-replay.message.clip_saving");
		current.saveClip(client);
	}

	/** クリップ方式で録っているか（ボタンの有効・無効よう） */
	public boolean canClip() {
		RecordingSession current = this.session;
		return current != null && current.isClipMode();
	}

	/** まとめ終わったので知らせる（ClipBuffer からクライアントスレッドで呼ばれる） */
	public void notifyClipSaved(MinecraftClient client, @Nullable Path saved, @Nullable Throwable failure) {
		if (failure instanceof ClipBuffer.NoSpaceException noSpace) {
			notify(client, "ifuto-replay.message.clip_no_space",
					Text.literal(formatSize(noSpace.neededBytes)));
			return;
		}

		if (failure != null || saved == null) {
			notify(client, "ifuto-replay.message.clip_failed");
			return;
		}

		long bytes;

		try {
			bytes = Files.size(saved);
		} catch (IOException e) {
			bytes = 0L;
		}

		notify(client, "ifuto-replay.message.clip_saved",
				Text.literal(saved.getFileName().toString()),
				Text.literal(formatSize(bytes)));
		IfutoReplayClient.LOGGER.info("[ifuto-replay] クリップを保存しました: {}", saved.getFileName());
	}

	/**
	 * クライアントが自分で湧かせたパーティクルを記録する（Mixin から呼ばれる）。
	 *
	 * <p>パケットとして届いた物は記録しない（すでにパケット側に残っているため）。
	 * 設定でオフにできる。
	 */
	public void recordParticle(ParticleEffect effect, double x, double y, double z,
							   double velocityX, double velocityY, double velocityZ) {
		if (!this.isRecording() || LocalEvents.isFromPacket()) {
			return;
		}

		if (!ReplayConfig.get().recordParticles) {
			return;
		}

		RecordingSession current = this.session;

		if (current == null || !current.allowsLocal()) {
			// 上限を超えているときは変換する前に帰る（バイト列化はそれなりに重い）
			return;
		}

		byte[] data = LocalEvents.encodeParticle(effect, x, y, z, velocityX, velocityY, velocityZ);

		if (data == null || data.length == 0) {
			return;
		}

		current.recordLocal(LocalEvents.TYPE_PARTICLE, data);
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

		// マウス・キー・画面の操作（パケットにならない物）も差分で記録する
		if (this.trackedSession != current) {
			this.trackedSession = current;
			this.inputTracker.reset();
		}

		this.inputTracker.tick(client, current);

		// ためているパーティクルをまとめて書く（ティック1回ぶんの遅れは見えない）
		current.flushLocalBatch();

		// クリップ方式: 区間の長さを過ぎていたら次へ移る（古い区間は捨てる）
		current.tickClip(client);

		// Minecraft の音はゲーム側のスレッドで取り出す（溜まっている分だけ）
		MinecraftAudioCapture.get().renderTick();

		// 録音が勝手に終わっていたら理由を出す（録画は止めない）
		String audioFailure = this.audioRecorder.pollFailure();

		if (!audioFailure.isEmpty()) {
			notify(client, "ifuto-replay.message.audio_failed", Text.literal(audioFailure));
		}

		String minecraftFailure = MinecraftAudioCapture.get().pollFailure();

		if (!minecraftFailure.isEmpty()) {
			notify(client, "ifuto-replay.message.audio_failed", Text.literal(minecraftFailure));
			MinecraftAudioCapture.get().stop(client);
		}

		long now = System.currentTimeMillis();

		if (now - this.lastDiskCheckMs >= DISK_CHECK_INTERVAL_MS) {
			this.lastDiskCheckMs = now;

			if (this.checkStorage(client, current)) {
				return;
			}
		}

		if (current.isOverDuration() || current.isLimitReached()) {
			this.stop(client);
		}
	}

	/**
	 * 容量の見張り。空きが心細くなったら知らせて、危なくなったら保存して止める。
	 *
	 * @return 録画を止めたら true
	 */
	private boolean checkStorage(MinecraftClient client, RecordingSession session) {
		ReplayConfig config = ReplayConfig.get();
		long freeMb = freeSpaceMb(ReplayConfig.getSaveDirectory());

		// 取れない環境（フォルダが無い等）では何もしない
		if (freeMb < 0L) {
			return false;
		}

		if (config.criticalDiskSpaceMb > 0 && freeMb < config.criticalDiskSpaceMb) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 保存先の空きが {} MB しかないので録画を止めます", freeMb);
			notify(client, "ifuto-replay.message.disk_critical", Text.literal(String.valueOf(freeMb)));
			this.stop(client);
			return true;
		}

		if (config.lowDiskSpaceMb > 0 && freeMb < config.lowDiskSpaceMb && !this.lowDiskWarned) {
			this.lowDiskWarned = true;
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 保存先の空きが {} MB です", freeMb);
			notify(client, "ifuto-replay.message.disk_low", Text.literal(String.valueOf(freeMb)));
		}

		if (config.maxFileSizeMb > 0 && !this.sizeWarned) {
			long writtenMb = session.bytesWritten() / (1024L * 1024L);

			if (writtenMb * 100L >= (long) config.maxFileSizeMb * 80L) {
				this.sizeWarned = true;
				notify(client, "ifuto-replay.message.size_warning",
						Text.literal(String.valueOf(writtenMb)),
						Text.literal(String.valueOf(config.maxFileSizeMb)));
			}
		}

		return false;
	}

	/** 保存先の空き容量（MB）。分からなければ -1 */
	private static long freeSpaceMb(Path directory) {
		try {
			Path target = directory;

			while (target != null && !Files.exists(target)) {
				target = target.getParent();
			}

			if (target == null) {
				return -1L;
			}

			return Files.getFileStore(target).getUsableSpace() / (1024L * 1024L);
		} catch (IOException e) {
			return -1L;
		}
	}

	// --- おしらせ ---

	private static void notify(MinecraftClient client, String key, Object... args) {
		if (client == null || client.player == null || !ReplayConfig.get().notifyChat) {
			return;
		}

		client.player.sendMessage(Text.translatable(key, args), false);
	}

	/** 秒を「2 時間」「30 分」「15 秒」にする（設定画面とお知らせで共用） */
	public static Text clipLengthText(int seconds) {
		if (seconds >= 3600) {
			return Text.translatable("ifuto-replay.config.clip_length.hours", seconds / 3600);
		}

		if (seconds >= 60) {
			return Text.translatable("ifuto-replay.config.clip_length.minutes", seconds / 60);
		}

		return Text.translatable("ifuto-replay.config.clip_length.seconds", seconds);
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
