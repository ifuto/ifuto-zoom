package com.ifuto.fastplace.scaffold;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.human.HumanAim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * 視点の分離層。
 *
 * <p>Scaffold 中は <b>エンティティのピッチ（＝サーバーに送られる本物の向き）</b> を
 * ブロックへ向け、<b>カメラのピッチ</b> だけプレイヤーが操作している値を保つ。
 * パケットを偽装しているわけではなく、実際に体は下を向いている。手元の見た目
 * （カメラ）だけを切り離すので、前を向いたまま歩ける。</p>
 *
 * <p>マウスの入力は 2 系統で拾う:</p>
 * <ol>
 *   <li>{@code Entity#changeLookDirection} を mixin で横取りできた場合は、
 *       その場で視点（visual）に加算してエンティティ側は触らない。</li>
 *   <li>横取りできなかった場合は、毎 tick 「前回こちらが書いたピッチ」との差を
 *       マウス入力とみなして視点に加算する。どちらか片方だけが必ず効くので、
 *       二重計上は起きない。</li>
 * </ol>
 */
public final class ScaffoldView {
	public static final float MOUSE_SCALE = 0.15f;
	private static final float MIN_PITCH = -90.0f;
	private static final float MAX_PITCH = 90.0f;

	private static final HumanAim AIM = new HumanAim();

	private static boolean capturing;
	private static float visualPitch;
	private static float lastWrittenPitch;
	private static long lastStepMs = -1;
	private static boolean humanize = true;

	private ScaffoldView() {
	}

	public static boolean isCapturing() {
		return capturing;
	}

	public static float visualPitch() {
		return visualPitch;
	}

	/** カメラを「プレイヤーが見ている向き」で固定するか */
	public static boolean cameraActive() {
		return capturing && FastPlaceConfig.get().scaffoldKeepView;
	}

	public static float cameraPitch() {
		return visualPitch;
	}

	public static void begin(ClientPlayerEntity player, boolean useHumanModel, long nowMs) {
		capturing = true;
		humanize = useHumanModel;
		visualPitch = clamp(player.getPitch());
		lastWrittenPitch = player.getPitch();
		AIM.reset(player.getPitch());
		lastStepMs = nowMs;
	}

	/**
	 * mixin から呼ばれる。true を返したら呼び出し側は元の処理をキャンセルする。
	 */
	public static boolean handleMouseLook(double deltaX, double deltaY) {
		if (!capturing) {
			return false;
		}
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return false;
		}
		player.setYaw(player.getYaw() + (float) deltaX * MOUSE_SCALE);
		visualPitch = clamp(visualPitch + (float) deltaY * MOUSE_SCALE);
		return true;
	}

	/** 毎 tick、こちらの書き込みより前に呼ぶ。マウスで動いた分を視点側へ回収する */
	public static void syncFromEntity(ClientPlayerEntity player) {
		if (!capturing) {
			return;
		}
		float delta = player.getPitch() - lastWrittenPitch;
		if (Math.abs(delta) > 1.0e-4f) {
			visualPitch = clamp(visualPitch + delta);
		}
	}

	/** 目標ピッチへ 1 tick ぶん動かしてエンティティに書く。戻り値は書いたピッチ */
	public static float stepToward(ClientPlayerEntity player, float targetPitch, long nowMs) {
		float applied;
		if (!humanize) {
			applied = clamp(targetPitch);
		} else {
			double dt = lastStepMs < 0 ? 50.0 : Math.max(1.0, nowMs - lastStepMs);
			applied = clamp((float) AIM.step(targetPitch, nowMs, dt));
		}
		lastStepMs = nowMs;
		lastWrittenPitch = applied;
		player.setPitch(applied);
		return applied;
	}

	/** 復帰が終わったか（十分近づいたか） */
	public static boolean settledAt(float targetPitch) {
		if (!humanize) {
			return true;
		}
		return !AIM.isMoving() && Math.abs(AIM.angle() - targetPitch) < 0.15;
	}

	/** 復帰完了。カメラの切り離しをやめる */
	public static void finish(ClientPlayerEntity player) {
		lastWrittenPitch = clamp(visualPitch);
		player.setPitch(lastWrittenPitch);
		capturing = false;
		lastStepMs = -1;
		AIM.reset(lastWrittenPitch);
	}

	/** ワールドから消えた / リスポーンなど、安全側に倒すとき */
	public static void reset() {
		capturing = false;
		visualPitch = 0.0f;
		lastWrittenPitch = 0.0f;
		lastStepMs = -1;
		AIM.reset(0.0);
	}

	public static float lastWrittenPitch() {
		return lastWrittenPitch;
	}

	private static float clamp(float v) {
		return v < MIN_PITCH ? MIN_PITCH : Math.min(v, MAX_PITCH);
	}
}
