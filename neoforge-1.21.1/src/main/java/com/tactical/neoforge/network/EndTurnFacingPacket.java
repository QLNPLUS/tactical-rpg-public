package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** End the turn after turning the character to a chosen yaw. */
public record EndTurnFacingPacket(ActionStamp stamp, int yaw) implements CustomPacketPayload {

    public static final Type<EndTurnFacingPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "end_turn_facing"));

    public static final StreamCodec<FriendlyByteBuf, EndTurnFacingPacket> STREAM_CODEC =
            StreamCodec.of(EndTurnFacingPacket::encode, EndTurnFacingPacket::decode);

    private static void encode(FriendlyByteBuf buffer, EndTurnFacingPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.yaw);
    }

    private static EndTurnFacingPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new EndTurnFacingPacket(stamp, buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
