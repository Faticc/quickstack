package io.github.faticc

import io.github.faticc.network.QuickStackPacket
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.math.abs

object QuickStack : ModInitializer {
	const val MOD_ID: String = "quickstack"
	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	const val RADIUS_HORIZONTAL = 20.0
	const val RADIUS_VERTICAL = 10.0
	override fun onInitialize() {
		LOGGER.info("Initializing QuickStack mod...")

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
		val playerInv = player.inventory

		var isMainInventoryEmpty = true
		for (i in 9 until 36) {
			if (!playerInv.getItem(i).isEmpty) {
				isMainInventoryEmpty = false
				break
			}
		}
		if (isMainInventoryEmpty) return

		val level = player.level()
		val playerPos = player.blockPosition()
		val playerChunkX = playerPos.x shr 4
		val playerChunkZ = playerPos.z shr 4
		val horizontalRadiusSqr = RADIUS_HORIZONTAL * RADIUS_HORIZONTAL

		val regularInventories = mutableSetOf<Container>()
		val furnaces = mutableSetOf<AbstractFurnaceBlockEntity>()
		val processedPositions = mutableSetOf<BlockPos>()

		for (cx in (playerChunkX - 1)..(playerChunkX + 1)) {
			for (cz in (playerChunkZ - 1)..(playerChunkZ + 1)) {
				if (!level.hasChunk(cx, cz)) continue
				val chunk = level.getChunk(cx, cz)

				for (blockEntity in chunk.blockEntities.values) {
					val pos = blockEntity.blockPos
					if (!processedPositions.add(pos)) continue // Уже обработали

					val dx = pos.x - playerPos.x
					val dz = pos.z - playerPos.z
					if (dx * dx + dz * dz > horizontalRadiusSqr) continue
					if (abs(pos.y - playerPos.y) > RADIUS_VERTICAL) continue

					val blockState = blockEntity.blockState

					when (blockEntity) {
						is AbstractFurnaceBlockEntity -> {
							furnaces.add(blockEntity)
						}
						is ChestBlockEntity -> {
							if (blockState.block is ChestBlock) {
								val chestType = blockState.getValue(ChestBlock.TYPE)
								if (chestType != ChestType.SINGLE) {
									val connectedDir = ChestBlock.getConnectedDirection(blockState)
									processedPositions.add(pos.relative(connectedDir))
								}

								val chestBlock = blockState.block as ChestBlock
								val realContainer = ChestBlock.getContainer(chestBlock, blockState, level, pos, true) ?: blockEntity
								regularInventories.add(realContainer)
							}
						}
						is Container -> {
							regularInventories.add(blockEntity)
						}
					}
				}
			}
		}

		for (i in 9 until 36) {
			var playerStack = playerInv.getItem(i)
			if (playerStack.isEmpty) continue

			for (chestInv in regularInventories) {
				if (playerStack.isEmpty) break
				if (chestContainsItem(chestInv, playerStack)) {
					playerStack = insertIntoInventory(chestInv, playerStack)
				}
			}

			if (!playerStack.isEmpty) {
				val isCoal = playerStack.`is`(ItemTags.COALS)
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
					}
				}
			}

			playerInv.setItem(i, playerStack)
		}
	}

	private fun isOre(stack: ItemStack): Boolean {
		val hasOreTag = stack.tags().anyMatch { tagKey ->
			val tagPath = tagKey.location().path
			tagPath.contains("ore") || tagPath.contains("raw_materials")
		}

		val path = BuiltInRegistries.ITEM.getKey(stack.item).path
		return hasOreTag || path.contains("ore") || path.contains("raw_") || path.contains("ancient_debris")
	}

	private fun insertIntoFurnaceSlot(furnace: AbstractFurnaceBlockEntity, slot: Int, stack: ItemStack): ItemStack {
		val slotStack = furnace.getItem(slot)

		if (!slotStack.isEmpty && ItemStack.isSameItemSameComponents(slotStack, stack) && slotStack.count < slotStack.maxStackSize) {
			val spaceLeft = slotStack.maxStackSize - slotStack.count
			val amountToMove = minOf(spaceLeft, stack.count)

			slotStack.grow(amountToMove)
			stack.shrink(amountToMove)
			furnace.setChanged()
		} else if (slotStack.isEmpty) {
			val amountToMove = minOf(stack.count, furnace.maxStackSize)
			val newStack = stack.copy()
			newStack.count = amountToMove

			furnace.setItem(slot, newStack)
			stack.shrink(amountToMove)
			furnace.setChanged()
		}

		return stack
	}

	private fun chestContainsItem(inventory: Container, stack: ItemStack): Boolean {
		for (i in 0 until inventory.containerSize) {
			if (ItemStack.isSameItemSameComponents(inventory.getItem(i), stack)) {
				return true
			}
		}
		return false
	}

	private fun insertIntoInventory(inventory: Container, stack: ItemStack): ItemStack {
		for (i in 0 until inventory.containerSize) {
			if (stack.isEmpty) break
			val slotStack = inventory.getItem(i)

			if (ItemStack.isSameItemSameComponents(slotStack, stack) && slotStack.count < slotStack.maxStackSize) {
				val spaceLeft = slotStack.maxStackSize - slotStack.count
				val amountToMove = minOf(spaceLeft, stack.count)

				slotStack.grow(amountToMove)
				stack.shrink(amountToMove)
				inventory.setChanged()
			}
		}

		if (!stack.isEmpty) {
			for (i in 0 until inventory.containerSize) {
				if (stack.isEmpty) break
				val slotStack = inventory.getItem(i)

				if (slotStack.isEmpty) {
					inventory.setItem(i, stack.copy())
					stack.count = 0
					inventory.setChanged()
					break
				}
			}
		}
		return stack
	}
}