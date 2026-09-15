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
import java.util.List;

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

	/**
	 * まとめたパーティクル（中身は「件数 + (長さ + パーティクル1件ぶん) の繰り返し」）。
	 *
	 * <p>件数が多いので1件ずつ枠を取ると書き込みが詰まる。まとめて1枠で書く。
	 * 古い読み手はこの種類を知らないので読み飛ばす（パーティクルが出ないだけで壊れない）。
	 */
	public static final int TYPE_PARTICLE_BATCH = 1;

	/** 壊れたファイルを読んで暴走しないための上限 */
	private static final int MAX_BATCH_ENTRIES = 100_000;
	private static final int MAX_BATCH_ENTRY_BYTES = 1 << 20;

	/**
	 * いまパケットを処理しているスレッドか（このあいだに湧いた物は記録しない）。
	 *
	 * <p>スレッドごとに持つ。マルチプレイではパケットの適用（Netty 側）と描画
	 * （クライアント側）が別のスレッドなので、1個しかないと「描画で自然に湧いた物」まで
	 * 捨ててしまう。シングルプレイでは同じスレッドなので従来どおり動く。
	 *
	 * <p>なおマルチプレイでは、パケット由来の処理がクライアント側で後から走るぶんは
	 * この印では拾えない（別スレッドで印が消えたあとに湧くため）。サーバー発のパーティクルが
	 * 二重に出る可能性として残っている。将来的にはクライアントのタスク実行を包んで
	 * 印を付けるのが正しいが、動作確認ができる環境でやる（TODO）。
	 */
	private static final ThreadLocal<Boolean> FROM_PACKET = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private LocalEvents() {
	}

	/** いまパケットを処理しているか（このあいだに湧いた物は記録しない） */
	public static void setFromPacket(boolean value) {
		FROM_PACKET.set(value);
	}

	public static boolean isFromPacket() {
		return FROM_PACKET.get();
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

	/** パーティクルを何件かまとめて、1枠ぶんのバイト列にする（空なら null） */
	public static @Nullable byte[] encodeBatch(List<byte[]> entries) {
		if (entries == null || entries.isEmpty()) {
			return null;
		}

		int valid = 0;

		for (byte[] entry : entries) {
			if (entry != null && entry.length > 0) {
				valid++;
			}
		}

		if (valid <= 0) {
			return null;
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * valid);

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(valid);

			for (byte[] entry : entries) {
				if (entry == null || entry.length == 0) {
					continue;
				}

				out.writeInt(entry.length);
				out.write(entry);
			}
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
			String idText = nbt.getString("id").orElse(null);
			Identifier id = idText == null ? null : Identifier.tryParse(idText);
			NbtElement parameters = nbt.get("p");

			if (id == null || parameters == null) {
				return;
			}

			ParticleType<?> type = Registries.PARTICLE_TYPE.getOptionalValue(id).orElse(null);
			ParticleEffect effect = type == null ? null : decodeParameters(type, parameters);

			if (effect != null) {
				// 記録したのと同じ入口（ParticleManager）から出す
				client.particleManager.addParticle(effect, x, y, z, velocityX, velocityY, velocityZ);
			}
		} catch (IOException e) {
			// 1件読めなくても再生は続ける（パーティクルは飾りなので）
		}
	}

	/** まとめたパーティクルを、同じ場所へ同じように出す（壊れていれば分かるぶんだけ） */
	public static void playBatch(MinecraftClient client, byte[] data) {
		if (client == null || client.world == null || data == null || data.length < 4) {
			return;
		}

		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			int count = in.readInt();

			if (count < 0 || count > MAX_BATCH_ENTRIES) {
				return;
			}

			for (int i = 0; i < count; i++) {
				int length = in.readInt();

				if (length < 0 || length > MAX_BATCH_ENTRY_BYTES || length > in.available()) {
					return;
				}

				byte[] entry = new byte[length];
				in.readFully(entry);
				playParticle(client, entry);
			}
		} catch (IOException e) {
			// 1枠読めなくても再生は続ける（パーティクルは飾りなので）
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
