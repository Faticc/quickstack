package io.github.faticc.client

import io.github.faticc.QuickStack
import io.github.faticc.network.QuickStackPacket
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.lang.reflect.Field

object QuickStackClient : ClientModInitializer {
	private var cachedLeftPosField: Field? = null
	private var cachedTopPosField: Field? = null

	override fun onInitializeClient() {
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen is InventoryScreen) {
				val sprites = WidgetSprites(
					QuickStack.id("quick_stack_button"),
					QuickStack.id("quick_stack_button_highlighted")
				)

				val initialX = getScreenLeftPos(screen) + 128
				val initialY = getScreenTopPos(screen) + 61

				val button = object : ImageButton(
					initialX, initialY,
					20, 18,
					sprites,
					{ clickedButton ->
						ClientPlayNetworking.send(QuickStackPacket())
						clickedButton.isFocused = false
					}
				) {}

				button.setTooltip(Tooltip.create(Component.translatable("tooltip.quickstack.button")))
				Screens.getWidgets(screen).add(button)

				ScreenEvents.afterTick(screen).register { _ ->
					button.x = getScreenLeftPos(screen) + 128
					button.y = getScreenTopPos(screen) + 61
				}

				ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
					val iconX = button.x + (button.width - 16) / 2
					val iconY = button.y + (button.height - 16) / 2

					graphics.pose().pushMatrix()
					graphics.pose().translate(0.0f, -0.66f)
					graphics.item(ItemStack(Items.CHEST), iconX, iconY)
					graphics.pose().popMatrix()
				}
			}
		}
	}

	private fun getScreenLeftPos(screen: InventoryScreen): Int {
		return try {
			val field = cachedLeftPosField ?: net.minecraft.client.gui.screens.inventory.AbstractContainerScreen::class.java
				.getDeclaredField("leftPos").apply { isAccessible = true }.also { cachedLeftPosField = it }
			field.getInt(screen)
		} catch (_: Exception) {
			(screen.width - 176) / 2
		}
	}

	private fun getScreenTopPos(screen: InventoryScreen): Int {
		return try {
			val field = cachedTopPosField ?: net.minecraft.client.gui.screens.inventory.AbstractContainerScreen::class.java
				.getDeclaredField("topPos").apply { isAccessible = true }.also { cachedTopPosField = it }
			field.getInt(screen)
		} catch (_: Exception) {
			(screen.height - 166) / 2
		}
	}
}