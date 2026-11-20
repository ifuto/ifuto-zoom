package com.ifuto.fastplace.aim;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.human.TrackingAim;
import com.ifuto.fastplace.input.VirtualKeys;
import com.ifuto.fastplace.scaffold.ScaffoldModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.Set;

/**
 * 剣（近接）用のアシスト。
 *
 * <p><b>掴み方</b>: 左クリックした瞬間、視線の先（敵の当たり判定を
 * {@code aimAssistClickBoxScale} 倍に広げた箱）に相手がいて、<b>ブロックや他の
 * エンティティに遮られていなければ</b>その相手を掴む。掴んだら
 * {@code aimAssistStickMs} のあいだ追い続ける（押しっぱなしにする必要はない）。</p>
 *
 * <p><b>動かし方</b>: 回転は tick ではなく<b>描画フレームごと</b>に、{@link TrackingAim} が
 * 出す「人間の速度」ぶんだけ足す。20 回/秒で書くと 50ms ごとの段差が出るため。
 * マウスは常にそのまま効く（視点を奪わない）。</p>
 *
 * <p><b>外さないための約束</b></p>
 * <ol>
 *   <li>1 フレームの回転量は人間の上限速度でクリップされる。</li>
 *   <li>回した結果、相手への角度誤差が<b>悪化するなら適用しない</b>。</li>
 *   <li>安全域より外に出た場合だけ、同じ速度上限のなかで内側へ引き戻す。</li>
 *   <li>進行方向と逆向きに数度ずれる（{@code aimAssistStrafeBiasDeg}）が、これは
 *       「狙う点」のずれなので命中判定の内側に収まる。</li>
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
	/** 先読み（予測的追従）の見込み時間 [s] と係数（Barnes & Asselman 1991） */
	private static final double LEAD_SECONDS = 0.065;
	private static final double LEAD_GAIN = 0.40;
	/** 一瞬だけ対象から外れたときに反応時間をやり直さない猶予 [ms] */
	private static final long RETARGET_GRACE_MS = 500L;
	/** 進行方向バイアスの追従時定数 [ms] */
	private static final double STRAFE_BIAS_TAU_MS = 220.0;
	/** 横移動の基準速度 [blocks/tick]（4.317 m/s 相当） */
	public static final double STRAFE_REFERENCE = 0.216;

	/**
	 * 近接武器として扱うアイテム。1.21 以降 {@code SwordItem}/{@code AxeItem} は
	 * データコンポーネント方式に移ったので実体（{@link Items}）で判定する。
	 */
	/**
	 ** 遅延初期化。{@code Items.*} はレジストリ（Bootstrap）が要るので、
	 * 静的初期化で触るとテスト用のヘッドレス実行が落ちる。
	 */
	private static Set<Item> meleeWeapons;

	private static Set<Item> meleeWeapons() {
		Set<Item> cached = meleeWeapons;
		if (cached == null) {
			cached = Set.of(
				Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
				Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
				Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.GOLDEN_AXE,
				Items.DIAMOND_AXE, Items.NETHERITE_AXE);
			meleeWeapons = cached;
		}
		return cached;
	}

	private static final TrackingAim MODEL = new TrackingAim();

	private static LivingEntity target;
	private static LivingEntity previousTarget;
	private static long previousTargetAt = -1L;
	/** クリックで掴んだ相手を追い続ける期限 */
	private static long stickyUntil = -1L;
	/** クリックで掴んだ相手を優先するか */
	private static boolean lockedByClick;
	private static boolean lastAttackPressed;

	/** tick で決めた「狙う点」。フレーム側はこれへ向かう */
	private static Vec3d goalPoint;
	/** 進行方向による狙いの偏り [deg]（EMA） */
	private static double strafeBias;
	private static double lastX = Double.NaN;
	private static double lastZ = Double.NaN;

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

	/** いまクリックで掴んでいる最中か */
	public static boolean stickyActive(long nowMs) {
		return enabled() && nowMs < stickyUntil;
	}

	public static void release() {
		if (target != null) {
			previousTarget = target;
			previousTargetAt = System.currentTimeMillis();
		}
		target = null;
		goalPoint = null;
		lockedByClick = false;
		MODEL.reset();
	}

	/** いま相手を追っているか（他のモジュールが遠慮するための問い合わせ） */
	public static boolean isTracking() {
		return enabled() && target != null;
	}

	// ------------------------------------------------------------------
	// tick: 相手を選ぶ（20 回/秒で十分）
	// ------------------------------------------------------------------

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

		handleClick(client, player, cfg, now);

		boolean active = !cfg.aimAssistClickAcquire || now < stickyUntil;
		if (cfg.aimAssistOnlyWhileAttacking && !VirtualKeys.physical(client.options.attackKey)) {
			active = false;
		}
		if (!active) {
			release();
			return;
		}

		MODEL.setHumanize(cfg.aimAssistHumanize);
		updateStrafeBias(player, cfg);

		LivingEntity picked;
		if (lockedByClick && target != null && stillValid(player, target, cfg)) {
			picked = target;
		} else {
			lockedByClick = false;
			picked = pickTarget(client, player, cfg);
		}
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
		goalPoint = computeGoal(picked, cfg);
	}

	// ------------------------------------------------------------------
	// フレーム: 回転を足す（60fps でも滑らか）
	// ------------------------------------------------------------------

	/**
	 * 描画フレームごとに 1 回呼ぶ。
	 *
	 * <p><b>ここが「かくかく」対策の本体</b>。以前は</p>
	 * <ul>
	 *   <li>狙う点を 20Hz の tick でしか更新していなかった（相手が動くと 50ms ごとに
	 *       目標が飛ぶ）</li>
	 *   <li>「誤差が悪化したフレームは回転を捨てる」「安全域を外れたら強い補正を足す」
	 *       という 2 つのルールで、フレームによって回転量が 0 になったり跳ねたりしていた</li>
	 * </ul>
	 * <p>いまは毎フレーム狙い点を計算し直し、回転は「誤差より大きくならない」ように
	 * クランプするだけにした。捨てるフレームが無いので、速度が連続になる。</p>
	 *
	 * @param dtMs 前フレームからの経過 [ms]
	 */
	public static void frameStep(MinecraftClient client, double dtMs) {
		ClientPlayerEntity player = client.player;
		if (!enabled() || player == null || client.world == null
			|| client.currentScreen != null || target == null
			|| !player.isAlive() || player.isSpectator() || ScaffoldModule.isEngaged()) {
			return;
		}
		if (target.isRemoved() || !target.isAlive()) {
			release();
			return;
		}

		long now = System.currentTimeMillis();
		FastPlaceConfig cfg = FastPlaceConfig.get();
		Vec3d eye = player.getEyePos();
		// 狙い点は毎フレーム作り直す（相手の今の位置 + 先読み）
		// tick の値を使い回すと、目標が 50ms ごとにワープして見える
		Vec3d goal = computeGoal(target, cfg);

		double[] goalAngles = anglesTo(eye, goal);
		double errYaw = MathHelper.wrapDegrees((float) (goalAngles[0] - player.getYaw()));
		double errPitch = goalAngles[1] - player.getPitch();

		double[] delta = MODEL.stepFrame(now, dtMs, errYaw, errPitch,
			cfg.aimAssistStrength, strafeBias, cfg.aimAssistSmoothing);
		if (Math.abs(delta[0]) < 1.0e-4 && Math.abs(delta[1]) < 1.0e-4) {
			return;
		}

		float yaw = player.getYaw();
		float pitch = player.getPitch();
		// 誤差を跨がない（行き過ぎない）ことだけを保証する。ここでフレームを捨てると
		// 「動く / 止まる」が交互に来て、それがカクつきになる
		double stepMag = Math.min(Math.hypot(delta[0], delta[1]),
			Math.hypot(errYaw, errPitch) * 0.92);
		double norm = Math.hypot(delta[0], delta[1]);
		if (norm < 1.0e-6) {
			return;
		}
		player.setYaw(yaw + (float) (delta[0] / norm * stepMag));
		player.setPitch(MathHelper.clamp(pitch + (float) (delta[1] / norm * stepMag),
			-90.0f, 90.0f));
	}

	// ------------------------------------------------------------------
	// クリックで掴む
	// ------------------------------------------------------------------

	private static void handleClick(MinecraftClient client, ClientPlayerEntity player,
			FastPlaceConfig cfg, long now) {
		boolean pressed = VirtualKeys.physical(client.options.attackKey);
		boolean clicked = pressed && !lastAttackPressed;
		lastAttackPressed = pressed;
		if (!clicked || !cfg.aimAssistClickAcquire) {
			return;
		}
		LivingEntity clickedTarget = pickClickTarget(client, player,
			cfg.aimAssistClickBoxScale, cfg.aimAssistPlayersOnly);
		if (clickedTarget == null) {
			return;
		}
		stickyUntil = now + (long) cfg.aimAssistStickMs;
		target = clickedTarget;
		lockedByClick = true;
		MODEL.acquire(now);
	}

	/**
	 * 視線の先にいる相手（当たり判定を {@code boxScale} 倍に広げて判定）。
	 * ブロックや他のエンティティに遮られていたら選ばない。
	 */
	public static LivingEntity pickClickTarget(MinecraftClient client, ClientPlayerEntity player,
			double boxScale, boolean playersOnly) {
		if (client.world == null) {
			return null;
		}
		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d end = eye.add(look.multiply(MAX_RANGE + 1.0));

		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || living == player) {
				continue;
			}
			if (!living.isAlive() || living.isRemoved() || living.isInvisible()) {
				continue;
			}
			if (playersOnly && !(living instanceof PlayerEntity)) {
				continue;
			}
			if (player.squaredDistanceTo(living) > MAX_RANGE * MAX_RANGE) {
				continue;
			}
			Box box = scaledHitbox(living.getBoundingBox(), boxScale);
			Optional<Vec3d> hit = box.raycast(eye, end);
			if (hit.isEmpty()) {
				continue;
			}
			Vec3d point = hit.get();
			if (!clearLine(player, client.world, eye, point, living)) {
				continue;
			}
			double distance = eye.squaredDistanceTo(point);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = living;
			}
		}
		return best;
	}

	/** 当たり判定を倍率 {@code scale} 倍に広げた箱（実際の判定は広がらない） */
	public static Box scaledHitbox(Box box, double scale) {
		double s = Math.max(1.0, scale);
		double cx = (box.minX + box.maxX) * 0.5;
		double cy = (box.minY + box.maxY) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		double hx = (box.maxX - box.minX) * 0.5 * s;
		double hy = (box.maxY - box.minY) * 0.5 * s;
		double hz = (box.maxZ - box.minZ) * 0.5 * s;
		return new Box(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
	}

	/** 視線が相手まで遮られていないか（ブロック・他のエンティティ） */
	public static boolean clearLine(ClientPlayerEntity player, World world, Vec3d eye, Vec3d point,
			Entity ignore) {
		if (world == null) {
			return false;
		}
		BlockHitResult blockHit = world.raycast(new RaycastContext(eye, point,
			RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
		double targetDistance = eye.squaredDistanceTo(point);
		if (blockHit.getType() == HitResult.Type.BLOCK
			&& blockHit.getPos().squaredDistanceTo(eye) < targetDistance - 1.0e-3) {
			return false;
		}
		Box sweep = new Box(eye, point).expand(0.05);
		for (Entity other : world.getOtherEntities(player, sweep)) {
			if (other == ignore || other.isSpectator() || other.isRemoved()) {
				continue;
			}
			if (other.getBoundingBox().expand(0.02).raycast(eye, point).isPresent()) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------
	// 相手を選ぶ / 狙う点
	// ------------------------------------------------------------------

	private static boolean stillValid(ClientPlayerEntity player, LivingEntity candidate, FastPlaceConfig cfg) {
		if (candidate.isRemoved() || !candidate.isAlive() || candidate.isInvisible()) {
			return false;
		}
		if (cfg.aimAssistPlayersOnly && !(candidate instanceof PlayerEntity)) {
			return false;
		}
		return Math.sqrt(player.squaredDistanceTo(candidate)) <= RELEASE_RANGE && player.canSee(candidate);
	}

	private static LivingEntity pickTarget(MinecraftClient client, ClientPlayerEntity player, FastPlaceConfig cfg) {
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
			if (cfg.aimAssistPlayersOnly && !(living instanceof PlayerEntity)) {
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

	private static Vec3d computeGoal(LivingEntity picked, FastPlaceConfig cfg) {
		Vec3d point = aimPoint(picked.getBoundingBox());
		if (cfg.aimAssistPredict) {
			// 相手の速度 × 知覚遅れ × 係数。人間は追従の遅れぶんを予測で埋める
			point = point.add(picked.getVelocity().multiply(LEAD_SECONDS * 20.0 * LEAD_GAIN));
		}
		return point;
	}

	/** 進行方向と逆向きに数度ずれる量を、ゆっくり追従させる（自己運動の残差） */
	private static void updateStrafeBias(ClientPlayerEntity player, FastPlaceConfig cfg) {
		double x = player.getX();
		double z = player.getZ();
		double dx = Double.isNaN(lastX) ? 0.0 : x - lastX;
		double dz = Double.isNaN(lastZ) ? 0.0 : z - lastZ;
		lastX = x;
		lastZ = z;

		double yawRad = Math.toRadians(player.getYaw());
		double rightX = Math.cos(yawRad);
		double rightZ = Math.sin(yawRad);
		double lateral = dx * rightX + dz * rightZ;
		double wanted = strafeBiasTarget(lateral, cfg.aimAssistStrafeBiasDeg);
		double alpha = 1.0 - Math.exp(-50.0 / STRAFE_BIAS_TAU_MS);
		strafeBias += alpha * (wanted - strafeBias);
	}

	/**
	 * 横移動 1 blocks/tick あたりの狙いの偏り [deg]。
	 * 右へ動くと負（狙いはわずかに左に残る）。
	 */
	public static double strafeBiasTarget(double lateralPerTick, double maxDeg) {
		double normalized = Math.max(-1.0, Math.min(1.0, lateralPerTick / STRAFE_REFERENCE));
		return -normalized * maxDeg;
	}

	/** いまの進行方向バイアス [deg]（テスト・HUD 用） */
	public static double strafeBiasDeg() {
		return strafeBias;
	}

	private static Vec3d aimPoint(Box box) {
		double x = (box.minX + box.maxX) * 0.5;
		double z = (box.minZ + box.maxZ) * 0.5;
		double y = box.minY + box.getLengthY() * AIM_HEIGHT;
		return new Vec3d(x, y, z);
	}

	private static boolean isMeleeWeapon(ItemStack stack) {
		return meleeWeapons().contains(stack.getItem());
	}

	// ------------------------------------------------------------------
	// 角度
	// ------------------------------------------------------------------

	public static double[] anglesTo(Vec3d from, Vec3d to) {
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
