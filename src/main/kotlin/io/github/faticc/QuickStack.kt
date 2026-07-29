package io.github.faticc

import io.github.faticc.network.QuickStackPacket
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import org.slf4j.LoggerFactory

object QuickStack : ModInitializer {
	const val MOD_ID: String = "quickstack"
	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	const val SEARCH_RADIUS_BLOCKS = 16.0

	override fun onInitialize() {
		LOGGER.info("Initializing QuickStack mod (Chunk-Optimized)...")

		PayloadTypeRegistry.serverboundPlay().register(QuickStackPacket.ID, QuickStackPacket.CODEC)

		ServerPlayNetworking.registerGlobalReceiver(QuickStackPacket.ID) { _, context ->
			val player: ServerPlayer = context.player()
			context.server().execute {
				performQuickStack(player)
			}
		}
	}

	fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

	private fun performQuickStack(player: ServerPlayer) {
		val level = player.level()
		val playerPos = player.blockPosition()

		val playerChunkX = playerPos.x shr 4
		val playerChunkZ = playerPos.z shr 4

		val radiusSqr = SEARCH_RADIUS_BLOCKS * SEARCH_RADIUS_BLOCKS

		val regularInventories = mutableSetOf<Container>()
		val furnaces = mutableSetOf<AbstractFurnaceBlockEntity>()

		for (cx in (playerChunkX - 1)..(playerChunkX + 1)) {
			for (cz in (playerChunkZ - 1)..(playerChunkZ + 1)) {

				if (level.hasChunk(cx, cz)) {
					val chunk = level.getChunk(cx, cz)

					for (blockEntity in chunk.blockEntities.values) {

						if (blockEntity.blockPos.distSqr(playerPos) <= radiusSqr) {

							val blockState = blockEntity.blockState

							if (blockEntity is AbstractFurnaceBlockEntity) {
								furnaces.add(blockEntity)
							} else if (blockEntity is Container) {
								val pos = blockEntity.blockPos
								val realContainer: Container = if (blockEntity is ChestBlockEntity && blockState.block is ChestBlock) {
									ChestBlock.getContainer(blockState.block as ChestBlock, blockState, level, pos, true) ?: blockEntity
								} else {
									blockEntity
								}
								regularInventories.add(realContainer)
							}
						}
					}
				}
			}
		}

		val playerInv = player.inventory

		for (i in 9 until 36) {
			var playerStack = playerInv.getItem(i)
			if (playerStack.isEmpty) continue

			for (chestInv in regularInventories) {
				if (playerStack.isEmpty) break

				if (chestContainsItem(chestInv, playerStack)) {
					val remainder = insertIntoInventory(chestInv, playerStack)
					playerInv.setItem(i, remainder)
					playerStack = remainder
				}
			}

			if (!playerStack.isEmpty) {
				val isCoal = playerStack.item == Items.COAL || playerStack.item == Items.CHARCOAL
				val isOreItem = isOre(playerStack)

				if (isCoal || isOreItem) {
					for (furnace in furnaces) {
						if (playerStack.isEmpty) break

						if (isCoal) {
							playerStack = insertIntoFurnaceSlot(furnace, 1, playerStack)
						}

						if (isOreItem && !playerStack.isEmpty) {
							playerStack = insertIntoFurnaceSlot(furnace, 0, playerStack)
						}

						playerInv.setItem(i, playerStack)
					}
				}
			}
		}
	}

	private fun isOre(stack: ItemStack): Boolean {
		val path = BuiltInRegistries.ITEM.getKey(stack.item).path
		return path.contains("ore") || path.contains("raw_") || path.contains("ancient_debris")
	}


	private fun insertIntoFurnaceSlot(furnace: AbstractFurnaceBlockEntity, slot: Int, stack: ItemStack): ItemStack {
		val remainder = stack.copy()
		val slotStack = furnace.getItem(slot)

		if (!slotStack.isEmpty && ItemStack.isSameItemSameComponents(slotStack, remainder) && slotStack.count < slotStack.maxStackSize) {
			val spaceLeft = slotStack.maxStackSize - slotStack.count
			val amountToMove = minOf(spaceLeft, remainder.count)

			slotStack.grow(amountToMove)
			remainder.shrink(amountToMove)
			furnace.setChanged()
		}

		else if (slotStack.isEmpty) {
			val amountToMove = minOf(remainder.count, furnace.maxStackSize)
			val newStack = remainder.copy()
			newStack.count = amountToMove

			furnace.setItem(slot, newStack)
			remainder.shrink(amountToMove)
			furnace.setChanged()
		}

		return remainder
	}

	private fun chestContainsItem(inventory: Container, stack: ItemStack): Boolean {
		for (i in 0 until inventory.containerSize) {
			val slotStack = inventory.getItem(i)
			if (ItemStack.isSameItemSameComponents(slotStack, stack)) {
				return true
			}
		}
		return false
	}

	private fun insertIntoInventory(inventory: Container, stack: ItemStack): ItemStack {
		val remainder = stack.copy()

		for (i in 0 until inventory.containerSize) {
			if (remainder.isEmpty) break
			val slotStack = inventory.getItem(i)

			if (ItemStack.isSameItemSameComponents(slotStack, remainder) && slotStack.count < slotStack.maxStackSize) {
				val spaceLeft = slotStack.maxStackSize - slotStack.count
				val amountToMove = minOf(spaceLeft, remainder.count)

				slotStack.grow(amountToMove)
				remainder.shrink(amountToMove)
				inventory.setChanged()
			}
		}

		if (!remainder.isEmpty) {
			for (i in 0 until inventory.containerSize) {
				if (remainder.isEmpty) break
				val slotStack = inventory.getItem(i)

				if (slotStack.isEmpty) {
					inventory.setItem(i, remainder.copy())
					remainder.count = 0
					inventory.setChanged()
					break
				}
			}
		}
		return remainder
	}
}