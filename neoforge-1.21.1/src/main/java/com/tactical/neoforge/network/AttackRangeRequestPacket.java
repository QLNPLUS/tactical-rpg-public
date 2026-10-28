package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A correlation token only: the server chooses the owner, weapon, origin and preview bounds. */
public record AttackRangeRequestPacket(ActionStamp stamp, int requestId) implements CustomPacketPayload {
    public static final Type<AttackRangeRequestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "attack_range_request"));
    public static final StreamCodec<FriendlyByteBuf, AttackRangeRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> { StampIO.write(buffer, packet.stamp()); buffer.writeVarInt(packet.requestId()); },
            buffer -> new AttackRangeRequestPacket(StampIO.read(buffer), buffer.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
