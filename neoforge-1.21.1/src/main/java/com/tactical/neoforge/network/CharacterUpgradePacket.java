package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * "Confirm one level-up, at the revision I was shown."
 *
 * <p>No price and no point count travel with it. The explained revision is the only ordering token, so a
 * duplicate, a replayed or a stale confirmation is refused instead of paying twice; the server re-reads the
 * level cap, the current price and the real XP balance before it spends anything.</p>
 */
public record CharacterUpgradePacket(int requestId, long expectedRevision) implements CustomPacketPayload {

    public static final Type<CharacterUpgradePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "character_upgrade"));
    public static final StreamCodec<FriendlyByteBuf, CharacterUpgradePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.requestId);
                buffer.writeVarLong(packet.expectedRevision);
            },
            buffer -> new CharacterUpgradePacket(buffer.readVarInt(), buffer.readVarLong()));

    public CharacterUpgradePacket {
        if (requestId < 0) throw new IllegalArgumentException("Invalid character request id");
        if (expectedRevision < 0) throw new IllegalArgumentException("Invalid expected revision");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
