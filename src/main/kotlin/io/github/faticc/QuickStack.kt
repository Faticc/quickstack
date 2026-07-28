package io.github.faticc

import io.github.faticc.network.QuickStackPacket
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import org.slf4j.LoggerFactory

object QuickStack : ModInitializer {
	const val MOD_ID: String = "quickstack"
	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	const val SEARCH_RADIUS = 10.0

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
		val level = player.level()
		val pos = player.blockPosition()
		val searchBox = AABB(pos).inflate(SEARCH_RADIUS)

		val inventories = mutableSetOf<Container>()
		for (x in searchBox.minX.toInt()..searchBox.maxX.toInt()) {
			for (y in searchBox.minY.toInt()..searchBox.maxY.toInt()) {
				for (z in searchBox.minZ.toInt()..searchBox.maxZ.toInt()) {
					val blockPos = BlockPos(x, y, z)
					val blockState = level.getBlockState(blockPos)
					val blockEntity: BlockEntity? = level.getBlockEntity(blockPos)

					if (blockEntity is Container) {
						val realContainer: Container = if (blockEntity is ChestBlockEntity && blockState.block is ChestBlock) {
							ChestBlock.getContainer(blockState.block as ChestBlock, blockState, level, blockPos, true) ?: blockEntity
						} else {
							blockEntity
						}
						inventories.add(realContainer)
					}
				}
			}
		}

		val playerInv = player.inventory

		for (i in 9 until 36) {
			var playerStack = playerInv.getItem(i)
			if (playerStack.isEmpty) continue

			for (chestInv in inventories) {
				if (playerStack.isEmpty) break

				if (chestContainsItem(chestInv, playerStack)) {
					val remainder = insertIntoInventory(chestInv, playerStack)
					playerInv.setItem(i, remainder)
					playerStack = remainder
				}
			}
		}
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