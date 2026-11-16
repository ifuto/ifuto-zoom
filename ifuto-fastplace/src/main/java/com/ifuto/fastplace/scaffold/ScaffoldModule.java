package com.ifuto.fastplace.scaffold;

import com.ifuto.fastplace.FastPlaceConfig;
import com.ifuto.fastplace.human.PlacementRhythm;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * God Bridge 用 Scaffold。
 *
 * <p><b>何をするか</b>: ブロックを持って前へ歩くだけで、足元の続きへ自動でブロックを置く。
 * スニークもジャンプも使わない。エンティティのピッチ（= サーバーに送る本物の向き）は
 * ちゃんとブロックへ向けるが、カメラは {@link ScaffoldView} が分離しているので
 * プレイヤーの視界は前を向いたまま。パケットの偽装は一切しない。</p>
 *
 * <p><b>人間らしさ</b>: 設置位置は「今乗っているブロックの側面」を狙う（本物の
 * ブリッジと同じ場所）。狙いは {@link com.ifuto.fastplace.human.HumanAim} の
 * サッケード／追従モデルで動かし、置くタイミングは
 * {@link PlacementRhythm} の間隔モデルで揺らす。歩く速さで設置数が決まるので、
 * 一定 CPS の機械連打にはならない。</p>
 *
 * <p><b>休む条件</b>: モジュール OFF / ブロックを手放した / 画面を開いた /
 * スペクテイター・乗り物・使用中。休むときはエンティティのピッチを
 * 人間の「見上げる」速度で今の視点へ戻す。</p>
 */
public final class ScaffoldModule {
	/** 目の位置から置く面までの最大距離（バニラは 4.5。ラグを見て少し詰める） */
	private static final double REACH = 4.25;
	/** 狙いが合ったとみなす角度 [deg] */
	private static final float ALIGN_DEG = 5.0f;
	/** 置く必要がなくなってから視点を戻し始めるまで [ms] */
	private static final long RELAX_AFTER_MS = 520L;
	/** 自分の設置がサーバーに反映されるまで「埋まった」とみなす時間 [ms] */
	private static final long PENDING_TTL_MS = 900L;
	/** この距離まで先のギャップを見て狙い始める [blocks] */
	private static final double LOOKAHEAD = 2.8;
	/** 復帰が終わらないときの打ち切り [ms] */
	private static final long RESTORE_TIMEOUT_MS = 3000L;
	/** 何ブロック下まで地面を探すか */
	private static final int MAX_PLANE_DROP = 4;
	/** 設置を始める距離（人間ごとのクセ。ブロックごとに引き直す） */
	private static final double TRIGGER_MIN = 1.15;
	private static final double TRIGGER_MAX = 1.70;

	private static final PlacementRhythm RHYTHM = new PlacementRhythm();
	private static final Random RANDOM = new Random();
	private static final Map<Long, Long> PENDING = new HashMap<>();

	private static ClientWorld lastWorld;
	private static int planeTop = Integer.MIN_VALUE;
	/** 足元から 4 ブロック以内に地面が無い（＝奈落の上） */
	private static boolean overVoid;
	private static Target currentTarget;
	private static long idleSince = -1;
	private static long nextClickAt = 0;
	private static double triggerDist = 1.45;
	private static float lastAimPitch = 0.0f;
	private static long restoreSince = -1;

	private ScaffoldModule() {
	}

	public static boolean enabled() {
		return FastPlaceConfig.get().scaffoldEnabled;
	}

	public static void setEnabled(boolean on) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		cfg.scaffoldEnabled = on;
		cfg.save();
		if (!on) {
			clearAimState();
		}
	}

	public static void toggle() {
		setEnabled(!enabled());
	}

	/** いま体をブロックへ向けている（復帰中も含む） */
	public static boolean isEngaged() {
		return ScaffoldView.isCapturing();
	}

	/** ジャンプ入力だけを落とした入力（Scaffold 中は跳ばない） */
	public static PlayerInput withoutJump(PlayerInput input) {
		if (input == null || !input.jump() || !blocksJump()) {
			return input;
		}
		return new PlayerInput(
			input.forward(), input.backward(), input.left(), input.right(),
			false, input.sneak(), input.sprint());
	}

	/** ジャンプ入力を落とすか */
	public static boolean blocksJump() {
		return FastPlaceConfig.get().scaffoldEnabled
			&& FastPlaceConfig.get().scaffoldBlockJump
			&& ScaffoldView.isCapturing();
	}

	public static void reset() {
		ScaffoldView.reset();
		RHYTHM.reset();
		PENDING.clear();
		lastWorld = null;
		planeTop = Integer.MIN_VALUE;
		clearAimState();
		lastAimPitch = 0.0f;
		triggerDist = 1.45;
	}

	public static void tick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null) {
			if (lastWorld != null || ScaffoldView.isCapturing()) {
				lastWorld = null;
				planeTop = Integer.MIN_VALUE;
				clearAimState();
				ScaffoldView.reset();
			}
			return;
		}
		if (client.world != lastWorld) {
			lastWorld = client.world;
			planeTop = Integer.MIN_VALUE;
			clearAimState();
			RHYTHM.reset();
			PENDING.clear();
			ScaffoldView.reset();
		}

		RHYTHM.tick(now);
		prunePending(now);

		boolean capturing = ScaffoldView.isCapturing();
		if (capturing) {
			// マウスで動いた分を視点（カメラ）側へ回収してから、こちらの向きを書き直す
			ScaffoldView.syncFromEntity(player);
		}

		if (client.currentScreen != null || !eligible(client, player)) {
			if (capturing) {
				restore(player, now);
			} else {
				clearAimState();
			}
			return;
		}

		updatePlane(client, player);
		if (planeTop == Integer.MIN_VALUE) {
			if (capturing) {
				restore(player, now);
			}
			return;
		}

		Vec3d intended = inputDirection(client, player);
		Vec3d dir = intended != null ? intended : velocityDirection(player);
		if (dir == null) {
			dir = yawForward(player);
		}
		// 入力が無い（立ち止まっている）なら、下を見続けずに人の速度で戻る。
		// ただし奈落へ落ちている最中は、入力が無くても拾う。
		boolean deliberate = intended != null;
		boolean falling = overVoid && player.getVelocity().y < -0.06;

		Target target = findTarget(client.world, player, dir, now);

		if (target == null || (!deliberate && !falling)) {
			if (capturing) {
				if (idleSince < 0) {
					idleSince = now;
				}
				if (now - idleSince >= RELAX_AFTER_MS) {
					restore(player, now);
				} else {
					// 置く必要はないが、人がやるように少しの間は下を見たまま
					ScaffoldView.stepToward(player, lastAimPitch, now);
				}
			} else {
				clearAimState();
			}
			return;
		}
		idleSince = -1;

		if (!capturing) {
			ScaffoldView.begin(player,
				FastPlaceConfig.get().scaffoldHumanize,
				now);
			// 人が「見てから押す」までの反応時間
			nextClickAt = now + RHYTHM.reactionMs(RANDOM);
			lastAimPitch = player.getPitch();
		}

		float aimPitch = pitchTo(player, target.hit);
		float appliedPitch = ScaffoldView.stepToward(player, aimPitch, now);
		lastAimPitch = aimPitch;

		maybePlace(client, player, target, dir, appliedPitch, now);
	}

	// ------------------------------------------------------------------
	// 条件
	// ------------------------------------------------------------------

	private static boolean eligible(MinecraftClient client, ClientPlayerEntity player) {
		FastPlaceConfig cfg = FastPlaceConfig.get();
		if (!cfg.scaffoldEnabled) {
			return false;
		}
		if (!(player.getMainHandStack().getItem() instanceof BlockItem)) {
			return false;
		}
		if (player.getHealth() <= 0.0f) {
			return false;
		}
		if (player.isRiding() || player.isUsingItem()) {
			return false;
		}
		ClientPlayerInteractionManager interaction = client.interactionManager;
		return interaction != null && interaction.getCurrentGameMode() != GameMode.SPECTATOR;
	}

	// ------------------------------------------------------------------
	// 足場の高さ
	// ------------------------------------------------------------------

	private static void updatePlane(MinecraftClient client, ClientPlayerEntity player) {
		int top = surfaceTop(client.world, player);
		overVoid = top == Integer.MIN_VALUE;
		if (top != Integer.MIN_VALUE && Math.abs(player.getY() - top) <= 0.4) {
			planeTop = top;
		} else if (planeTop == Integer.MIN_VALUE) {
			planeTop = top != Integer.MIN_VALUE ? top : (int) Math.floor(player.getY());
		}
		if (Math.abs(player.getY() - planeTop) > 6.0) {
			planeTop = top != Integer.MIN_VALUE ? top : (int) Math.floor(player.getY());
		}
	}

	/** 足元のいちばん上の固体ブロックの「上面 Y」。無ければ MIN_VALUE */
	private static int surfaceTop(ClientWorld world, ClientPlayerEntity player) {
		int fx = (int) Math.floor(player.getX());
		int fz = (int) Math.floor(player.getZ());
		int fy = (int) Math.floor(player.getY() + 0.001);
		for (int i = 0; i <= MAX_PLANE_DROP; i++) {
			BlockPos pos = new BlockPos(fx, fy - i, fz);
			if (isSolid(world, pos)) {
				return pos.getY() + 1;
			}
		}
		return Integer.MIN_VALUE;
	}

	// ------------------------------------------------------------------
	// 進む向き
	// ------------------------------------------------------------------

	/** WASD 入力から見た進行方向。入力が無ければ null */
	private static Vec3d inputDirection(MinecraftClient client, ClientPlayerEntity player) {
		float forward = 0.0f;
		float strafe = 0.0f;
		if (client.options != null) {
			forward = (client.options.forwardKey.isPressed() ? 1.0f : 0.0f)
				- (client.options.backKey.isPressed() ? 1.0f : 0.0f);
			strafe = (client.options.rightKey.isPressed() ? 1.0f : 0.0f)
				- (client.options.leftKey.isPressed() ? 1.0f : 0.0f);
		}
		if (forward == 0.0f && strafe == 0.0f) {
			return null;
		}
		double yaw = Math.toRadians(player.getYaw());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		double x = strafe * cos - forward * sin;
		double z = forward * cos + strafe * sin;
		double length = Math.hypot(x, z);
		if (length < 1.0e-4) {
			return null;
		}
		return new Vec3d(x / length, 0.0, z / length);
	}

	/** 実際に動いている向き（押されていないが慣性で滑っている場合） */
	private static Vec3d velocityDirection(ClientPlayerEntity player) {
		Vec3d velocity = player.getVelocity();
		double length = Math.hypot(velocity.x, velocity.z);
		if (length < 0.06) {
			return null;
		}
		return new Vec3d(velocity.x / length, 0.0, velocity.z / length);
	}

	/** どこにも動いていないときの保険（視線の水平方向） */
	private static Vec3d yawForward(ClientPlayerEntity player) {
		double yaw = Math.toRadians(player.getYaw());
		return new Vec3d(-Math.sin(yaw), 0.0, Math.cos(yaw));
	}

	// ------------------------------------------------------------------
	// 置く場所を探す
	// ------------------------------------------------------------------

	private static Target findTarget(ClientWorld world, ClientPlayerEntity player, Vec3d dir, long now) {
		int y = planeTop - 1;
		BlockPos stand = new BlockPos(
			(int) Math.floor(player.getX()), y, (int) Math.floor(player.getZ()));

		// 1) 奈落へ落ちかけているなら、まず足元を埋める（段差を降りるだけなら何もしない）
		if (overVoid && player.getVelocity().y < -0.06 && placeable(world, stand, now)) {
			Target emergency = pickFace(world, player, stand, dir, stand, now);
			if (emergency != null) {
				return emergency;
			}
		}

		// 2) 進行方向で最初に空いている列
		BlockPos firstAir = null;
		for (int step = 1; step <= 3; step++) {
			BlockPos pos = new BlockPos(
				(int) Math.floor(player.getX() + dir.x * step),
				y,
				(int) Math.floor(player.getZ() + dir.z * step));
			if (pos.equals(stand)) {
				continue;
			}
			if (placeable(world, pos, now)) {
				firstAir = pos;
				break;
			}
			if (!solidOrPending(world, pos, now)) {
				// 空でも自分の設置待ちでもない（水・草など）→ ここは触らない
				break;
			}
		}
		if (firstAir == null) {
			return null;
		}
		double distance = horizontalDistance(player, firstAir);
		if (distance > LOOKAHEAD) {
			currentTarget = null;
			return null;
		}
		return pickFace(world, player, firstAir, dir, stand, now);
	}

	/**
	 * 置くブロックに対して、どの隣接ブロックのどの面を叩くかを決める。
	 * 本物のブリッジと同じく「今乗っているブロックの側面」を強く好む。
	 */
	private static Target pickFace(ClientWorld world, ClientPlayerEntity player, BlockPos target,
			Vec3d dir, BlockPos stand, long now) {
		if (currentTarget != null && currentTarget.pos.equals(target) && stillValid(world, currentTarget, now)) {
			// 同じブロックを狙っている間はヒット点（＝狙いのクセ）を維持する
			return currentTarget;
		}
		Vec3d eye = player.getEyePos();
		Target best = null;
		double bestScore = -1.0e9;
		for (Direction towardsNeighbor : Direction.values()) {
			BlockPos neighbor = target.offset(towardsNeighbor);
			if (!isSolid(world, neighbor)) {
				continue;
			}
			Direction side = towardsNeighbor.getOpposite();
			Vec3d normal = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
			Vec3d center = new Vec3d(neighbor.getX() + 0.5, neighbor.getY() + 0.5, neighbor.getZ() + 0.5);
			Vec3d hit = jitterOnFace(center.add(normal.multiply(0.5)), side);
			double dist = eye.distanceTo(hit);
			if (dist > REACH) {
				continue;
			}
			Vec3d toEye = eye.subtract(hit);
			double length = toEye.length();
			if (length < 1.0e-3) {
				continue;
			}
			double visibility = toEye.multiply(1.0 / length).dotProduct(normal);
			if (visibility < 0.15) {
				continue;   // 面が自分から見えていない
			}
			double score = visibility;
			if (neighbor.equals(stand)) {
				score += 1.4;   // 足元のブロックから伸ばす
			}
			if (-(normal.x * dir.x + normal.z * dir.z) > 0.5) {
				score += 0.35;  // 進行方向と逆向きの面（後ろから伸ばす）
			}
			if (side.getOffsetY() > 0) {
				score -= 0.4;   // 上面より側面のほうがブリッジらしい
			}
			if (score > bestScore) {
				bestScore = score;
				best = new Target(target, neighbor, side, hit, dist);
			}
		}
		if (best != null) {
			currentTarget = best;
		}
		return best;
	}

	private static Vec3d jitterOnFace(Vec3d point, Direction side) {
		double a = (RANDOM.nextDouble() * 2.0 - 1.0) * 0.18;
		double b = (RANDOM.nextDouble() * 2.0 - 1.0) * 0.18;
		return switch (side.getAxis()) {
			case X -> new Vec3d(point.x, point.y + a, point.z + b);
			case Y -> new Vec3d(point.x + a, point.y, point.z + b);
			case Z -> new Vec3d(point.x + a, point.y + b, point.z);
		};
	}

	private static boolean stillValid(ClientWorld world, Target target, long now) {
		if (isSolid(world, target.pos)) {
			return false;
		}
		if (!isSolid(world, target.neighbor)) {
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------
	// 置く
	// ------------------------------------------------------------------

	private static void maybePlace(MinecraftClient client, ClientPlayerEntity player, Target target,
			Vec3d dir, float appliedPitch, long now) {
		boolean emergency = target.pos.getX() == (int) Math.floor(player.getX())
			&& target.pos.getZ() == (int) Math.floor(player.getZ());

		if (!emergency) {
			double distance = horizontalDistance(player, target.pos);
			if (distance > triggerDist || now < nextClickAt) {
				return;
			}
		}

		Vec3d toHit = target.hit.subtract(player.getEyePos());
		double horizontal = Math.hypot(toHit.x, toHit.z);
		float targetPitch = (float) -Math.toDegrees(Math.atan2(toHit.y, horizontal));
		float targetYaw = (float) Math.toDegrees(Math.atan2(-toHit.x, toHit.z));
		float pitchError = Math.abs(appliedPitch - targetPitch);
		float yawError = Math.abs(wrapDegrees(targetYaw - player.getYaw()));
		// 緊急（奈落へ落下中）だけは視野を広めに許す。落ちるよりは置く
		float pitchTolerance = emergency ? 25.0f : ALIGN_DEG;
		float yawTolerance = emergency ? 180.0f : 35.0f;
		if (pitchError > pitchTolerance || yawError > yawTolerance) {
			return;   // まだ狙いが合っていない（人のように見てから押す）
		}

		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (interaction == null) {
			return;
		}
		ActionResult result = interaction.interactBlock(player, Hand.MAIN_HAND,
			new BlockHitResult(target.hit, target.side, target.neighbor, false));
		if (result.isAccepted()) {
			if (FastPlaceConfig.get().scaffoldSwing) {
				player.swingHand(Hand.MAIN_HAND);
			}
			markPending(target.pos, now);
			triggerDist = TRIGGER_MIN + RANDOM.nextDouble() * (TRIGGER_MAX - TRIGGER_MIN);
			// 人間のテンポは入れたいが、先に床が尽きるなら待たない（落ちない範囲でだけ怠ける）
			int runway = runway(client.world, player, dir, now);
			long gap = RHYTHM.nextGapMs(RANDOM, now, runway >= 3);
			// 設定の「設置ペース」は人間モデルの間隔そのものを伸縮する
			double cadence = clamp(FastPlaceConfig.get().scaffoldCadence, 0.6, 1.4);
			long paced = Math.round(gap / cadence);
			long safeGap = Math.min(paced, maxSafeGapMs(runway, player));
			nextClickAt = now + Math.max((long) PlacementRhythm.MIN_GAP_MS, safeGap);
			currentTarget = null;
			idleSince = -1;
		} else {
			nextClickAt = now + 140L + RANDOM.nextInt(160);
		}
	}

	/** 進行方向に連続して埋まっている列の数（0〜6）。「余裕」の指標 */
	private static int runway(ClientWorld world, ClientPlayerEntity player, Vec3d dir, long now) {
		int count = 0;
		for (int step = 1; step <= 6; step++) {
			BlockPos pos = new BlockPos(
				(int) Math.floor(player.getX() + dir.x * step),
				planeTop - 1,
				(int) Math.floor(player.getZ() + dir.z * step));
			if (!solidOrPending(world, pos, now)) {
				break;
			}
			count++;
		}
		return count;
	}

	/** その余裕で落ちずに待てる最大時間 [ms] */
	private static long maxSafeGapMs(int runway, ClientPlayerEntity player) {
		Vec3d velocity = player.getVelocity();
		double speedPerTick = Math.max(0.06, Math.hypot(velocity.x, velocity.z));
		double travel = Math.max(0.12, runway - 0.45);
		return Math.round(travel / speedPerTick * 50.0);
	}

	// ------------------------------------------------------------------
	// 復帰
	// ------------------------------------------------------------------

	private static void restore(ClientPlayerEntity player, long now) {
		if (!ScaffoldView.isCapturing()) {
			clearAimState();
			return;
		}
		if (restoreSince < 0) {
			restoreSince = now;
		}
		float targetPitch = ScaffoldView.visualPitch();
		ScaffoldView.stepToward(player, targetPitch, now);
		// 収束しないまま固まらないよう、長すぎたら打ち切って視点を返す
		if (ScaffoldView.settledAt(targetPitch) || now - restoreSince > RESTORE_TIMEOUT_MS) {
			ScaffoldView.finish(player);
			clearAimState();
		}
	}

	// ------------------------------------------------------------------
	// 小物
	// ------------------------------------------------------------------

	private static void clearAimState() {
		idleSince = -1;
		restoreSince = -1;
		currentTarget = null;
		nextClickAt = 0;
	}

	private static boolean placeable(ClientWorld world, BlockPos pos, long now) {
		return world.getBlockState(pos).isAir() && !isPending(pos, now);
	}

	private static boolean solidOrPending(ClientWorld world, BlockPos pos, long now) {
		return isSolid(world, pos) || isPending(pos, now);
	}

	private static boolean isSolid(ClientWorld world, BlockPos pos) {
		return world.getBlockState(pos).isSolidBlock(world, pos);
	}

	private static void markPending(BlockPos pos, long now) {
		PENDING.put(pos.asLong(), now + PENDING_TTL_MS);
	}

	private static boolean isPending(BlockPos pos, long now) {
		Long expiry = PENDING.get(pos.asLong());
		return expiry != null && expiry > now;
	}

	private static void prunePending(long now) {
		Iterator<Map.Entry<Long, Long>> iterator = PENDING.entrySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().getValue() <= now) {
				iterator.remove();
			}
		}
	}

	private static double horizontalDistance(ClientPlayerEntity player, BlockPos pos) {
		return Math.hypot(player.getX() - (pos.getX() + 0.5), player.getZ() - (pos.getZ() + 0.5));
	}

	private static float pitchTo(ClientPlayerEntity player, Vec3d point) {
		Vec3d delta = point.subtract(player.getEyePos());
		double horizontal = Math.hypot(delta.x, delta.z);
		return (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
	}

	private static Vec3d lookVector(float yawDeg, float pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double cosPitch = Math.cos(pitch);
		return new Vec3d(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
	}

	private static float wrapDegrees(float degrees) {
		float value = degrees % 360.0f;
		if (value >= 180.0f) {
			value -= 360.0f;
		}
		if (value < -180.0f) {
			value += 360.0f;
		}
		return value;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}

	private static final class Target {
		final BlockPos pos;
		final BlockPos neighbor;
		final Direction side;
		final Vec3d hit;
		final double distance;

		Target(BlockPos pos, BlockPos neighbor, Direction side, Vec3d hit, double distance) {
			this.pos = pos;
			this.neighbor = neighbor;
			this.side = side;
			this.hit = hit;
			this.distance = distance;
		}
	}
}
