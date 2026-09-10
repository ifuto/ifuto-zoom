package com.ifuto.armorhud.hud;

import com.ifuto.armorhud.config.ArmorHudConfig;
import com.ifuto.armorhud.config.DurabilityStyle;
import com.ifuto.armorhud.config.HudLayout;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 装備中の防具を HUD に描く。上から頭→胴→脚→足の順。
 * 耐久の色はバニラのアイテムバーよろしく 緑→黄→赤 の連続グラデーション。
 */
@Environment(EnvType.CLIENT)
public class ArmorHudRenderer implements HudElement {
	private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private static final int ICON = 16;
	private static final int GAP = 3;
	private static final int MARGIN = 4;

	@Override
	public void render(DrawContext context, RenderTickCounter tickCounter) {
		ArmorHudConfig config = ArmorHudConfig.get();
		MinecraftClient client = MinecraftClient.getInstance();

		if (!config.showHud || client.player == null || client.options.hudHidden) {
			return;
		}

		List<ItemStack> items = new ArrayList<>(4);
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = client.player.getEquippedStack(slot);

			if (stack.isEmpty() && config.hideEmptySlots) {
				continue;
			}

			items.add(stack);
		}

		if (items.isEmpty()) {
			return;
		}

		TextRenderer textRenderer = client.textRenderer;
		DurabilityStyle style = config.durability;

		// 配置の基準を決める前にパネル全体のサイズを測っておく（数値の桁で揺れないように）
		boolean horizontal = config.layout == HudLayout.HORIZONTAL;
		int[] cellWidths = new int[items.size()];
		int panelW = 0;
		int panelH = 0;

		for (int i = 0; i < items.size(); i++) {
			int cell = ICON;

			if (style.showText() && hasDurability(items.get(i))) {
				cell += 2 + textRenderer.getWidth(durabilityText(items.get(i), style));
			}

			cellWidths[i] = cell;

			if (horizontal) {
				panelW += cell + (i > 0 ? GAP : 0);
				panelH = ICON;
			} else {
				panelW = Math.max(panelW, cell);
				panelH += ICON + (i > 0 ? GAP : 0);
			}
		}

		int screenW = context.getScaledWindowWidth();
		int screenH = context.getScaledWindowHeight();
		int x = config.position.isLeft() ? MARGIN + config.offsetX : screenW - MARGIN - panelW + config.offsetX;
		int y = config.position.isTop() ? MARGIN + config.offsetY : screenH - MARGIN - panelH + config.offsetY;

		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = items.get(i);

			if (stack.isEmpty()) {
				// 未装備は薄い枠だけ
				context.fill(x, y, x + ICON, y + ICON, 0x55000000);
			} else {
				context.drawItem(stack, x, y);

				if (hasDurability(stack)) {
					float ratio = durabilityRatio(stack);
					int color = durabilityColor(ratio);

					if (style.showBar()) {
						// アイコン下端にバニラ風の 2px バー
						context.fill(x + 2, y + 13, x + 14, y + 15, 0xFF1F1F1F);
						context.fill(x + 2, y + 13, x + 2 + Math.round(12.0F * ratio), y + 14, color);
					}

					if (style.showText()) {
						context.drawTextWithShadow(textRenderer, durabilityText(stack, style), x + ICON + 2, y + 4, color);
					}
				}
			}

			if (horizontal) {
				x += cellWidths[i] + GAP;
			} else {
				y += ICON + GAP;
			}
		}
	}

	private static boolean hasDurability(ItemStack stack) {
		return !stack.isEmpty() && stack.isDamageable() && stack.getMaxDamage() > 0;
	}

	private static float durabilityRatio(ItemStack stack) {
		float ratio = 1.0F - (float) stack.getDamage() / (float) stack.getMaxDamage();
		return Math.max(0.0F, Math.min(1.0F, ratio));
	}

	private static String durabilityText(ItemStack stack, DurabilityStyle style) {
		if (style.isPercent()) {
			return Math.round(durabilityRatio(stack) * 100.0F) + "%";
		}

		return Integer.toString(stack.getMaxDamage() - stack.getDamage());
	}

	// 残量 1.0 で緑、0.5 くらいで黄、0 で赤。HSV の色相を残量に乗せるだけ
	private static int durabilityColor(float ratio) {
		float h6 = Math.max(0.0F, Math.min(1.0F, ratio)) * 2.0F; // 0..2 (赤→黄→緑)
		int i = (int) h6;
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
