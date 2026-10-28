package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks to attempt a flee check. */
public record FleePacket(ActionStamp stamp) implements CustomPacketPayload {

    public static final Type<FleePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "flee"));

    public static final StreamCodec<FriendlyByteBuf, FleePacket> STREAM_CODEC =
            StreamCodec.of((buffer, packet) -> StampIO.write(buffer, packet.stamp),
                    buffer -> new FleePacket(StampIO.read(buffer)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
