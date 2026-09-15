package com.ifuto.replay.recording;

import com.ifuto.replay.IfutoReplayClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * **クライアントの内側でだけ** 起きた出来事を記録し、再生時に同じ物をもう一度起こす。
 *
 * <p>サーバーから届く物はすべてパケットとして残る。けれど「クライアントが自分で湧かせた
 * パーティクル」はパケットにならないので、そのままでは再生したときに出てこない
 * （ブロックを崩しているときの破片、足あと、Mod が自分で出している演出など）。
 *
 * <p>**パケットの処理中に湧いた物は記録しない。** そちらはサーバーから届いた物として
 * すでにパケットに残っているので、記録すると二重に出てしまう。
 */
public final class LocalEvents {
	/** パーティクル */
	public static final int TYPE_PARTICLE = 0;

	private static volatile boolean fromPacket;

	private LocalEvents() {
	}

	/** いまパケットを処理しているか（このあいだに湧いた物は記録しない） */
	public static void setFromPacket(boolean value) {
		fromPacket = value;
	}

	public static boolean isFromPacket() {
		return fromPacket;
	}

	// --- 記録 ---

	/** パーティクル1件を記録用のバイト列にする（種類が残せなければ null） */
	public static @Nullable byte[] encodeParticle(ParticleEffect effect, double x, double y, double z,
												  double velocityX, double velocityY, double velocityZ) {
		Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
		NbtElement parameters = encodeParameters(effect);

		if (id == null || parameters == null) {
			return null;
		}

		NbtCompound nbt = new NbtCompound();
		nbt.putString("id", id.toString());
		nbt.put("p", parameters);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			NbtIo.writeCompound(nbt, out);
			out.writeFloat((float) x);
			out.writeFloat((float) y);
			out.writeFloat((float) z);
			out.writeFloat((float) velocityX);
			out.writeFloat((float) velocityY);
			out.writeFloat((float) velocityZ);
		} catch (IOException e) {
			return null;
		}

		return bytes.toByteArray();
	}

	@SuppressWarnings("unchecked")
	private static @Nullable NbtElement encodeParameters(ParticleEffect effect) {
		try {
			ParticleType<ParticleEffect> type = (ParticleType<ParticleEffect>) effect.getType();
			return type.getCodec().codec().encodeStart(NbtOps.INSTANCE, effect).result().orElse(null);
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.debug("[ifuto-replay] パーティクルの種類を残せませんでした", t);
			return null;
		}
	}

	// --- 再生 ---

	/** 記録したパーティクルを、同じ場所へ同じように出す */
	public static void playParticle(MinecraftClient client, byte[] data) {
		ClientWorld world = client.world;

		if (world == null) {
			return;
		}

		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			NbtCompound nbt = NbtIo.readCompound(in);
			float x = in.readFloat();
			float y = in.readFloat();
			float z = in.readFloat();
			float velocityX = in.readFloat();
			float velocityY = in.readFloat();
			float velocityZ = in.readFloat();
			Identifier id = Identifier.tryParse(nbt.getString("id"));
			NbtElement parameters = nbt.get("p");

			if (id == null || parameters == null) {
				return;
			}

			ParticleType<?> type = Registries.PARTICLE_TYPE.getOrEmpty(id).orElse(null);
			ParticleEffect effect = type == null ? null : decodeParameters(type, parameters);

			if (effect != null) {
				world.addParticle(effect, x, y, z, velocityX, velocityY, velocityZ);
			}
		} catch (IOException e) {
			// 1件読めなくても再生は続ける（パーティクルは飾りなので）
		}
	}

	@SuppressWarnings("unchecked")
	private static @Nullable ParticleEffect decodeParameters(ParticleType<?> type, NbtElement parameters) {
		try {
			ParticleType<ParticleEffect> casted = (ParticleType<ParticleEffect>) type;
			return casted.getCodec().codec().parse(NbtOps.INSTANCE, parameters).result().orElse(null);
		} catch (Throwable t) {
			return null;
		}
	}
}
