package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.AttributeContainer;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * 「途中から録り始めた」録画でも再生できるようにするための世界の写し。
 *
 * <p>パケットを前に巻き戻せない都合上、再生には「世界を作る最初のパケット」が必要になる。
 * サーバーに取り直しを頼むことは（BAN を避けるため）絶対にしないので、
 * **すでに手元にある世界** から「サーバーが送ってきたのと同じパケット」を組み立てて、
 * 録画の先頭に置く。使うのはバニラがサーバー側で使っているのと同じコンストラクタなので、
 * 形が変わっても追従できる。
 *
 * <p>書く順番はバニラの「ログイン → 世界の情報 → 地形 → 实体 → 自分の状態」と同じ。
 */
public final class WorldSnapshot {
	/** 1回で保存するチャンクの上限（開始が重くなりすぎないための安全弁） */
	private static final int MAX_CHUNKS = 4096;

	/** 装備のスロット（バニラの送信順） */
	private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
			EquipmentSlot.MAINHAND,
			EquipmentSlot.OFFHAND,
			EquipmentSlot.FEET,
			EquipmentSlot.LEGS,
			EquipmentSlot.CHEST,
			EquipmentSlot.HEAD
	};

	/** 作った写し */
	public record Snapshot(List<Packet<?>> packets, int chunks, int entities) {
	}

	private WorldSnapshot() {
	}

	/**
	 * いまの世界から「再生の開始に必要なパケット」を組み立てる。
	 *
	 * @param radius 保存する地形の半径（チャンク）
	 * @return 作れたら内容、必要条件が足りなければ null
	 */
	public static @Nullable Snapshot build(MinecraftClient client, int radius) {
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		Packet<?> join = RecordingManager.getJoinPacket();

		if (world == null || player == null || join == null || radius <= 0) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] 写しを作れません (world={}, player={}, join={}, radius={})",
					world != null, player != null, join != null, radius);
			return null;
		}

		List<Packet<?>> packets = new ArrayList<>();

		// 1) 世界そのものを作る（入った直後のパケットを覚えてある）
		packets.add(join);

		// 入ったあとに別の次元へ移っていたら、その分も流す（これで今いる次元になる）
		Packet<?> respawn = RecordingManager.getRespawnPacket();

		if (respawn != null) {
			packets.add(respawn);
		}

		// 2) 世界の情報（時刻は「入った直後」の物がそのまま残るので送らない）
		packets.add(new PlayerAbilitiesS2CPacket(player.getAbilities()));
		packets.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED,
				world.getRainGradient(1.0F)));
		packets.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED,
				world.getThunderGradient(1.0F)));

		if (world.isRaining()) {
			packets.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STARTED, 0.0F));
		}

		// 3) 持ち物（syncId が -1 は「自分の持ち物」というバニラの決まり。空の枠は送らない）
		Inventory inventory = player.getInventory();

		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);

			if (!stack.isEmpty()) {
				packets.add(new ScreenHandlerSlotUpdateS2CPacket(-1, 0, slot, stack.copy()));
			}
		}

		// 4) 自分の状態
		packets.add(new HealthUpdateS2CPacket(player.getHealth(),
				player.getHungerManager().getFoodLevel(),
				player.getHungerManager().getSaturationLevel()));
		packets.add(new ExperienceBarUpdateS2CPacket(player.experienceProgress, player.experienceLevel,
				player.totalExperience));
		packets.add(PlayerPositionLookS2CPacket.of(0, EntityPosition.fromEntity(player), Set.of()));

		// 5) 地形（明るさもバニラのプロバイダからそのまま持ってくる。
		// 明るさの有無はサーバーと同じ見方（区画ごとにあるかないか）で調べる。
		// 無いことにする（null）と真っ暗な地形になるので、必ず本物を渡す）
		ChunkPos center = player.getChunkPos();
		ChunkManager chunks = world.getChunkManager();
		LightingProvider lighting = world.getLightingProvider();
		int chunkCount = 0;

		for (int dx = -radius; dx <= radius && chunkCount < MAX_CHUNKS; dx++) {
			for (int dz = -radius; dz <= radius && chunkCount < MAX_CHUNKS; dz++) {
				Chunk chunk = chunks.getChunk(center.x + dx, center.z + dz, ChunkStatus.FULL, false);

				if (!(chunk instanceof WorldChunk worldChunk) || worldChunk.isEmpty()) {
					continue;
				}

				try {
					packets.add(new ChunkDataS2CPacket(worldChunk, lighting,
							lightMask(worldChunk, lighting, LightType.SKY),
							lightMask(worldChunk, lighting, LightType.BLOCK)));
					chunkCount++;
				} catch (Throwable t) {
					// 1個の地形で写し全体を捨てない（欠けた所はあとから届く）
					IfutoReplayClient.LOGGER.warn("[ifuto-replay] 地形1個を写せませんでした ({}, {})",
							worldChunk.getPos().x, worldChunk.getPos().z, t);
				}
			}
		}

		// 6) 实体（自分以外。塊の中にある物だけ）
		double limit = (radius + 1) * 16.0;
		double limitSq = limit * limit;
		int entityCount = 0;

		for (Object found : world.getEntities()) {
			if (!(found instanceof Entity entity) || entity == player || !entity.isAlive() || entity.isRemoved()) {
				continue;
			}

			if (entity.squaredDistanceTo(player.getSyncedPos()) > limitSq) {
				continue;
			}

			try {
				packets.add(new EntitySpawnS2CPacket(entity, 0, BlockPos.ofFloored(entity.getSyncedPos())));
				packets.add(EntityPositionSyncS2CPacket.create(entity));
				packets.add(new EntityVelocityUpdateS2CPacket(entity));
				packets.add(new EntitySetHeadYawS2CPacket(entity,
						(byte) MathHelper.floor(entity.getHeadYaw() * 256.0F / 360.0F)));

				List<DataTracker.SerializedEntry<?>> tracked = entity.getDataTracker().getChangedEntries();

				if (tracked != null && !tracked.isEmpty()) {
					packets.add(new EntityTrackerUpdateS2CPacket(entity.getId(), tracked));
				}

				if (entity instanceof LivingEntity living) {
					AttributeContainer attributes = living.getAttributes();

					if (attributes != null) {
						packets.add(new EntityAttributesS2CPacket(entity.getId(), attributes.getAttributesToSend()));
					}

					List<Pair<EquipmentSlot, ItemStack>> equipment = equipment(living);

					if (!equipment.isEmpty()) {
						packets.add(new EntityEquipmentUpdateS2CPacket(entity.getId(), equipment));
					}

					for (StatusEffectInstance effect : living.getStatusEffects()) {
						packets.add(new EntityStatusEffectS2CPacket(entity.getId(), effect, false));
					}
				}

				if (entity.hasPassengers()) {
					packets.add(new EntityPassengersSetS2CPacket(entity));
				}

				entityCount++;
			} catch (Throwable t) {
				// 1匹の写しで全体を捨てない（欠けた分はあとから届く）
				IfutoReplayClient.LOGGER.warn("[ifuto-replay] 实体1匹を写せませんでした ({})",
						entity.getType().getTranslationKey(), t);
			}
		}

		return new Snapshot(packets, chunkCount, entityCount);
	}

	/**
	 * 明るさのデータがある区画の一覧（サーバーがパケットに添える物と同じ）。
	 *
	 * <p>明るさは地形の上下1区画ぶん広く持つ（境目の混ざり具合のため）ので、
	 * 番号は「いちばん下の区画 - 1」から数える。バニラと同じ数え方。
	 */
	private static BitSet lightMask(WorldChunk chunk, LightingProvider lighting, LightType type) {
		ChunkLightProvider<?, ?> provider = lighting.get(type);
		int sections = chunk.getSectionArray().length;
		BitSet mask = new BitSet(sections + 2);

		if (provider == null) {
			return mask;
		}

		ChunkPos pos = chunk.getPos();
		int bottom = chunk.getBottomSectionCoord() - 1;

		for (int i = 0; i < sections + 2; i++) {
			if (provider.getLightSection(ChunkSectionPos.from(pos.x, bottom + i, pos.z)) != null) {
				mask.set(i);
			}
		}

		return mask;
	}

	/** 装備のうち、何か持っている枠だけ */
	private static List<Pair<EquipmentSlot, ItemStack>> equipment(LivingEntity entity) {
		List<Pair<EquipmentSlot, ItemStack>> result = new ArrayList<>();

		for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
			ItemStack stack = entity.getEquippedStack(slot);

			if (!stack.isEmpty()) {
				result.add(new Pair<>(slot, stack.copy()));
			}
		}

		return result;
	}
}
