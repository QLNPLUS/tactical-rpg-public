package com.tactical.neoforge.network;

import com.tactical.core.progression.CharacterStat;
import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * "Show me what this draft would look like, without committing it."
 *
 * <p>The draft is exactly six increments in the frozen stat order. It is never priced by the client: the
 * server re-reads the rules, re-checks the revision and computes the preview from the real attribute
 * instances, so a preview and a later confirmation cannot disagree. A preview changes nothing - no
 * attribute is written and rolled back, and no point is reserved.</p>
 */
public record CharacterPreviewPacket(int requestId, long expectedRevision, int[] increments)
        implements CustomPacketPayload {

    /** Ceiling of one increment on the wire; the configured cap is far lower. */
    public static final int MAX_INCREMENT = 1_000_000;

    public static final Type<CharacterPreviewPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "character_preview"));
    public static final StreamCodec<FriendlyByteBuf, CharacterPreviewPacket> STREAM_CODEC = StreamCodec.of(
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
                    throw new IllegalArgumentException("A draft needs exactly " + CharacterStat.COUNT + " increments");
                int[] increments = new int[size];
                for (int index = 0; index < size; index++) {
                    int value = buffer.readVarInt();
                    if (value < 0 || value > MAX_INCREMENT) throw new IllegalArgumentException("Invalid draft increment");
                    increments[index] = value;
                }
                return new CharacterPreviewPacket(requestId, revision, increments);
            });

    public CharacterPreviewPacket {
        if (requestId < 0) throw new IllegalArgumentException("Invalid character request id");
        if (expectedRevision < 0) throw new IllegalArgumentException("Invalid expected revision");
        if (increments == null || increments.length != CharacterStat.COUNT)
            throw new IllegalArgumentException("A draft needs exactly " + CharacterStat.COUNT + " increments");
        int[] copy = increments.clone();
        for (int value : copy) {
            if (value < 0 || value > MAX_INCREMENT) throw new IllegalArgumentException("Invalid draft increment");
        }
        increments = copy;
    }

    @Override public int[] increments() { return increments.clone(); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
