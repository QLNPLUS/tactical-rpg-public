package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks to walk to a grid cell. */
public record MoveIntentPacket(ActionStamp stamp, int x, int y, int z) implements CustomPacketPayload {

    public static final Type<MoveIntentPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "move_intent"));

    public static final StreamCodec<FriendlyByteBuf, MoveIntentPacket> STREAM_CODEC =
            StreamCodec.of(MoveIntentPacket::encode, MoveIntentPacket::decode);

    private static void encode(FriendlyByteBuf buffer, MoveIntentPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.x);
        buffer.writeVarInt(packet.y);
        buffer.writeVarInt(packet.z);
    }

    private static MoveIntentPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new MoveIntentPacket(stamp, buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
