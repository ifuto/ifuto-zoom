package com.ifuto.armorhud.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ifuto.armorhud.IfutoArmorHudClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/ifuto-armor-hud.json に保存する設定。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ArmorHudConfig instance;

	/** HUD 自体の表示/非表示（V キーでも切り替わる） */
	public boolean showHud = true;

	/** ホットバーの左右どちらに置くか */
	public HudPosition position = HudPosition.HOTBAR_LEFT;

	/** アイコンの並べ方 */
	public HudLayout layout = HudLayout.VERTICAL;

	/** ホットバーからどれくらい離すか（既定はアイテムマス1つ分の 18px） */
	public int hotbarGap = 18;

	/** 装備枠と枠の間の隙間（px） */
	public int slotGap = 2;

	/** スロットの背景（アイテム枠 or ゴーストアイコンだけ） */
	public SlotBackground background = SlotBackground.FRAME;

	/** 装備していないスロットの扱い */
	public EmptySlotMode emptyMode = EmptySlotMode.KEEP;

	/** 耐久表示（枠の中）。既定は中ゲージのみ */
	public InfoMode inside = InfoMode.GAUGE;

	/** 耐久表示（枠の外）。横向きなら枠の上、縦向きなら指定側に横書き or 縦ゲージ */
	public InfoMode outside = InfoMode.NONE;

	/** 縦向きのとき枠の外を左右どちらに出すか */
	public OutsideSide outsideSide = OutsideSide.LEFT;

	/** 耐久がピンチになったら枠を赤く点滅させる */
	public boolean warnBlink = false;

	/** ピンチと見なす残り耐久（％） */
	public int warnPercent = 15;

	/** 文字とゲージの後ろに薄い影を敷いて見やすくする */
	public boolean dynamicContrast = false;

	/** HUD 全体の大きさ（%） */
	public int hudScale = 100;

	/** どういうとき HUD を出すか（常時 / 傷あり / ピンチ） */
	public ShowCondition showCondition = ShowCondition.ALWAYS;

	/** 装備が壊れた瞬間に音とチャットで知らせる */
	public boolean breakAlert = true;

	/** 最終調整用のオフセット（+が右/下） */
	public int offsetX = 0;
	public int offsetY = 0;

	/** Discord Rich Presence 用のアプリケーション Client ID（空欄なら連携オフ） */
	public String discordClientId = "";

	/** 参加時の案内チャットを出した回数（5回まで出す） */
	public int joinCount = 0;

	public static ArmorHudConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	public static Path getPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(IfutoArmorHudClient.MOD_ID + ".json");
	}

	public static ArmorHudConfig load() {
		Path path = getPath();
		ArmorHudConfig config = new ArmorHudConfig();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ArmorHudConfig loaded = GSON.fromJson(reader, ArmorHudConfig.class);

				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				IfutoArmorHudClient.LOGGER.warn("[ifuto-armor-hud] {} が読めなかったので既定値で続行", path, e);
			}
		}

		config.validate();
		instance = config;
		config.save();
		return config;
	}

	public void save() {
		Path path = getPath();
		this.validate();

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			IfutoArmorHudClient.LOGGER.warn("[ifuto-armor-hud] {} を書き込めなかった", path, e);
		}
	}

	public void resetToDefaults() {
		ArmorHudConfig defaults = new ArmorHudConfig();
		this.showHud = defaults.showHud;
		this.position = defaults.position;
		this.layout = defaults.layout;
		this.hotbarGap = defaults.hotbarGap;
		this.slotGap = defaults.slotGap;
		this.background = defaults.background;
		this.emptyMode = defaults.emptyMode;
		this.inside = defaults.inside;
		this.outside = defaults.outside;
		this.outsideSide = defaults.outsideSide;
		this.warnBlink = defaults.warnBlink;
		this.warnPercent = defaults.warnPercent;
		this.dynamicContrast = defaults.dynamicContrast;
		this.hudScale = defaults.hudScale;
		this.showCondition = defaults.showCondition;
		this.breakAlert = defaults.breakAlert;
		this.offsetX = defaults.offsetX;
		this.offsetY = defaults.offsetY;
		// discordClientId と joinCount はリセット対象外（ユーザー固有の値なので残す）
	}

	/** 手書き編集や古いファイルで壊れていても落ちないように丸める */
	public void validate() {
		if (this.position == null) {
			this.position = HudPosition.HOTBAR_LEFT;
		}

		if (this.layout == null) {
			this.layout = HudLayout.VERTICAL;
		}

		if (this.background == null) {
			this.background = SlotBackground.FRAME;
		}

		if (this.emptyMode == null) {
			this.emptyMode = EmptySlotMode.KEEP;
		}

		if (this.inside == null) {
			this.inside = InfoMode.GAUGE;
		}

		if (this.outside == null) {
			this.outside = InfoMode.NONE;
		}

		if (this.outsideSide == null) {
			this.outsideSide = OutsideSide.LEFT;
		}

		if (this.showCondition == null) {
			this.showCondition = ShowCondition.ALWAYS;
		}

		if (this.discordClientId == null) {
			this.discordClientId = "";
		}

		this.hotbarGap = clamp(this.hotbarGap, 0, 64);
		this.slotGap = clamp(this.slotGap, 0, 16);
		this.warnPercent = clamp(this.warnPercent, 1, 50);
		this.hudScale = clamp(this.hudScale, 25, 200);
		this.offsetX = clamp(this.offsetX, -500, 500);
		this.offsetY = clamp(this.offsetY, -500, 500);

		if (this.joinCount < 0) {
			this.joinCount = 0;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.min(max, Math.max(min, value));
	}
}
