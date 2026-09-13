package com.ifuto.replay.recording;

import net.minecraft.client.network.ClientRegistries;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.SerializableRegistries;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.DynamicOps;

/**
 * 「動的レジストリ」の写し。
 *
 * <p>パケットを復元するには、録ったときと同じレジストリ（バイオーム・次元・エンチャントなど）
 * が必要。あとから別の世界に居る状態で再生しても崩れないように、録り始めの時点の物を
 * NBT にしてファイルの先頭に置いておく。
 *
 * <p>書き方も読み方もバニラの物をそのまま借りている（{@link SerializableRegistries} と
 * {@link ClientRegistries}）。サーバー→クライアントで使われている経路と同じなので、
 * バージョンが上がっても追随できる。
 */
public final class RegistrySnapshot {
	private static final String KEY_ID = "id";
	private static final String KEY_DATA = "data";

	private RegistrySnapshot() {
	}

	/**
	 * いまのレジストリを「サーバーから送られてくるのと同じ形」に直す。
	 *
	 * <p>少し重い（数百ミリ秒）ので、録画を始めたときの 1 回だけ呼ぶ。
	 */
	public static NbtCompound capture(DynamicRegistryManager registries) {
		NbtCompound root = new NbtCompound();
		DynamicOps<NbtElement> ops = registries.getOps(NbtOps.INSTANCE);

		SerializableRegistries.forEachSyncedRegistry(ops, registries, Set.of(), (registryKey, entries) -> {
			NbtList list = new NbtList();

			for (SerializableRegistries.SerializedRegistryEntry entry : entries) {
				NbtCompound child = new NbtCompound();
				child.putString(KEY_ID, entry.id().toString());

				Optional<NbtElement> data = entry.data();

				if (data.isPresent()) {
					child.put(KEY_DATA, data.get());
				}

				list.add(child);
			}

			root.put(registryKey.getValue().toString(), list);
		});

		return root;
	}

	/**
	 * 保存しておいた NBT からレジストリを組み直す。
	 *
	 * <p>バニラがサーバーから受け取ったデータを組み立てるときとまったく同じ経路
	 * （{@link ClientRegistries} → {@code RegistryLoader}）を通す。
	 */
	public static DynamicRegistryManager.Immutable restore(NbtCompound root, ResourceFactory resourceFactory) {
		ClientRegistries clientRegistries = new ClientRegistries();

		for (String key : root.getKeys()) {
			if (root.get(key) == null || !(root.get(key) instanceof NbtList list)) {
				continue;
			}

			RegistryKey<? extends Registry<?>> registryKey = RegistryKey.ofRegistry(Identifier.of(key));
			List<SerializableRegistries.SerializedRegistryEntry> entries = new ArrayList<>(list.size());

			for (int i = 0; i < list.size(); i++) {
				Optional<NbtCompound> entry = list.getCompound(i);

				if (entry.isEmpty()) {
					continue;
				}

				NbtCompound child = entry.get();
				Optional<String> id = child.getString(KEY_ID);

				if (id.isEmpty()) {
					continue;
				}

				NbtElement data = child.get(KEY_DATA);
				entries.add(new SerializableRegistries.SerializedRegistryEntry(
						Identifier.of(id.get()),
						Optional.ofNullable(data)
				));
			}

			clientRegistries.putDynamicRegistry(registryKey, entries);
		}

		return clientRegistries.createRegistryManager(resourceFactory, DynamicRegistryManager.of(Registries.REGISTRIES),
				false);
	}
}
