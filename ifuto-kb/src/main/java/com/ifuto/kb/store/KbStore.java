package com.ifuto.kb.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.kb.IfutoKbClient;
import com.ifuto.kb.model.BaselineTable;
import com.ifuto.kb.model.Sample;
import com.ifuto.kb.model.ServerRecord;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/** config/ifuto-kb 配下の保存と振り分け。メモリ常駐は最大 8 鯖（LRU・追い出し時保存）。 */
public final class KbStore {
	private KbStore() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_LOADED = 8;
	private static final long SAVE_MS = 5000;

	private static Path dir;
	private static Path serversDir;
	private static BaselineTable baselines = new BaselineTable();
	@SuppressWarnings("serial")
	private static final LinkedHashMap<String, ServerRecord> records = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ServerRecord> e) {
			if (size() > MAX_LOADED) {
				saveRecord(e.getValue());
				return true;
			}
			return false;
		}
	};
	private static String sessionKey = null;
	private static boolean dirty = false;
	private static long lastSaveMs = 0;
	private static String modVersion = "?";

	public static Gson gson() {
		return GSON;
	}

	public static void init(Path configDir) {
		dir = configDir;
		serversDir = dir.resolve("servers");
		try {
			Files.createDirectories(serversDir);
		} catch (Exception ignored) {
		}
		try {
			modVersion = FabricLoader.getInstance().getModContainer(IfutoKbClient.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		} catch (Exception ignored) {
		}
		loadBaselines();
	}

	/** 鯖キー。シングルは null（= 基準表行き）。 */
	public static String keyOf(MinecraftClient client) {
		try {
			if (client.isInSingleplayer()) {
				return null;
			}
			var entry = client.getCurrentServerEntry();
			if (entry == null || entry.address == null) {
				return "unknown";
			}
			return entry.address.trim().toLowerCase();
		} catch (Exception ignored) {
			return "unknown";
		}
	}

	public static void onJoin(MinecraftClient client) {
		sessionKey = keyOf(client);
		if (sessionKey != null) {
			recordFor(sessionKey);
		}
	}

	public static void onLeave() {
		saveNow();
		sessionKey = null;
	}

	public static BaselineTable baselines() {
		return baselines;
	}

	public static ServerRecord recordFor(String address) {
		ServerRecord rec = records.get(address);
		if (rec == null) {
			rec = loadRecord(address);
			records.put(address, rec);
		}
		return rec;
	}

	public static List<ServerRecord> all() {
		try (var stream = Files.list(serversDir)) {
			stream.filter(p -> p.toString().endsWith(".json")).limit(64).forEach(p -> {
				try {
					ServerRecord rec = GSON.fromJson(Files.readString(p), ServerRecord.class);
					if (rec != null && rec.address != null && !records.containsKey(rec.address)) {
						if (rec.samples == null) {
							rec.samples = new ArrayList<>();
						}
						if (rec.excluded == null) {
							rec.excluded = new LinkedHashMap<>();
						}
						records.put(rec.address, rec);
					}
				} catch (Exception ignored) {
				}
			});
		} catch (Exception ignored) {
		}
		ArrayList<ServerRecord> out = new ArrayList<>(records.values());
		out.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
		return out;
	}

	/** サンプルに期待値と係数を付けて振り分ける。期待値は取り込み前の表で計算する。 */
	public static void route(Sample sample) {
		BaselineTable.Expected exp = baselines.expectedFor(sample);
		double keep = Math.max(0, 1.0 - sample.victim.resist);
		sample.baseH = exp.h();
		sample.baseV = exp.v();
		sample.expH = exp.h() >= 0 ? exp.h() * keep : -1;
		sample.expV = exp.v() >= 0 ? exp.v() * keep : -1;
		sample.seedUsed = exp.seed();
		sample.ratioH = sample.expH > 0.01 ? sample.obsH / sample.expH : -1;
		sample.ratioV = (sample.vValid && sample.expV > 0.01) ? sample.obsV / sample.expV : -1;
		long now = System.currentTimeMillis();
		if (sessionKey == null) {
			baselines.ingest(sample);
		} else {
			ServerRecord rec = recordFor(sessionKey);
			rec.address = sessionKey;
			rec.modVersion = modVersion;
			rec.ingest(sample, now);
		}
		dirty = true;
	}

	public static void noteExclusion(String reason) {
		if (sessionKey == null) {
			baselines.excluded.merge(reason, 1L, Long::sum);
		} else {
			recordFor(sessionKey).excluded.merge(reason, 1L, Long::sum);
		}
		dirty = true;
	}

	public static void autosave() {
		long now = System.currentTimeMillis();
		if (dirty && now - lastSaveMs > SAVE_MS) {
			saveNow();
		}
	}

	public static void saveNow() {
		try {
			if (dir == null) {
				return;
			}
			Files.writeString(dir.resolve("baselines.json"), GSON.toJson(baselines));
			for (ServerRecord rec : new ArrayList<>(records.values())) {
				saveRecord(rec);
			}
			dirty = false;
			lastSaveMs = System.currentTimeMillis();
		} catch (Exception ignored) {
		}
	}

	public static boolean delete(String address) {
		records.remove(address);
		try {
			return Files.deleteIfExists(fileFor(address));
		} catch (Exception ignored) {
			return false;
		}
	}

	private static void loadBaselines() {
		try {
			Path file = dir.resolve("baselines.json");
			if (Files.isRegularFile(file)) {
				BaselineTable loaded = GSON.fromJson(Files.readString(file), BaselineTable.class);
				if (loaded != null && loaded.cells != null) {
					baselines = loaded;
					if (baselines.excluded == null) {
						baselines.excluded = new LinkedHashMap<>();
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	private static ServerRecord loadRecord(String address) {
		try {
			Path file = fileFor(address);
			if (Files.isRegularFile(file)) {
				ServerRecord rec = GSON.fromJson(Files.readString(file), ServerRecord.class);
				if (rec != null) {
					rec.address = address;
					if (rec.samples == null) {
						rec.samples = new ArrayList<>();
					}
					if (rec.excluded == null) {
						rec.excluded = new LinkedHashMap<>();
					}
					return rec;
				}
			}
		} catch (Exception ignored) {
		}
		ServerRecord rec = new ServerRecord();
		rec.address = address;
		rec.modVersion = modVersion;
		return rec;
	}

	private static void saveRecord(ServerRecord rec) {
		try {
			if (serversDir == null || rec.address == null) {
				return;
			}
			Files.writeString(fileFor(rec.address), rec.toJson(GSON));
		} catch (Exception ignored) {
		}
	}

	private static Path fileFor(String address) {
		String safe = address.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (safe.length() > 60) {
			safe = safe.substring(0, 60);
		}
		return serversDir.resolve(safe + "_" + Math.abs(address.hashCode() % 10000) + ".json");
	}
}
