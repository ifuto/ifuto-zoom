package com.ifuto.fastplace.human;

import com.ifuto.fastplace.aim.AimAssistModule;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 「実際に視点を向ける」ための回転ヘルパー。
 *
 * <p>モジュールが代わりにブロックを叩くとき、<b>向きはちゃんと体（= サーバーに
 * 送られる本物の yaw/pitch）を動かす</b>。パケットの偽装はしない。その代わり
 * 20Hz の tick で書くと 50ms ごとの段差が出るので、<b>描画フレームごと</b>に
 * 少しずつ回す。</p>
 *
 * <p>速さは「残りの角度 × ゲイン」で、上限だけを設定値で決める。目標に近づくほど
 * 自動的に遅くなるので、行き過ぎず、最後はすっと止まる（人間のサッケードの
 * 減速相と同じ形）。</p>
 */
public final class SmoothLook {
	/** 残り角度 1 度あたりの角速度 [deg/s per deg] */
	private static final double GAIN = 12.0;
	/** これ以下なら「向いた」とみなす [deg] */
	public static final double DEFAULT_TOLERANCE_DEG = 0.9;
	/** 1 フレームで動かす下限（止まりかけでも最後まで詰める） [deg] */
	private static final double MIN_STEP_DEG = 0.05;

	private SmoothLook() {
	}

	/**
	 * {@code point} へ向けて 1 フレームぶん回す。
	 *
	 * @return 向き終わった（誤差が {@code toleranceDeg} 以内）なら true
	 */
	public static boolean stepFrame(ClientPlayerEntity player, Vec3d point, double maxDegPerSec,
			double dtMs, double toleranceDeg) {
		double[] target = AimAssistModule.anglesTo(player.getEyePos(), point);
		double errYaw = MathHelper.wrapDegrees((float) (target[0] - player.getYaw()));
		double errPitch = target[1] - player.getPitch();
		double error = Math.hypot(errYaw, errPitch);
		if (error <= toleranceDeg) {
			return true;
		}
		double dt = Math.max(0.5, Math.min(120.0, dtMs));
		double speed = Math.min(Math.max(30.0, maxDegPerSec), error * GAIN);
		double step = Math.max(MIN_STEP_DEG, speed * dt / 1000.0);
		step = Math.min(step, error * 0.92);
		player.setYaw(player.getYaw() + (float) (errYaw / error * step));
		player.setPitch(MathHelper.clamp(
			player.getPitch() + (float) (errPitch / error * step), -90.0f, 90.0f));
		return false;
	}

	/** いま向いている誤差 [deg] */
	public static double errorDeg(ClientPlayerEntity player, Vec3d point) {
		double[] target = AimAssistModule.anglesTo(player.getEyePos(), point);
		double errYaw = MathHelper.wrapDegrees((float) (target[0] - player.getYaw()));
		double errPitch = target[1] - player.getPitch();
		return Math.hypot(errYaw, errPitch);
	}
}
