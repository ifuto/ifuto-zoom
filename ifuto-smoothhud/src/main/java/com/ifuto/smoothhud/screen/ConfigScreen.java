package com.ifuto.smoothhud.screen;

import com.ifuto.smoothhud.ConfigManager;
import com.ifuto.smoothhud.Slide;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * 速さの設定画面（元 Mod の移植。見本の動きも直してある）。
 */
@Environment(EnvType.CLIENT)
public class ConfigScreen extends Screen {
	private static final Identifier HOTBAR = Identifier.ofVanilla("hud/hotbar");
	private static final Identifier SELECTION = Identifier.ofVanilla("hud/hotbar_selection");

	private final Screen parent;
	private int selectedSlot;
	private float tempSpeed = ConfigManager.getConfig().speed;
	private long lastTickTime;
	private float currentX;

	public ConfigScreen(Screen parent) {
		super(Text.literal("SmoothHud Config"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		SliderWidget speedButtonWidget = new SliderWidget(
				this.width / 2 - 100, this.height / 4 + 24,
				200, 20,
				Text.literal("Speed: " + (int) this.tempSpeed),
				((int) this.tempSpeed - 2) / 38.0) {
			@Override
			protected void updateMessage() {
				ConfigScreen.this.tempSpeed = (int) (this.value * 38.0) + 2;
				this.setMessage(Text.literal("Speed: " + (int) ConfigScreen.this.tempSpeed));
			}

			@Override
			protected void applyValue() {
			}
		};

		ButtonWidget saveButtonWidget = ButtonWidget.builder(Text.literal("Save & Exit"), button -> {
			ConfigManager.getConfig().speed = this.tempSpeed;
			ConfigManager.saveConfig();
			this.client.setScreen(this.parent);
		}).dimensions(
				this.width / 2 - 100, this.height / 4 + 48,
				200, 20).build();

		ButtonWidget cancelButtonWidget = ButtonWidget.builder(Text.literal("Cancel"), button -> {
			this.client.setScreen(this.parent);
		}).dimensions(
				this.width / 2 - 100, this.height / 4 + 72,
				200, 20).build();

		this.addDrawableChild(speedButtonWidget);
		this.addDrawableChild(saveButtonWidget);
		this.addDrawableChild(cancelButtonWidget);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode >= 49 && keyCode <= 57) {
			this.selectedSlot = keyCode - 49;
			return true;
		}

		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		double scrolled = MathHelper.clamp(verticalAmount, -1.0, 1.0);

		if (scrolled > 0) {
			this.selectedSlot = (this.selectedSlot + 8) % 9;
		} else if (scrolled < 0) {
			this.selectedSlot = (this.selectedSlot + 1) % 9;
		}

		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		float targetX = this.selectedSlot * Slide.SLOT;
		long now = System.currentTimeMillis();

		if (this.lastTickTime == 0) {
			this.currentX = targetX;
		} else {
			float deltaTime = (now - this.lastTickTime) / 1000.0f;
			this.currentX = Slide.toward(this.currentX, targetX, deltaTime, this.tempSpeed);
		}

		this.lastTickTime = now;

		int hotbarX = this.width / 2 - 91;
		int hotbarY = this.height / 4 + 100;
		int selectionX = Math.round(this.currentX) - 1;

		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HOTBAR, hotbarX, hotbarY, 182, 22);
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, SELECTION,
				hotbarX + selectionX, hotbarY - 1, 24, 23);

		int color = ((now / 300) % 2 == 0) ? 0xFF555555 : 0xFF666666;
		context.drawCenteredTextWithShadow(this.textRenderer, "Scroll or press hotkeys to preview",
				this.width / 2, hotbarY + 25, color);
	}
}
