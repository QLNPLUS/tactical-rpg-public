package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks the server to open the AUI container-backed battle inventory. */
public record OpenInventoryPacket() implements CustomPacketPayload {

    public static final Type<OpenInventoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "open_inventory"));

    public static final StreamCodec<FriendlyByteBuf, OpenInventoryPacket> STREAM_CODEC =
            StreamCodec.of((buffer, packet) -> {
            }, buffer -> new OpenInventoryPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
