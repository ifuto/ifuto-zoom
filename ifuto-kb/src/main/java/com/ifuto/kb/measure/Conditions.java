package com.ifuto.kb.measure;

import java.util.UUID;

import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** 1ヒット分の条件スナップショットを作る純粋寄りの読み取り集。失敗したら安全側の既定値。 */
public final class Conditions {
	private Conditions() {
	}

	/** 手持ちのノックバックエンチャントレベル。取れなければ 0。 */
	@SuppressWarnings("unchecked")
	public static int kbLevel(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		try {
			var comp = EnchantmentHelper.getEnchantments(stack);
			for (Object o : comp.getEnchantments()) {
				if (!(o instanceof RegistryEntry<?> re)) {
					continue;
				}
				String id = re.getKey().map(k -> k.getValue().toString()).orElse("");
				if (id.equals("minecraft:knockback")) {
					return Math.max(0, comp.getLevel((RegistryEntry<Enchantment>) o));
				}
			}
		} catch (Exception ignored) {
		}
		return 0;
	}

	/** ネザライト防具の数。KB耐性 = 個数 x 0.1（他プレイヤーの属性は見えないので全員この式で統一）。 */
	public static int netheriteCount(LivingEntity entity) {
		int n = 0;
		try {
			for (ItemStack stack : entity.getArmorItems()) {
				Item item = stack.getItem();
				if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
					|| item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) {
					n++;
				}
			}
		} catch (Exception ignored) {
		}
		return n;
	}

	/** tab list の ping。取れなければ -1。 */
	public static int pingOf(UUID uuid) {
		try {
			var handler = MinecraftClient.getInstance().getNetworkHandler();
			if (handler == null) {
				return -1;
			}
			var entry = handler.getPlayerListEntry(uuid);
			return entry == null ? -1 : entry.getLatency();
		} catch (Exception ignored) {
			return -1;
		}
	}

	public static String itemId(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "none";
		}
		try {
			return Registries.ITEM.getId(stack.getItem()).toString();
		} catch (Exception ignored) {
			return "?";
		}
	}

	public static String mobId(Entity entity) {
		try {
			return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
		} catch (Exception ignored) {
			return "?";
		}
	}

	/**
	 * 他エンティティの設置判定。onGround は他人の分が同期されないので、
	 * 足元の当たり判定 + 縦速度で推測する。
	 */
	public static boolean inferGrounded(net.minecraft.client.world.ClientWorld world, LivingEntity entity, double vy) {
		if (Math.abs(vy) > 0.08) {
			return false;
		}
		try {
			BlockPos pos = new BlockPos(MathHelper.floor(entity.getX()),
				MathHelper.floor(entity.getY() - 0.01), MathHelper.floor(entity.getZ()));
			return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
		} catch (Exception ignored) {
			return false;
		}
	}
}
