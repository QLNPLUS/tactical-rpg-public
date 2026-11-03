package com.tactical.neoforge.network;

import com.tactical.core.progression.CharacterStat;
import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * "Commit this allocation, at the revision I was shown."
 *
 * <p>The six increments are non-negative by contract: an allocation only ever adds points, so a client
 * cannot take back a point that is already invested, and the server never subtracts one. The payload carries
 * no price, no level and no resulting stat value; those are re-derived from the stored profile.</p>
 */
public record CharacterAllocatePacket(int requestId, long expectedRevision, int[] increments)
        implements CustomPacketPayload {

    /** Ceiling of one increment on the wire; the configured cap is far lower. */
    public static final int MAX_INCREMENT = 1_000_000;

    public static final Type<CharacterAllocatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "character_allocate"));
    public static final StreamCodec<FriendlyByteBuf, CharacterAllocatePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.requestId);
                buffer.writeVarLong(packet.expectedRevision);
                buffer.writeVarInt(packet.increments.length);
                for (int value : packet.increments) buffer.writeVarInt(value);
            },
            buffer -> {
                int requestId = buffer.readVarInt();
                long revision = buffer.readVarLong();
                int size = buffer.readVarInt();
                if (size != CharacterStat.COUNT)
                    throw new IllegalArgumentException("An allocation needs exactly " + CharacterStat.COUNT + " increments");
                int[] increments = new int[size];
                for (int index = 0; index < size; index++) {
                    int value = buffer.readVarInt();
                    if (value < 0 || value > MAX_INCREMENT) throw new IllegalArgumentException("Invalid allocation increment");
                    increments[index] = value;
                }
                return new CharacterAllocatePacket(requestId, revision, increments);
            });

    public CharacterAllocatePacket {
        if (requestId < 0) throw new IllegalArgumentException("Invalid character request id");
        if (expectedRevision < 0) throw new IllegalArgumentException("Invalid expected revision");
        if (increments == null || increments.length != CharacterStat.COUNT)
            throw new IllegalArgumentException("An allocation needs exactly " + CharacterStat.COUNT + " increments");
        int[] copy = increments.clone();
        for (int value : copy) {
            if (value < 0 || value > MAX_INCREMENT) throw new IllegalArgumentException("Invalid allocation increment");
        }
        increments = copy;
    }

    @Override public int[] increments() { return increments.clone(); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
