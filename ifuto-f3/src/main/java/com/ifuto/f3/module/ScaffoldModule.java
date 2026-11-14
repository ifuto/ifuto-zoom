package com.ifuto.f3.module;

import com.ifuto.f3.F3Config;
import net.minecraft.item.BlockItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * 毎 tick 呼ばれる、動かしても安全な scaffold 本体。
 * 手順: 進行方向の足元先を見て、隙間があれば視点をその面へ寄せ、
 * ほぼ合ったら interactBlock を投げて目 → 置くの順を守る。
 * スニークなしで正面歩きブリッジができ、サーバーから見た動きは
 * 「ちゃんと面を見て置いている人」になる。
 */
public final class ScaffoldModule {
	private static final float YAW_SPEED = 0.45f;
	private static final float PITCH_SPEED = 0.45f;
	private static final float RESTORE_SPEED = 0.15f;
	private static final float ALIGN_TOLERANCE = 25.0f;
	private static final int PLACE_COOLDOWN_TICKS = 4;
	private static final int FAIL_COOLDOWN_TICKS = 8;

	private static int cooldown = 0;
	private static Float savedPitch = null;
	private static boolean wasActive = false;

	private ScaffoldModule() {
	}

	public static boolean enabled() {
		return F3Config.get().scaffoldEnabled;
	}

	public static boolean blocksJump() {
		return enabled();
	}

	public static void setEnabled(boolean on) {
		F3Config.get().scaffoldEnabled = on;
		F3Config.get().save();
		if (!on) {
			resetAim();
		}
	}

	public static void tick(MinecraftClient client) {
		if (cooldown > 0) {
			cooldown--;
		}
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (player == null || world == null) {
			resetAim();
			return;
		}
		if (!enabled() || client.currentScreen != null || client.interactionManager == null) {
			restorePitchIfNeeded(client);
			wasActive = false;
			return;
		}
		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (interaction.getCurrentGameMode() == GameMode.SPECTATOR) {
			restorePitchIfNeeded(client);
			wasActive = false;
			return;
		}
		ItemStack stack = player.getMainHandStack();
		boolean holdingBlock = stack.getItem() instanceof BlockItem;
		if (!client.options.forwardKey.isPressed() || !holdingBlock) {
			restorePitchIfNeeded(client);
			wasActive = false;
			return;
		}

		float yaw = player.getYaw();
		Direction dir = facingFromYaw(yaw);
		BlockPos feet = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
		BlockPos target = feet.offset(dir).down();
		BlockPos neighbor = target.offset(dir.getOpposite());

		boolean targetFree = world.getBlockState(target).isAir();
		boolean neighborSolid = world.getBlockState(neighbor).isSolidBlock(world, neighbor);
		if (!targetFree || !neighborSolid) {
			restorePitchIfNeeded(client);
			wasActive = false;
			return;
		}

		if (!wasActive) {
			savedPitch = player.getPitch();
			wasActive = true;
		}

		Vec3d eye = player.getEyePos();
		Vec3d face = faceCenter(neighbor, dir);
		double dx = face.x - eye.x;
		double dy = face.y - eye.y;
		double dz = face.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float aimPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		float aimYaw = (float) wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)));

		float yawNow = player.getYaw();
		float pitchNow = player.getPitch();
		player.setYaw(yawNow + (float) wrapDegrees(aimYaw - yawNow) * YAW_SPEED);
		player.setPitch(pitchNow + (aimPitch - pitchNow) * PITCH_SPEED);

		float yawError = (float) Math.abs(wrapDegrees(aimYaw - player.getYaw()));
		float pitchError = Math.abs(aimPitch - player.getPitch());
		if (cooldown > 0 || yawError > ALIGN_TOLERANCE || pitchError > ALIGN_TOLERANCE) {
			return;
		}

		BlockHitResult hit = new BlockHitResult(face, dir, neighbor, false);
		ActionResult result = interaction.interactBlock(player, Hand.MAIN_HAND, hit);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
			cooldown = PLACE_COOLDOWN_TICKS;
		} else {
			cooldown = FAIL_COOLDOWN_TICKS;
		}
	}

	public static void resetAim() {
		wasActive = false;
		savedPitch = null;
		cooldown = 0;
	}

	private static void restorePitchIfNeeded(MinecraftClient client) {
		if (savedPitch == null || client.player == null) {
			return;
		}
		float pitch = client.player.getPitch();
		float next = pitch + (savedPitch - pitch) * RESTORE_SPEED;
		if (Math.abs(savedPitch - next) < 1.0f) {
			next = savedPitch;
			savedPitch = null;
		}
		client.player.setPitch(next);
	}

	private static Direction facingFromYaw(float yaw) {
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		if (Math.abs(fx) > Math.abs(fz)) {
			return fx > 0 ? Direction.EAST : Direction.WEST;
		}
		return fz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private static Vec3d faceCenter(BlockPos pos, Direction face) {
		double ox = face.getOffsetX() * 0.5;
		double oy = face.getOffsetY() * 0.5;
		double oz = face.getOffsetZ() * 0.5;
		return new Vec3d(pos.getX() + 0.5 + ox, pos.getY() + 0.5 + oy, pos.getZ() + 0.5 + oz);
	}

	private static double wrapDegrees(double v) {
		v = v % 360.0;
		if (v >= 180.0) {
			v -= 360.0;
		}
		if (v < -180.0) {
			v += 360.0;
		}
		return v;
	}
}
