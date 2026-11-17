package com.ifuto.fastplace.aim;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.human.TrackingAim;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * 剣（近接）用のアシスト。
 *
 * <p><b>何をするか</b>: 視野の内側・間合いの少し手前にいる相手に対して、
 * {@link TrackingAim} が計算した「人間の速度で寄る」1 tick ぶんの回転を
 * <b>足す</b>だけ。視点を奪わない（マウスは常にそのまま効く）し、パケットも偽装しない。</p>
 *
 * <p><b>外さないための約束</b>:</p>
 * <ol>
 *   <li>1 tick の回転量は {@link TrackingAim} が人間の上限速度でクリップする。</li>
 *   <li>回した結果、相手への角度誤差が<b>悪化するなら適用しない</b>。</li>
 *   <li>すでに当たり判定の安全域より外にいる場合だけ、そのぶんを内側へ引き戻す
 *       （引き戻しも同じ速度上限の中）。</li>
 *   <li>狙うのは当たり判定の 62% の高さ（胸）。上下の当たり判定には余裕がある。</li>
 * </ol>
 */
public final class AimAssistModule {
	/** 狙い始める距離（剣の間合い 3.0 の少し手前） */
	private static final double MAX_RANGE = 4.6;
	/** 一度ロックした相手を手放す距離 */
	private static final double RELEASE_RANGE = 5.4;
	/** ロック中は視野をこの倍率まで広く許す（ちらつき防止） */
	private static final double FOV_KEEP = 1.35;
	/** 狙う高さ（当たり判定の下から 62% = 胸） */
	private static final double AIM_HEIGHT = 0.62;
	/** 当たり判定の半幅 + 余白 [blocks] */
	private static final double HIT_HALF_WIDTH = 0.40;
	/** 当たり判定の角度半径のこの割合まで内側にいれば十分とみなす */
	private static final double SAFE_FRACTION = 0.55;
	/**
	 * 近接武器として扱うアイテム。
	 * 1.21 以降 {@code SwordItem}/{@code AxeItem} はデータコンポーネント方式に移り、
	 * クラスとしては存在しないので、実体（{@link Items}）で判定する。
	 */
	private static final Set<Item> MELEE_WEAPONS = Set.of(
		Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
		Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
		Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.GOLDEN_AXE,
		Items.DIAMOND_AXE, Items.NETHERITE_AXE);

	/** 先読み（予測的追従）の見込み時間 [s] と係数（Barnes & Asselman 1991） */
	private static final double LEAD_SECONDS = 0.065;
	private static final double LEAD_GAIN = 0.70;

	/** 一瞬だけ対象から外れたときに反応時間をやり直さない猶予 [ms] */
	private static final long RETARGET_GRACE_MS = 500L;

	private static final TrackingAim MODEL = new TrackingAim();

	private static LivingEntity target;
	private static LivingEntity previousTarget;
	private static long previousTargetAt = -1L;

	private AimAssistModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().aimAssistEnabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		cfg.aimAssistEnabled = on;
		cfg.save();
		if (!on) {
			release();
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	/** いま狙っている相手（HUD・デバッグ用） */
	public static LivingEntity currentTarget() {
		return target;
	}

	public static void release() {
		if (target != null) {
			previousTarget = target;
			previousTargetAt = System.currentTimeMillis();
		}
		target = null;
		MODEL.reset();
	}

	public static void tick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		ClientPlayerEntity player = client.player;
		if (!enabled() || player == null || client.world == null
			|| client.currentScreen != null
			|| ScaffoldModule.isEngaged()
			|| !player.isAlive() || player.isSpectator()) {
			release();
			return;
		}

		FastPlaceConfig cfg = FastPlaceConfig.get();
		if (cfg.aimAssistWeaponsOnly && !isMeleeWeapon(player.getMainHandStack())) {
			release();
			return;
		}
		if (cfg.aimAssistOnlyWhileAttacking && !client.options.attackKey.isPressed()) {
			release();
			return;
		}

		MODEL.setHumanize(cfg.aimAssistHumanize);

		LivingEntity picked = pickTarget(client, player);
		if (picked == null) {
			release();
			return;
		}
		if (picked != target) {
			boolean reacquired = picked == previousTarget && now - previousTargetAt < RETARGET_GRACE_MS;
			target = picked;
			if (!reacquired) {
				// 相手を捉えた瞬間から人間の反応時間ぶんだけ遅れて動き出す
				MODEL.acquire(now);
			}
		}

		Vec3d eye = player.getEyePos();
		Box box = picked.getBoundingBox();
		Vec3d truePoint = aimPoint(box);
		Vec3d goal = truePoint;
		if (cfg.aimAssistPredict) {
			// 相手の速度 × 知覚遅れ × 係数。人間は追従の遅れぶんを予測で埋める
			goal = goal.add(picked.getVelocity().multiply(LEAD_SECONDS * 20.0 * LEAD_GAIN));
		}

		double[] goalAngles = anglesTo(eye, goal);
		double errYaw = MathHelper.wrapDegrees((float) (goalAngles[0] - player.getYaw()));
		double errPitch = goalAngles[1] - player.getPitch();

		double[] delta = MODEL.step(now, errYaw, errPitch, cfg.aimAssistStrength);
		if (delta[0] == 0.0 && delta[1] == 0.0) {
			return;
		}

		float yaw = player.getYaw();
		float pitch = player.getPitch();

		// 当たり判定の角度半径（水平の半幅が厳しいのでそれを基準にする）
		double eyeToBox = Math.max(0.6, eye.distanceTo(box.getCenter()) - 0.3);
		double hitRadius = Math.toDegrees(Math.atan2(HIT_HALF_WIDTH, eyeToBox));
		double safeLimit = hitRadius * SAFE_FRACTION;

		double before = errorTo(yaw, pitch, eye, truePoint);
		float nextYaw = yaw + (float) delta[0];
		float nextPitch = MathHelper.clamp(pitch + (float) delta[1], -90.0f, 90.0f);
		double after = errorTo(nextYaw, nextPitch, eye, truePoint);

		if (after > before + 1.0e-6) {
			// アシストが悪化させた → 安全域の外にいるときだけ、同じ速度上限で引き戻す
			double[] errTrue = errorVector(yaw, pitch, eye, truePoint);
			double[] fix = TrackingAim.pullInside(errTrue[0], errTrue[1], safeLimit);
			double fixMag = Math.hypot(fix[0], fix[1]);
			if (fixMag < 1.0e-9) {
				return;   // すでに十分内側。何もしない
			}
			double stepMag = Math.hypot(delta[0], delta[1]);
			double k = Math.min(1.0, stepMag / fixMag);
			nextYaw = yaw + (float) (fix[0] * k);
			nextPitch = MathHelper.clamp(pitch + (float) (fix[1] * k), -90.0f, 90.0f);
			if (errorTo(nextYaw, nextPitch, eye, truePoint) > before + 1.0e-6) {
				return;   // それでも悪くなるなら触らない
			}
		}

		player.setYaw(nextYaw);
		player.setPitch(nextPitch);
	}

	// ------------------------------------------------------------------
	// 相手を選ぶ
	// ------------------------------------------------------------------

	private static LivingEntity pickTarget(MinecraftClient client, ClientPlayerEntity player) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0f);
		double cosNormal = Math.cos(Math.toRadians(cfg.aimAssistFov * 0.5));
		double cosKeep = Math.cos(Math.toRadians(cfg.aimAssistFov * 0.5 * FOV_KEEP));

		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;

		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || living == player) {
				continue;
			}
			if (!living.isAlive() || living.isRemoved() || living.isInvisible()) {
				continue;
			}
			boolean locked = living == target;
			double distance = Math.sqrt(player.squaredDistanceTo(living));
			if (distance > (locked ? RELEASE_RANGE : MAX_RANGE)) {
				continue;
			}
			Vec3d to = aimPoint(living.getBoundingBox()).subtract(eye);
			double length = to.length();
			if (length < 1.0e-4) {
				continue;
			}
			double cos = to.x / length * look.x + to.y / length * look.y + to.z / length * look.z;
			if (cos < (locked ? cosKeep : cosNormal)) {
				continue;   // 視野の外
			}
			if (!player.canSee(living)) {
				continue;   // 壁越しは狙わない
			}
			double score = Math.toDegrees(Math.acos(MathHelper.clamp(cos, -1.0, 1.0))) + distance * 0.15;
			if (locked) {
				score -= 6.0;   // 同じ相手を優先（ちらつき防止）
			}
			if (score < bestScore) {
				bestScore = score;
				best = living;
			}
		}
		return best;
	}

	private static Vec3d aimPoint(Box box) {
		double x = (box.minX + box.maxX) * 0.5;
		double z = (box.minZ + box.maxZ) * 0.5;
		double y = box.minY + box.getLengthY() * AIM_HEIGHT;
		return new Vec3d(x, y, z);
	}

	private static boolean isMeleeWeapon(ItemStack stack) {
		return MELEE_WEAPONS.contains(stack.getItem());
	}

	// ------------------------------------------------------------------
	// 角度
	// ------------------------------------------------------------------

	private static double[] anglesTo(Vec3d from, Vec3d to) {
		Vec3d delta = to.subtract(from);
		double horizontal = Math.hypot(delta.x, delta.z);
		double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
		double pitch = -Math.toDegrees(Math.atan2(delta.y, horizontal));
		return new double[] {yaw, pitch};
	}

	private static double[] errorVector(float yaw, float pitch, Vec3d eye, Vec3d point) {
		double[] angles = anglesTo(eye, point);
		return new double[] {
			MathHelper.wrapDegrees((float) (angles[0] - yaw)),
			angles[1] - pitch
		};
	}

	private static double errorTo(float yaw, float pitch, Vec3d eye, Vec3d point) {
		double[] error = errorVector(yaw, pitch, eye, point);
		return Math.hypot(error[0], error[1]);
	}
}
