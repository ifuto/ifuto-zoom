package com.ifuto.replay.recording;

import com.ifuto.replay.mixin.ChatScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.BeaconScreen;
import net.minecraft.client.gui.screen.ingame.BrewingStandScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.ingame.FurnaceScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.screen.ingame.SmithingScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 「パケットにならない操作」を差分で記録する。
 *
 * <p>サーバーとやりとりする内容（チャットの送受信・インベントリのクリック・移動など）は
 * すべてパケットとして残る。けれど **ローカルでしか起きない操作** はパケットにならないので、
 * ここで別に記録する:
 * <ul>
 *     <li>マウスカーソルの位置（インベントリのどこを指していたか）</li>
 *     <li>チャット欄に **入力中の** 文字（送るまでサーバーには届かない）</li>
 *     <li>F5（視点）と F3（デバッグ画面）</li>
 *     <li>どの画面を開いていたか</li>
 * </ul>
 *
 * <p>記録は **差分** だけ。動いていないのに毎回同じ値を書かないし、
 * カーソルは「前回からの移動量」、入力中の文字は「増えた分」だけを書く
 * （ときどき絶対値を混ぜて、途中から再生しても狂わないようにしている）。
 */
public final class InputTracker {
	/** これだけ動いたら絶対座標を記録し直す（欠けても崩れないように） */
	private static final double KEYFRAME_DISTANCE = 96.0;

	/** 絶対座標を記録し直す間隔（ms） */
	private static final long KEYFRAME_INTERVAL_MS = 1000L;

	/** 入力中の文字を記録する間隔（ms）。打つ速さは高が知れているので間引く */
	private static final long CHAT_INTERVAL_MS = 200L;

	private boolean hasCursor;
	private double lastX;
	private double lastY;
	private long lastKeyframeMs;
	private int lastPerspective = -1;
	private int lastDebug = -1;
	private String lastScreen = "";
	private String lastChatText = "";
	private long lastChatMs;

	public InputTracker() {
	}

	/** 録画を新しく始めるときに呼ぶ（前の録画の続きに見えないようにする） */
	public void reset() {
		this.hasCursor = false;
		this.lastX = 0.0;
		this.lastY = 0.0;
		this.lastKeyframeMs = 0L;
		this.lastPerspective = -1;
		this.lastDebug = -1;
		this.lastScreen = "";
		this.lastChatText = "";
		this.lastChatMs = 0L;
	}

	/** クライアントの tick ごとに呼ぶ（録画中だけ） */
	public void tick(MinecraftClient client, RecordingSession session) {
		if (client == null || session == null) {
			return;
		}

		this.tickScreen(client, session);
		this.tickOptions(client, session);
		this.tickCursor(client, session);
		this.tickChat(client, session);
	}

	private void tickScreen(MinecraftClient client, RecordingSession session) {
		String screen = screenId(client.currentScreen);

		if (!screen.equals(this.lastScreen)) {
			this.lastScreen = screen;
			session.recordInput(encode(ReplayFormat.INPUT_SCREEN, out -> out.writeUTF(screen)));
		}
	}

	private void tickOptions(MinecraftClient client, RecordingSession session) {
		int perspective = client.options.getPerspective().ordinal();

		if (perspective != this.lastPerspective) {
			this.lastPerspective = perspective;
			session.recordInput(encode(ReplayFormat.INPUT_PERSPECTIVE, out -> out.writeByte(perspective)));
		}

		int debug = client.options.debugEnabled ? 1 : 0;

		if (debug != this.lastDebug) {
			this.lastDebug = debug;
			session.recordInput(encode(ReplayFormat.INPUT_DEBUG, out -> out.writeByte(debug)));
		}
	}

	private void tickCursor(MinecraftClient client, RecordingSession session) {
		double x = client.mouse.getX();
		double y = client.mouse.getY();

		if (!this.hasCursor) {
			this.hasCursor = true;
			this.lastX = x;
			this.lastY = y;
			this.lastKeyframeMs = System.currentTimeMillis();
			session.recordInput(encode(ReplayFormat.INPUT_MOUSE_ABS,
					out -> {
						out.writeShort((int) Math.round(x));
						out.writeShort((int) Math.round(y));
					}));
			return;
		}

		double dx = x - this.lastX;
		double dy = y - this.lastY;

		if (dx == 0.0 && dy == 0.0) {
			return;
		}

		long now = System.currentTimeMillis();
		boolean keyframe = now - this.lastKeyframeMs >= KEYFRAME_INTERVAL_MS
				|| Math.abs(x - this.lastX) + Math.abs(y - this.lastY) > KEYFRAME_DISTANCE;

		if (keyframe) {
			this.lastKeyframeMs = now;
			session.recordInput(encode(ReplayFormat.INPUT_MOUSE_ABS,
					out -> {
						out.writeShort((int) Math.round(x));
						out.writeShort((int) Math.round(y));
					}));
		} else {
			session.recordInput(encode(ReplayFormat.INPUT_MOUSE_DELTA,
					out -> {
						out.writeShort((int) Math.round(dx));
						out.writeShort((int) Math.round(dy));
					}));
		}

		this.lastX = x;
		this.lastY = y;
	}

	private void tickChat(MinecraftClient client, RecordingSession session) {
		if (!(client.currentScreen instanceof ChatScreen chatScreen)) {
			// 閉じたら「空」を一度だけ記録して、以降は何もしない
			if (!this.lastChatText.isEmpty()) {
				this.lastChatText = "";
				session.recordInput(encode(ReplayFormat.INPUT_CHAT_SET, out -> out.writeUTF("")));
			}

			return;
		}

		TextFieldWidget field = ((ChatScreenAccessor) chatScreen).ifutoReplay$getChatField();

		if (field == null) {
			return;
		}

		String text = field.getText();

		if (text.equals(this.lastChatText)) {
			return;
		}

		long now = System.currentTimeMillis();

		if (now - this.lastChatMs < CHAT_INTERVAL_MS) {
			return;
		}

		this.lastChatMs = now;

		// 差分: 前に送った文字列の続きなら「増えた分」だけ書く
		if (text.startsWith(this.lastChatText) && !this.lastChatText.isEmpty()) {
			String suffix = text.substring(this.lastChatText.length());
			session.recordInput(encode(ReplayFormat.INPUT_CHAT_APPEND, out -> {
				out.writeInt(this.lastChatText.length());
				out.writeUTF(suffix);
			}));
		} else {
			session.recordInput(encode(ReplayFormat.INPUT_CHAT_SET, out -> out.writeUTF(text)));
		}

		this.lastChatText = text;
	}

	/** イベント1個を「種類 + 中身」にまとめる */
	private static byte[] encode(int subtype, PayloadWriter writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(subtype);
			writer.write(out);
		} catch (IOException e) {
			return null;
		}

		return bytes.toByteArray();
	}

	/** 開いている画面を短い名前へ（クラス名をそのまま出さないため） */
	private static String screenId(@Nullable Screen screen) {
		if (screen == null) {
			return "";
		}

		if (screen instanceof ChatScreen) {
			return "chat";
		}

		if (screen instanceof InventoryScreen) {
			return "inventory";
		}

		if (screen instanceof CreativeInventoryScreen) {
			return "creative";
		}

		if (screen instanceof GenericContainerScreen) {
			return "container";
		}

		if (screen instanceof ShulkerBoxScreen) {
			return "shulker";
		}

		if (screen instanceof HopperScreen) {
			return "hopper";
		}

		if (screen instanceof FurnaceScreen) {
			return "furnace";
		}

		if (screen instanceof CraftingScreen) {
			return "crafting";
		}

		if (screen instanceof AnvilScreen) {
			return "anvil";
		}

		if (screen instanceof SmithingScreen) {
			return "smithing";
		}

		if (screen instanceof StonecutterScreen) {
			return "stonecutter";
		}

		if (screen instanceof LoomScreen) {
			return "loom";
		}

		if (screen instanceof GrindstoneScreen) {
			return "grindstone";
		}

		if (screen instanceof EnchantmentScreen) {
			return "enchanting";
		}

		if (screen instanceof BrewingStandScreen) {
			return "brewing";
		}

		if (screen instanceof BeaconScreen) {
			return "beacon";
		}

		if (screen instanceof MerchantScreen) {
			return "merchant";
		}

		if (screen instanceof AdvancementsScreen) {
			return "advancements";
		}

		if (screen instanceof GameMenuScreen) {
			return "pause";
		}

		return screen.getClass().getSimpleName();
	}

	@FunctionalInterface
	private interface PayloadWriter {
		void write(DataOutputStream out) throws IOException;
	}
}
