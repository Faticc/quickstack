package io.github.faticc.network

import io.github.faticc.QuickStack
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class QuickStackPacket : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<QuickStackPacket> =
            CustomPacketPayload.Type(QuickStack.id("quick_stack"))

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, QuickStackPacket> =
            CustomPacketPayload.codec({ _, _ -> }, { QuickStackPacket() })
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}