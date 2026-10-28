package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Swap two inventory slots (free action). */
public record SwapSlotsPacket(ActionStamp stamp, int first, int second) implements CustomPacketPayload {

    public static final Type<SwapSlotsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "swap_slots"));

    public static final StreamCodec<FriendlyByteBuf, SwapSlotsPacket> STREAM_CODEC =
            StreamCodec.of(SwapSlotsPacket::encode, SwapSlotsPacket::decode);

    private static void encode(FriendlyByteBuf buffer, SwapSlotsPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.first);
        buffer.writeVarInt(packet.second);
    }

    private static SwapSlotsPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new SwapSlotsPacket(stamp, buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
