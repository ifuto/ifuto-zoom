package com.ifuto.kb.measure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ifuto.kb.model.Sample;
import com.ifuto.kb.store.KbStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * 殴りと被弾の検出器。mixin からの出来事と毎 tick の位置履歴をつき合わせて
 * Sample を作る。全構造に上限あり（メモリは増え続けない）。
 */
public final class HitTracker {
	private HitTracker() {
	}

	private static final int MAX_SWINGS = 32;
	private static final int MAX_ENTITIES = 24;
	private static final int POS_CAP = 48;
	private static final int EVT_CAP = 16;
	private static final int HURT_CAP = 64;
	private static final int PAIR_TICKS = 3;
	private static final int STALE_TICKS = 8;
	private static final double SPIKE_MIN = 0.12;
	private static final long WEAK_MS = 550;

	private record Swing(int targetId, long tick, long intervalMs, String weapon, int kb,
			boolean sprint, boolean airborne, boolean falling, boolean selfVehicle, boolean targetVehicle) {
	}

	private record PosTick(long tick, double x, double y, double z) {
	}

	private record VelEvt(long tick, double x, double y, double z) {
	}

	private record DmgSrc(long tick, int attackerId, boolean melee) {
	}

	private record DmgEvt(long tick, double amount) {
	}

	private static final Deque<Swing> swings = new ArrayDeque<>();
	@SuppressWarnings("serial")
	private static final LinkedHashMap<Integer, Deque<PosTick>> posHist = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Deque<PosTick>> e) {
			return size() > MAX_ENTITIES;
		}
	};
	@SuppressWarnings("serial")
	private static final LinkedHashMap<Integer, Deque<VelEvt>> velHist = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Deque<VelEvt>> e) {
			return size() > MAX_ENTITIES;
		}
	};
	private static final Deque<DmgSrc> dmgSrc = new ArrayDeque<>();
	private static final Deque<DmgEvt> dmgEvt = new ArrayDeque<>();
	@SuppressWarnings("serial")
	private static final LinkedHashMap<Integer, Long> hurtTick = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Long> e) {
			return size() > HURT_CAP;
		}
	};
	/** 他人の装備（装備パケットから維持。entityId -> スロット名 -> ネザライトか）。 */
	private static final LinkedHashMap<Integer, Map<String, Boolean>> equipment = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Map<String, Boolean>> e) {
			return size() > 64;
		}
	};

	private static double lastVital = -1;
	private static long lastSwingMs = 0;
	private static boolean damagePacketsSeen = false;

	public static void onJoin() {
		clear();
	}

	public static void onLeave() {
		clear();
	}

	private static void clear() {
		swings.clear();
		posHist.clear();
		velHist.clear();
		dmgSrc.clear();
		dmgEvt.clear();
		hurtTick.clear();
		lastVital = -1;
		lastSwingMs = 0;
		damagePacketsSeen = false;
	}

	// ---- mixin 入口 ----

	public static void onSwing(PlayerEntity player, Entity target) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null || player != client.player) {
			return;
		}
		if (!(target instanceof LivingEntity living)) {
			return;
		}
		long now = System.currentTimeMillis();
		long interval = lastSwingMs == 0 ? 99999 : now - lastSwingMs;
		lastSwingMs = now;
		var stack = player.getMainHandStack();
		swings.addLast(new Swing(target.getId(), client.world.getTime(), interval,
			Conditions.itemId(stack), Conditions.kbLevel(stack),
			player.isSprinting(), !player.isOnGround(), player.getVelocity().y < -0.1,
			player.hasVehicle(), living.hasVehicle()));
		while (swings.size() > MAX_SWINGS) {
			swings.pollFirst();
			KbStore.noteExclusion("swingOverflow");
		}
	}

	public static void onVelocity(EntityVelocityUpdateS2CPacket packet) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		Vec3d v = packet.getVelocity();
		Deque<VelEvt> q = velHist.computeIfAbsent(packet.getEntityId(), k -> new ArrayDeque<>());
		q.addLast(new VelEvt(client.world.getTime(), v.x, v.y, v.z));
		while (q.size() > EVT_CAP) {
			q.pollFirst();
		}
	}

	public static void onDamage(EntityDamageS2CPacket packet) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return;
		}
		try {
			var source = packet.createDamageSource(client.world);
			Entity attacker = source.getAttacker();
			Entity direct = source.getSource();
			boolean melee = attacker != null && (direct == null || direct.getId() == attacker.getId());
			damagePacketsSeen = true;
			dmgSrc.addLast(new DmgSrc(client.world.getTime(), attacker == null ? -1 : attacker.getId(), melee));
			while (dmgSrc.size() > EVT_CAP) {
				dmgSrc.pollFirst();
			}
		} catch (Exception ignored) {
		}
	}

	/** 装備パケットで他人のネザライト数を維持する。差分更新なのでスロット単位で覚える。 */
	@SuppressWarnings("unchecked")
	public static void onEquipment(EntityEquipmentUpdateS2CPacket packet) {
		try {
			Map<String, Boolean> slots = equipment.computeIfAbsent(packet.getEntityId(),
				k -> new LinkedHashMap<>());
			for (Object o : packet.getEquipmentList()) {
				com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack> pair =
					(com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>) o;
				if (pair.getFirst() != null && pair.getFirst().isArmorSlot()) {
					slots.put(pair.getFirst().getName(), isNetheriteArmor(pair.getSecond()));
				}
			}
		} catch (Exception ignored) {
		}
	}

	public static boolean isNetheriteArmor(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		var item = stack.getItem();
		return item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
			|| item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS;
	}

	public static int netheriteFor(int entityId) {
		Map<String, Boolean> slots = equipment.get(entityId);
		if (slots == null) {
			return 0;
		}
		int n = 0;
		for (boolean b : slots.values()) {
			if (b) {
				n++;
			}
		}
		return n;
	}

	/** status 2 = ダメージのけぞり。殴り側のダメージ確認に使う。 */
	public static void onStatus(EntityStatusS2CPacket packet) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || packet.getStatus() != 2) {
			return;
		}
		try {
			Entity entity = packet.getEntity(client.world);
			if (entity != null) {
				hurtTick.put(entity.getId(), client.world.getTime());
			}
		} catch (Exception ignored) {
		}
	}

	// ---- tick 駆動 ----

	public static void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) {
			return;
		}
		long tick = world.getTime();
		recordPositions(world, self, tick);
		watchHealth(self, tick);
		matchReceived(world, self, tick);
		matchDealt(world, self, tick);
		pruneStale(tick);
		KbStore.autosave();
	}

	private static void recordPositions(ClientWorld world, ClientPlayerEntity self, long tick) {
		pushPos(self.getId(), tick, self.getX(), self.getY(), self.getZ());
		for (Swing s : swings) {
			Entity e = world.getEntityById(s.targetId());
			if (e != null) {
				pushPos(e.getId(), tick, e.getX(), e.getY(), e.getZ());
			}
		}
		for (PlayerEntity p : world.getPlayers()) {
			if (p == self) {
				continue;
			}
			if (!posHist.containsKey(p.getId()) && posHist.size() >= MAX_ENTITIES) {
				break;
			}
			pushPos(p.getId(), tick, p.getX(), p.getY(), p.getZ());
		}
	}

	private static void pushPos(int id, long tick, double x, double y, double z) {
		Deque<PosTick> q = posHist.computeIfAbsent(id, k -> new ArrayDeque<>());
		PosTick last = q.peekLast();
		if (last != null && last.tick() == tick) {
			return;
		}
		q.addLast(new PosTick(tick, x, y, z));
		while (q.size() > POS_CAP) {
			q.pollFirst();
		}
	}

	private static void watchHealth(ClientPlayerEntity self, long tick) {
		double vital = self.getHealth() + self.getAbsorptionAmount();
		if (lastVital >= 0 && vital < lastVital - 0.001) {
			dmgEvt.addLast(new DmgEvt(tick, lastVital - vital));
			while (dmgEvt.size() > EVT_CAP) {
				dmgEvt.pollFirst();
			}
		}
		lastVital = vital;
	}

	// ---- 被弾：速度パケット + 体力減 + ダメージ元の三点照合 ----

	private static void matchReceived(ClientWorld world, ClientPlayerEntity self, long tick) {
		Deque<VelEvt> mine = velHist.get(self.getId());
		if (mine == null) {
			return;
		}
		var it = mine.iterator();
		while (it.hasNext()) {
			VelEvt v = it.next();
			if (tick - v.tick() > STALE_TICKS) {
				it.remove();
				DmgEvt d = nearestDmg(v.tick());
				KbStore.noteExclusion(d == null ? "noDamage" : "noSource");
				continue;
			}
			DmgEvt d = nearestDmg(v.tick());
			if (d == null) {
				continue;
			}
			DmgSrc s = nearestSrc(v.tick());
			Entity atk = null;
			boolean heuristic = false;
			if (s != null) {
				if (!s.melee()) {
					it.remove();
					dmgEvt.remove(d);
					dmgSrc.remove(s);
					KbStore.noteExclusion("nonMelee");
					continue;
				}
				atk = world.getEntityById(s.attackerId());
			} else if (!damagePacketsSeen) {
				// ダメージパケットを送らない鯖向けの近接推定
				atk = heuristicAttacker(world, self, v);
				heuristic = true;
			} else {
				continue; // パケット待ち
			}
			if (!(atk instanceof LivingEntity living)) {
				if (tick - v.tick() > PAIR_TICKS) {
					it.remove();
					dmgEvt.remove(d);
					if (s != null) {
						dmgSrc.remove(s);
					}
					KbStore.noteExclusion("attackerGone");
				}
				continue;
			}
			it.remove();
			dmgEvt.remove(d);
			if (s != null) {
				dmgSrc.remove(s);
			}
			buildReceived(world, self, living, v, d, heuristic);
		}
	}

	private static DmgEvt nearestDmg(long tick) {
		DmgEvt best = null;
		for (DmgEvt d : dmgEvt) {
			if (Math.abs(d.tick() - tick) <= PAIR_TICKS
				&& (best == null || Math.abs(d.tick() - tick) < Math.abs(best.tick() - tick))) {
				best = d;
			}
		}
		return best;
	}

	private static DmgSrc nearestSrc(long tick) {
		DmgSrc best = null;
		for (DmgSrc s : dmgSrc) {
			if (Math.abs(s.tick() - tick) <= PAIR_TICKS
				&& (best == null || Math.abs(s.tick() - tick) < Math.abs(best.tick() - tick))) {
				best = s;
			}
		}
		return best;
	}

	/** ノックバック方向の逆側にいる最寄りプレイヤー。1対1の素振り相手ならまず当たる。 */
	private static PlayerEntity heuristicAttacker(ClientWorld world, ClientPlayerEntity self, VelEvt v) {
		double len = Math.hypot(v.x(), v.z());
		if (len < 0.01) {
			return null;
		}
		double dx = -v.x() / len;
		double dz = -v.z() / len;
		PlayerEntity best = null;
		double bestDist = 36.0;
		for (PlayerEntity p : world.getPlayers()) {
			if (p == self) {
				continue;
			}
			double ox = p.getX() - self.getX();
			double oz = p.getZ() - self.getZ();
			double dist2 = ox * ox + oz * oz;
			if (dist2 > bestDist || dist2 < 0.01) {
				continue;
			}
		 double dot = (ox * dx + oz * dz) / Math.sqrt(dist2);
			if (dot > 0.3) {
				best = p;
				bestDist = dist2;
			}
		}
		return best;
	}

	private static void buildReceived(ClientWorld world, ClientPlayerEntity self, LivingEntity atk,
			VelEvt v, DmgEvt d, boolean heuristic) {
		if (self.hasVehicle() || atk.hasVehicle()) {
			KbStore.noteExclusion("vehicle");
			return;
		}
		double[] pre = velBefore(posHist.get(self.getId()), v.tick());
		double pushX = v.x() - pre[0] / 2.0;
		double pushZ = v.z() - pre[2] / 2.0;
		double obsH = Math.hypot(pushX, pushZ);
		double obsV = v.y() - pre[1] / 2.0;
		boolean grounded = self.isOnGround();
		boolean saturated = grounded && v.y() > 0.37;
		Sample s = new Sample();
		s.dir = "received";
		s.tick = v.tick();
		s.pingSelf = Conditions.pingOf(self.getUuid());
		if (atk instanceof PlayerEntity p) {
			s.attacker.kind = "player";
			s.pingOther = Conditions.pingOf(atk.getUuid());
			var stack = p.getMainHandStack();
			s.attacker.weapon = Conditions.itemId(stack);
			s.attacker.kb = Conditions.kbLevel(stack);
			s.attacker.sprint = p.isSprinting();
		} else {
			s.attacker.kind = Conditions.mobId(atk);
			s.attacker.weapon = "natural";
		}
		double atkVy = velBefore(posHist.get(atk.getId()), v.tick())[1];
		s.attacker.airborne = !Conditions.inferGrounded(world, atk, atkVy);
		s.attacker.falling = atkVy < -0.1;
		int neth = Conditions.netheriteCount(self);
		s.victim.kind = "self";
		s.victim.netherite = neth;
		s.victim.resist = neth * 0.1;
		s.victim.airborne = !grounded;
		s.damage = d.amount();
		s.damageConfirmed = true;
		s.obsH = obsH;
		s.obsV = obsV;
		s.vValid = grounded && !saturated && v.y() > 0.03;
		if (!grounded) {
			s.note = "airborne-vertical";
		} else if (saturated) {
			s.note = "vertical-saturated";
		}
		s.confidence = heuristic ? "low" : "high";
		if (heuristic) {
			s.note = s.note.isEmpty() ? "heuristic-attacker" : s.note + "+heuristic-attacker";
		}
		KbStore.route(s);
	}

	// ---- 殴り：スイング + 速度スパイク（あれば正確な速度パケット優先） ----

	private static void matchDealt(ClientWorld world, ClientPlayerEntity self, long tick) {
		int pingTicks = Math.max(0, Conditions.pingOf(self.getUuid()) / 50);
		var it = swings.iterator();
		while (it.hasNext()) {
			Swing sw = it.next();
			long windowEnd = sw.tick() + 3 + pingTicks * 2 + 8;
			Entity e = world.getEntityById(sw.targetId());
			if (!(e instanceof LivingEntity living)) {
				it.remove();
				KbStore.noteExclusion("targetGone");
				continue;
			}
			if (sw.selfVehicle() || living.hasVehicle()) {
				it.remove();
				KbStore.noteExclusion("vehicle");
				continue;
			}
			Deque<PosTick> hist = posHist.get(sw.targetId());
			Deque<PosTick> selfHist = posHist.get(self.getId());
			Spike spike = fromPacket(sw, hist, selfHist, windowEnd);
			if (spike == null) {
				spike = detectSpike(hist, selfHist, sw, tick, windowEnd);
			}
			if (spike == null) {
				if (tick > windowEnd) {
					it.remove();
					KbStore.noteExclusion("noSpike");
				}
				continue;
			}
			if (!spike.dirOk) {
				it.remove();
				KbStore.noteExclusion("wrongDir");
				continue;
			}
			if (!spike.ready && tick <= windowEnd) {
				continue; // あと数 tick 待つ
			}
			it.remove();
			buildDealt(world, self, living, sw, spike, tick);
		}
	}

	private static class Spike {
		double vNewH;
		double vyNew;
		double ux = 1;
		double uz = 0;
		double[] pre = new double[3];
		int fitN;
		boolean fallback;
		boolean exact;
		boolean dirOk = true;
		boolean ready;
	}

	private static VelEvt latestVelAfter(int entityId, long fromTick, long windowEnd) {
		Deque<VelEvt> q = velHist.get(entityId);
		if (q == null) {
			return null;
		}
		for (VelEvt v : q) {
			if (v.tick() >= fromTick && v.tick() <= windowEnd) {
				return v;
			}
		}
		return null;
	}

	private static Spike fromPacket(Swing sw, Deque<PosTick> hist, Deque<PosTick> selfHist, long windowEnd) {
		VelEvt exact = latestVelAfter(sw.targetId(), sw.tick(), windowEnd);
		if (exact == null) {
			return null;
		}
		Spike spike = new Spike();
		spike.exact = true;
		spike.ready = true;
		spike.vNewH = Math.hypot(exact.x(), exact.z());
		spike.vyNew = exact.y();
		spike.pre = velBefore(hist, exact.tick());
		double len = Math.hypot(exact.x(), exact.z());
		if (len > 0.001) {
			spike.ux = exact.x() / len;
			spike.uz = exact.z() / len;
		}
		double[] exp = expectedDir(hist, selfHist, sw.tick());
		if (exp != null) {
			double pushX = exact.x() - spike.pre[0] / 2.0;
			double pushZ = exact.z() - spike.pre[2] / 2.0;
			double pushLen = Math.hypot(pushX, pushZ);
			spike.dirOk = pushLen < 0.01 || (pushX * exp[0] + pushZ * exp[1]) / pushLen > 0.2;
		}
		return spike;
	}

	private static Spike detectSpike(Deque<PosTick> hist, Deque<PosTick> selfHist, Swing sw, long nowTick, long windowEnd) {
		if (hist == null || hist.size() < 4) {
			return null;
		}
		ArrayList<PosTick> pts = new ArrayList<>(hist);
		// スイング前の速さ
		double baseSpeed = 0;
		int baseIdx = -1;
		for (int i = 0; i < pts.size() - 1; i++) {
			if (pts.get(i + 1).tick() <= sw.tick()) {
				baseIdx = i;
			}
		}
		if (baseIdx >= 0) {
			baseSpeed = pairSpeed(pts.get(baseIdx), pts.get(baseIdx + 1));
		}
		// スパイク探索
		int spikeIdx = -1;
		for (int i = 0; i < pts.size() - 1; i++) {
			PosTick b = pts.get(i + 1);
			if (b.tick() <= sw.tick() || b.tick() > windowEnd || b.tick() > nowTick) {
				continue;
			}
			if (pairSpeed(pts.get(i), b) - baseSpeed > SPIKE_MIN) {
				spikeIdx = i;
				break;
			}
		}
		if (spikeIdx < 0) {
			return null;
		}
		Spike spike = new Spike();
		PosTick a = pts.get(spikeIdx);
		PosTick b = pts.get(spikeIdx + 1);
		double dt = Math.max(1, b.tick() - a.tick());
		double dx = (b.x() - a.x()) / dt;
		double dz = (b.z() - a.z()) / dt;
		double len = Math.hypot(dx, dz);
		if (len > 0.001) {
			spike.ux = dx / len;
			spike.uz = dz / len;
		}
		spike.pre = velBefore(hist, b.tick());
		double[] exp = expectedDir(hist, selfHist, sw.tick());
		if (exp != null) {
			spike.dirOk = len < 0.01 || (dx * exp[0] + dz * exp[1]) / len > 0.2;
		}
		// スパイク後の減衰にフィット
		ArrayList<Double> speeds = new ArrayList<>();
		double firstVy = 0;
		boolean firstVySet = false;
		for (int i = spikeIdx; i < pts.size() - 1 && speeds.size() < 6; i++) {
			PosTick p0 = pts.get(i);
			PosTick p1 = pts.get(i + 1);
			if (p1.tick() > nowTick) {
				break;
			}
			speeds.add(pairSpeed(p0, p1));
			if (!firstVySet) {
				double d = Math.max(1, p1.tick() - p0.tick());
				firstVy = (p1.y() - p0.y()) / d;
				firstVySet = true;
			}
		}
		var fit = SpikeFit.fit(speeds);
		spike.vNewH = fit.v0();
		spike.fitN = fit.n();
		spike.fallback = fit.fallback();
		spike.vyNew = firstVy + 0.08; // 1 tick 分の重力を戻す
		spike.ready = speeds.size() >= 3;
		if (!spike.ready) {
			// 窓切れ時は最大値で受け入れる（low 扱い）
			spike.vNewH = fit.v0();
			spike.fallback = true;
		}
		return spike;
	}

	private static double pairSpeed(PosTick a, PosTick b) {
		double dt = Math.max(1, b.tick() - a.tick());
		return Math.hypot((b.x() - a.x()) / dt, (b.z() - a.z()) / dt);
	}

	/** スイング時点の「自分→相手」水平単位ベクトル。取れなければ null。 */
	private static double[] expectedDir(Deque<PosTick> hist, Deque<PosTick> selfHist, long tick) {
		double[] t = posAt(hist, tick);
		double[] s = posAt(selfHist, tick);
		if (t == null || s == null) {
			return null;
		}
		double dx = t[0] - s[0];
		double dz = t[2] - s[2];
		double len = Math.hypot(dx, dz);
		if (len < 0.01) {
			return null;
		}
		return new double[] { dx / len, dz / len };
	}

	private static double[] posAt(Deque<PosTick> hist, long tick) {
		if (hist == null) {
			return null;
		}
		PosTick best = null;
		for (PosTick p : hist) {
			if (p.tick() <= tick) {
				best = p;
			} else {
				break;
			}
		}
		return best == null ? null : new double[] { best.x(), best.y(), best.z() };
	}

	private static double[] velBefore(Deque<PosTick> hist, long eventTick) {
		if (hist == null || hist.size() < 2) {
			return new double[3];
		}
		PosTick before = null;
		PosTick beforePrev = null;
		for (PosTick p : hist) {
			if (p.tick() >= eventTick) {
				break;
			}
			beforePrev = before;
			before = p;
		}
		if (before == null || beforePrev == null) {
			return new double[3];
		}
		long dt = before.tick() - beforePrev.tick();
		if (dt <= 0) {
			return new double[3];
		}
		return new double[] {
			(before.x() - beforePrev.x()) / dt,
			(before.y() - beforePrev.y()) / dt,
			(before.z() - beforePrev.z()) / dt
		};
	}

	private static void buildDealt(ClientWorld world, ClientPlayerEntity self, LivingEntity living,
			Swing sw, Spike spike, long nowTick) {
		double pushX = spike.vNewH * spike.ux - spike.pre[0] / 2.0;
		double pushZ = spike.vNewH * spike.uz - spike.pre[2] / 2.0;
		double obsH = Math.max(0, Math.hypot(pushX, pushZ));
		double obsV = spike.vyNew - spike.pre[1] / 2.0;
		boolean grounded = Conditions.inferGrounded(world, living, spike.pre[1]);
		boolean saturated = grounded && spike.vyNew > 0.37;
		long hurt = hurtTick.getOrDefault(sw.targetId(), -999L);
		boolean confirmed = hurt >= sw.tick() - 1 && hurt <= nowTick + 1;
		boolean weak = sw.intervalMs() < WEAK_MS;
		Sample s = new Sample();
		s.dir = "dealt";
		s.tick = sw.tick();
		s.pingSelf = Conditions.pingOf(self.getUuid());
		s.pingOther = living instanceof PlayerEntity ? Conditions.pingOf(living.getUuid()) : -1;
		s.attacker.kind = "player";
		s.attacker.weapon = sw.weapon();
		s.attacker.kb = sw.kb();
		s.attacker.sprint = sw.sprint();
		s.attacker.airborne = sw.airborne();
		s.attacker.falling = sw.falling();
		s.attacker.swingIntervalMs = sw.intervalMs();
		s.attacker.weakSuspect = weak;
		int neth = Conditions.netheriteCount(living);
		s.victim.kind = living instanceof PlayerEntity ? "player" : Conditions.mobId(living);
		s.victim.netherite = neth;
		s.victim.resist = neth * 0.1;
		s.victim.airborne = !grounded;
		s.damageConfirmed = confirmed;
		s.obsH = obsH;
		s.obsV = obsV;
		s.vValid = grounded && !saturated && spike.vyNew > 0.03;
		if (!grounded) {
			s.note = "airborne-vertical";
		} else if (saturated) {
			s.note = "vertical-saturated";
		}
		int score = 3;
		if (!confirmed) {
			score--;
		}
		if (weak) {
			score--;
		}
		if (!spike.exact) {
			score--;
		}
		if (spike.fallback) {
			score--;
		}
		s.confidence = score >= 3 ? "high" : score == 2 ? "med" : "low";
		KbStore.route(s);
	}

	private static void pruneStale(long tick) {
		dmgEvt.removeIf(d -> tick - d.tick() > STALE_TICKS);
		dmgSrc.removeIf(s -> tick - s.tick() > STALE_TICKS);
		for (Deque<VelEvt> q : velHist.values()) {
			q.removeIf(v -> tick - v.tick() > STALE_TICKS + 4);
		}
	}
}
