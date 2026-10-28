package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Asks for the nearby encounters this connection may see.
 *
 * <p>The payload carries only a correlation token. The server decides nearness, dimension,
 * eligibility and rate limit from the real connection identity, so a client cannot widen its own
 * view or name somebody else.</p>
 */
public record NearbyBattlesRequestPacket(long requestId) implements CustomPacketPayload {
    public static final Type<NearbyBattlesRequestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "nearby_battles_request"));
    public static final StreamCodec<FriendlyByteBuf, NearbyBattlesRequestPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, packet) -> buffer.writeVarLong(packet.requestId()),
                    buffer -> new NearbyBattlesRequestPacket(buffer.readVarLong()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
