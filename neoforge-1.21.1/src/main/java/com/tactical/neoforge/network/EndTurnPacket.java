package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks to end the current turn. */
public record EndTurnPacket(ActionStamp stamp) implements CustomPacketPayload {

    public static final Type<EndTurnPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "end_turn"));

    public static final StreamCodec<FriendlyByteBuf, EndTurnPacket> STREAM_CODEC =
            StreamCodec.of((buffer, packet) -> StampIO.write(buffer, packet.stamp),
                    buffer -> new EndTurnPacket(StampIO.read(buffer)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
