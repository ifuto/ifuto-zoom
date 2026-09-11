package com.ifuto.armorhud.hud;

import com.ifuto.armorhud.config.ArmorHudConfig;
import com.ifuto.armorhud.config.EmptySlotMode;
import com.ifuto.armorhud.config.HudLayout;
import com.ifuto.armorhud.config.HudPosition;
import com.ifuto.armorhud.config.InfoMode;
import com.ifuto.armorhud.config.OutsideSide;
import com.ifuto.armorhud.config.ShowCondition;
import com.ifuto.armorhud.config.SlotBackground;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 装備中の防具をホットバーの脇に描く。上から頭→胴→脚→足の順。
 * 耐久の色はバニラのアイテムバーよろしく 緑→黄→赤 の連続グラデーション。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudRenderer implements HudElement {
	// 頭→胴→脚→足の順（壊れ通知の走査でも使うので公開）
	public static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	// バニラのホットバーテクスチャから1スロット分(20x20)を切り抜いて使う（リソパで上書き可）
	private static final Identifier HOTBAR_TEXTURE = Identifier.ofVanilla("textures/gui/sprites/hud/hotbar.png");
	private static final int CELL = 20;      // セルの外寸（ボーダー2 + 内16）
	private static final int CELL_U = 21;    // 切り抜き範囲 (u, v)（中ほどのセルで両端の影を避ける）
	private static final int CELL_V = 1;
	private static final int HOTBAR_TEX_W = 182;
	private static final int HOTBAR_TEX_H = 22;
	private static final int ICON_OFF = 2;   // セル内のアイテム位置

	// オフハンドのウィジェットは角丸の箱 + 透明の空白。間隔があるときはこちらを使う
	private static final Identifier OFFHAND_TEXTURE = Identifier.ofVanilla("textures/gui/sprites/hud/hotbar_offhand_left.png");
	private static final int OFFHAND_CELL = 22;  // 枠の実寸
	private static final int OFFHAND_U = 0;
	private static final int OFFHAND_V = 1;
	private static final int OFFHAND_TEX_W = 29;
	private static final int OFFHAND_TEX_H = 24;
	private static final int OFFHAND_ICON_OFF = 3;

	// バニラの空き装備スロットに出るミニアイコンと同じやつ
	private static final Identifier[] GHOST_ICONS = {
			Identifier.ofVanilla("container/slot/helmet"),
			Identifier.ofVanilla("container/slot/chestplate"),
			Identifier.ofVanilla("container/slot/leggings"),
			Identifier.ofVanilla("container/slot/boots")
	};

	private static final int ICON = 16;
	private static final int TEXT_H = 9;
	private static final int HOTBAR_HALF = 91; // ホットバーは中央に幅182px

	@Override
	public void render(DrawContext context, RenderTickCounter tickCounter) {
		ArmorHudConfig config = ArmorHudConfig.get();
		MinecraftClient client = MinecraftClient.getInstance();

		if (!config.showHud || client.player == null || client.options.hudHidden || client.player.isSpectator()) {
			return;
		}

		List<ItemStack> items = new ArrayList<>(4);
		List<Identifier> ghosts = new ArrayList<>(4);

		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack stack = client.player.getEquippedStack(SLOTS[i]);

			if (stack.isEmpty() && config.emptyMode == EmptySlotMode.HIDE) {
				continue;
			}

			items.add(stack);
			ghosts.add(GHOST_ICONS[i]);
		}

		if (items.isEmpty()) {
			return;
		}

		// 表示条件: 傷あり / ピンチの装備が1つも無ければ出さない
		if (config.showCondition != ShowCondition.ALWAYS && !matchesCondition(items, config)) {
			return;
		}

		TextRenderer tr = client.textRenderer;
		Boolean horizontal = config.layout == HudLayout.HORIZONTAL;
		InfoMode inside = config.inside;
		InfoMode outside = config.outside;

		// 隙間なし: ホットバーのツギ目枠(20)。隙間あり: オフハンド風の角丸(22)
		int cell = config.slotGap > 0 ? OFFHAND_CELL : CELL;
		int iconOff = cell == OFFHAND_CELL ? OFFHAND_ICON_OFF : ICON_OFF;

		// 枠外の表示が占有する高さ/幅（横なら上、縦なら脇）
		int topExtra = 0;
		int sideExtra = 0;

		if (horizontal && outside.isText()) {
			topExtra = TEXT_H + 1;
		} else if (!horizontal && outside != InfoMode.NONE) {
			if (outside == InfoMode.GAUGE) {
				sideExtra = 3; // 隙間1 + ゲージ幅2
			} else {
				for (ItemStack stack : items) {
					if (hasDurability(stack)) {
						sideExtra = Math.max(sideExtra, 2 + tr.getWidth(infoText(outside, stack)));
					}
				}

				if (sideExtra == 0) {
					sideExtra = 2; // 全部未装備でも一応枠分は確保
				}
			}
		}

		int n = items.size();
		int panelW;
		int panelH;

		if (horizontal) {
			panelW = n * cell + (n - 1) * config.slotGap;
			panelH = cell + topExtra;
		} else {
			panelW = cell + sideExtra;
			panelH = n * cell + (n - 1) * config.slotGap;
		}

		int screenW = context.getScaledWindowWidth();
		int screenH = context.getScaledWindowHeight();

		// オフハンド枠（幅29px）が出ているときは、その分ずらす
		boolean hudRight = config.position == HudPosition.HOTBAR_RIGHT;
		boolean offhandRight = client.player.getMainArm() == Arm.LEFT;
		int sidePad = hudRight == offhandRight && !client.player.getStackInHand(Hand.OFF_HAND).isEmpty()
				? 29 : 0;

		int x0 = hudRight
				? screenW / 2 + HOTBAR_HALF + sidePad + config.hotbarGap
				: screenW / 2 - HOTBAR_HALF - sidePad - config.hotbarGap - panelW;
		x0 += config.offsetX;

		// 下端は画面端に揃える（ホットバーの底と同じ高さ）
		int y0 = screenH - panelH + config.offsetY;
		int yFrame0 = y0 + topExtra;

		boolean sideStripLeft = !horizontal && outside != InfoMode.NONE && config.outsideSide == OutsideSide.LEFT;

		// 見えているスロットの位置を先に決めておく
		int[] xs = new int[n];
		int[] ys = new int[n];

		for (int i = 0; i < n; i++) {
			xs[i] = horizontal
					? x0 + i * (cell + config.slotGap)
					: x0 + (sideStripLeft ? sideExtra : 0);
			ys[i] = horizontal
					? yFrame0
					: yFrame0 + i * (cell + config.slotGap);
		}

		// HUD スケール。ホットバーに近い側の下角を支点に拡縮する
		float scale = config.hudScale / 100.0F;

		if (scale != 1.0F) {
			float anchorX = config.position == HudPosition.HOTBAR_LEFT ? x0 + panelW : x0;
			float anchorY = y0 + panelH;
			var matrices = context.getMatrices();
			matrices.pushMatrix();
			matrices.translate(anchorX, anchorY);
			matrices.scale(scale, scale);
			matrices.translate(-anchorX, -anchorY);
		}

		for (int i = 0; i < n; i++) {
			ItemStack stack = items.get(i);
			int fx = xs[i];
			int fy = ys[i];

			drawSlot(context, config, stack, ghosts.get(i), fx, fy, cell, iconOff);

			if (hasDurability(stack)) {
				float ratio = durabilityRatio(stack);
				int color = durabilityColor(ratio);

				// 枠の中: バニラのアイテムオーバーレイ（耐久バーや個数と同じ描画）に任せる
				if (inside == InfoMode.GAUGE) {
					// ほぼ満タン(削れ1以下)までは出さない。プラグイン配布の見掛け新装備も対象
					if (stack.getDamage() > 1) {
						context.drawStackOverlay(tr, stack, fx + iconOff, fy + iconOff);
					}
				} else if (inside.isText()) {
					context.drawStackOverlay(tr, stack, fx + iconOff, fy + iconOff, infoText(inside, stack));
				}

				// 枠の外
				if (outside != InfoMode.NONE && !(horizontal && outside == InfoMode.GAUGE)) {
					drawOutside(context, config, tr, stack, outside, ratio, color, fx, fy, cell, horizontal);
				}

				// ピンチで赤く点滅（sin波で滑らかに）
				if (config.warnBlink && ratio * 100.0F <= config.warnPercent) {
					double phase = System.nanoTime() / 1.0E9 * Math.PI * 3.0;
					int alpha = (int) ((Math.sin(phase) + 1.0) / 2.0 * 0.7 * 255.0);
					int blink = alpha << 24 | 0xFF0000;
					context.fill(fx, fy, fx + cell, fy + 1, blink);
					context.fill(fx, fy + cell - 1, fx + cell, fy + cell, blink);
					context.fill(fx, fy, fx + 1, fy + cell, blink);
					context.fill(fx + cell - 1, fy, fx + cell, fy + cell, blink);
				}
			}
		}

		if (scale != 1.0F) {
			context.getMatrices().popMatrix();
		}
	}

	private static boolean matchesCondition(List<ItemStack> items, ArmorHudConfig config) {
		for (ItemStack stack : items) {
			if (!hasDurability(stack)) {
				continue;
			}

			if (config.showCondition == ShowCondition.WHEN_DAMAGED && stack.getDamage() > 0) {
				return true;
			}

			if (config.showCondition == ShowCondition.WHEN_CRITICAL
					&& durabilityRatio(stack) * 100.0F <= config.warnPercent) {
				return true;
			}
		}

		return false;
	}

	private void drawSlot(DrawContext context, ArmorHudConfig config, ItemStack stack, Identifier ghost,
					  int fx, int fy, int cell, int iconOff) {
		float alpha = stack.isEmpty() ? config.emptyMode.alpha() : 1.0F;
		int tint = Math.round(alpha * 255.0F) << 24 | 0xFFFFFF;

		if (stack.isEmpty() && config.background == SlotBackground.GHOST) {
			// ゴーストアイコンは白ティントで（素のままだと暗くて見づらい）
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, ghost,
					fx + iconOff, fy + iconOff, ICON, ICON, tint);
			return;
		}

		if (config.background == SlotBackground.FRAME) {
			if (cell == OFFHAND_CELL) {
				// 角丸のオフハンド枠をそのまま切り抜く（アイテムは+3+3）
				context.drawTexture(RenderPipelines.GUI_TEXTURED, OFFHAND_TEXTURE, fx, fy,
						(float) OFFHAND_U, (float) OFFHAND_V, cell, cell, cell, cell, OFFHAND_TEX_W, OFFHAND_TEX_H, tint);
			} else {
				// ホットバーのセルをそのまま切り抜く（アイテムは+2+2）
				context.drawTexture(RenderPipelines.GUI_TEXTURED, HOTBAR_TEXTURE, fx, fy,
						(float) CELL_U, (float) CELL_V, cell, cell, cell, cell, HOTBAR_TEX_W, HOTBAR_TEX_H, tint);
			}
		}

		if (!stack.isEmpty()) {
			context.drawItem(stack, fx + iconOff, fy + iconOff);
		}
	}

	private void drawOutside(DrawContext context, ArmorHudConfig config, TextRenderer tr, ItemStack stack,
							 InfoMode outside, float ratio, int color, int fx, int fy, int cell, boolean horizontal) {
		if (outside == InfoMode.GAUGE) {
			// 縦ゲージ用。縦向きのときだけ有効（横向きは呼ばれない）
			// バニラに合わせて、ほぼ満タン(削れ1以下)のときは出さない
			if (stack.getDamage() <= 1) {
				return;
			}

			int gx = config.outsideSide == OutsideSide.LEFT ? fx - 3 : fx + cell + 1;

			if (config.dynamicContrast) {
				context.fill(gx - 1, fy, gx + 3, fy + cell, 0x66000000);
			}

			int h = Math.round((cell - 2) * ratio);
			context.fill(gx, fy + 1, gx + 2, fy + cell - 1, 0xFF1F1F1F);
			if (h > 0) {
				context.fill(gx, fy + cell - 1 - h, gx + 2, fy + cell - 1, color);
			}
			return;
		}

		String text = infoText(outside, stack);
		int w = tr.getWidth(text);
		int tx;
		int ty;

		if (horizontal) {
			tx = fx + cell / 2 - w / 2;
			ty = fy - TEXT_H;
		} else {
			tx = config.outsideSide == OutsideSide.LEFT ? fx - 2 - w : fx + cell + 2;
			ty = fy + (cell - TEXT_H) / 2;
		}

		if (config.dynamicContrast) {
			context.fill(tx - 1, ty - 1, tx + w + 1, ty + TEXT_H, 0x66000000);
		}

		context.drawTextWithShadow(tr, text, tx, ty, color);
	}

	private static boolean hasDurability(ItemStack stack) {
		return !stack.isEmpty() && stack.isDamageable() && stack.getMaxDamage() > 0;
	}

	private static float durabilityRatio(ItemStack stack) {
		float ratio = 1.0F - (float) stack.getDamage() / (float) stack.getMaxDamage();
		return Math.max(0.0F, Math.min(1.0F, ratio));
	}

	private static String infoText(InfoMode mode, ItemStack stack) {
		return switch (mode) {
			case PERCENT -> Math.round(durabilityRatio(stack) * 100.0F) + "%";
			case NUMBER -> Integer.toString(stack.getMaxDamage() - stack.getDamage());
			case LOST -> Integer.toString(stack.getDamage());
			case LOST_PERCENT -> Math.round((1.0F - durabilityRatio(stack)) * 100.0F) + "%";
			default -> "";
		};
	}

	// 残量 1.0 で緑、0.5 くらいで黄、0 で赤。色相を残量に乗せるだけ
	private static int durabilityColor(float ratio) {
		float h6 = Math.max(0.0F, Math.min(1.0F, ratio)) * 2.0F; // 0..2 (赤→黄→緑)
		int i = Math.min(1, (int) h6); // ちょうど1.0でi=2に落ちて黄になるのを防ぐ
		float f = h6 - i;
		int r;
		int g;

		if (i == 0) {
			r = 255;
			g = (int) (255 * f);
		} else {
			r = (int) (255 * (1.0F - f));
			g = 255;
		}

		return 0xFF000000 | (r << 16) | (g << 8);
	}
}
