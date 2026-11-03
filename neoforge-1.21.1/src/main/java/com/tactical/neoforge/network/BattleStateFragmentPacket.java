package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * One bounded slice of a logical {@link BattleStatePacket} body, server to client.
 *
 * <p>The whole state is encoded once into a body (bounded by {@link BattleStatePacket#MAX_BODY_BYTES}) and
 * then cut into slices of at most {@link #MAX_FRAGMENT_BYTES}. A slice carries everything a receiver needs
 * to place it, to tie it to a membership, and to refuse it: the publication it belongs to, the total
 * length and exact part layout, its own index, whether the snapshot it was cut from enters or leaves an
 * encounter, and the session/membership stamp of that snapshot. All parts of one snapshot carry the same
 * header, so a snapshot can never be assembled across two memberships or from two publications.</p>
 *
 * <p>The layout is fully determined by the total length: part {@code i} of a body is always the byte range
 * {@code [i * MAX_FRAGMENT_BYTES, min(totalBytes, (i + 1) * MAX_FRAGMENT_BYTES))} and the part count is the
 * smallest one that covers the body. A slice whose length disagrees with that range is refused instead of
 * being patched up, so a receiver never has to guess about a gap or a pad.</p>
 *
 * <p>The transport is TCP, so parts arrive in order; there is deliberately no sequence-number window,
 * retransmission or reordering logic here. {@link #publication} only orders whole snapshots: a part of an
 * older publication than the one being assembled (or already settled) is refused.</p>
 */
public record BattleStateFragmentPacket(
        long publication,
        int totalBytes,
        int partCount,
        int partIndex,
        boolean active,
        byte[] payload,
        UUID sessionId,
        long membershipServerEpoch,
        long membershipGeneration
) implements CustomPacketPayload, MemberScoped {

    /** Largest slice one payload carries. A body is cut into whole slices of this size, last one shorter. */
    public static final int MAX_FRAGMENT_BYTES = 64 * 1024;

    /** Largest number of slices one legal body can occupy. */
    public static final int MAX_PARTS =
            (BattleStatePacket.MAX_FRAMED_BYTES + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES;

    /** The header's worst case: publication, three counts, the flag, a UUID, two stamps and a length. */
    static final int MAX_HEADER_BYTES = 10 + 5 + 5 + 5 + 1 + 16 + 10 + 10 + 5;

    public static final Type<BattleStateFragmentPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "battle_state_part"));

    public static final StreamCodec<FriendlyByteBuf, BattleStateFragmentPacket> STREAM_CODEC =
            StreamCodec.of(BattleStateFragmentPacket::encode, BattleStateFragmentPacket::decode);

    public BattleStateFragmentPacket {
        if (totalBytes <= 0) throw new IllegalArgumentException("Empty battle state body");
        if (totalBytes > BattleStatePacket.MAX_FRAMED_BYTES) throw new IllegalArgumentException("Battle state body exceeds the budget");
        if (partCount != partsFor(totalBytes)) throw new IllegalArgumentException("Wrong part count for this body");
        if (partIndex < 0 || partIndex >= partCount) throw new IllegalArgumentException("Invalid part index");
        if (payload == null) throw new IllegalArgumentException("Missing part payload");
        if (payload.length != bytesOf(totalBytes, partIndex)) throw new IllegalArgumentException("Wrong slice length");
        if (publication < 0L) throw new IllegalArgumentException("Negative publication");
        payload = payload.clone();
    }

    /** How many slices a body of this length occupies. */
    public static int partsFor(int totalBytes) {
        if (totalBytes <= 0) return 0;
        return (totalBytes + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES;
    }

    /** How many bytes slice {@code index} of this body holds. */
    public static int bytesOf(int totalBytes, int index) {
        if (index < 0 || index >= partsFor(totalBytes)) return 0;
        int offset = index * MAX_FRAGMENT_BYTES;
        return Math.min(MAX_FRAGMENT_BYTES, totalBytes - offset);
    }

    /** Byte offset of slice {@code index} inside the body. */
    public static int offsetOf(int index) {
        return index * MAX_FRAGMENT_BYTES;
    }

    /**
     * One part, written in one commit.
     *
     * <p>The frame is assembled in a bounded scratch buffer - bounded by this part's own slice plus the
     * header - and only then reserved and copied into the caller's buffer, so a caller never sees a part
     * with half a header or a truncated payload.</p>
     */
    private static void encode(FriendlyByteBuf buffer, BattleStateFragmentPacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing part buffer");
        if (packet == null) throw new IllegalArgumentException("Missing state part");
        int required = MAX_HEADER_BYTES + packet.payload.length;
        ByteBuf scratch = Unpooled.buffer(64, required);
        try {
            FriendlyByteBuf frame = new FriendlyByteBuf(scratch);
            frame.writeVarLong(packet.publication);
            frame.writeVarInt(packet.totalBytes);
            frame.writeVarInt(packet.partCount);
            frame.writeVarInt(packet.partIndex);
            frame.writeBoolean(packet.active);
            frame.writeBoolean(packet.sessionId != null);
            if (packet.sessionId != null) frame.writeUUID(packet.sessionId);
            frame.writeVarLong(packet.membershipServerEpoch);
            frame.writeVarLong(packet.membershipGeneration);
            frame.writeVarInt(packet.payload.length);
            frame.writeBytes(packet.payload);
            int size = scratch.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            if (buffer.maxWritableBytes() < written) {
                throw new IllegalArgumentException("State part of " + written
                        + " bytes does not fit the remaining capacity of the buffer");
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(scratch, scratch.readerIndex(), size);
            } catch (RuntimeException failure) {
                buffer.writerIndex(start);
                throw new IllegalArgumentException("State part could not be written to the buffer", failure);
            }
        } finally {
            scratch.release();
        }
    }

    private static BattleStateFragmentPacket decode(FriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing part buffer");
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("State part length is unreadable", failure);
        }
        if (length < 0 || length > MAX_HEADER_BYTES + MAX_FRAGMENT_BYTES) {
            throw new IllegalArgumentException("State part length is outside the allowed range");
        }
        if (length > buffer.readableBytes()) throw new IllegalArgumentException("State part declares more bytes than remain");
        FriendlyByteBuf frame = new FriendlyByteBuf(buffer.readSlice(length));
        long publication = frame.readVarLong();
        int totalBytes = frame.readVarInt();
        int partCount = frame.readVarInt();
        int partIndex = frame.readVarInt();
        boolean active = frame.readBoolean();
        UUID sessionId = frame.readBoolean() ? frame.readUUID() : null;
        long epoch = frame.readVarLong();
        long generation = frame.readVarLong();
        int size = frame.readVarInt();
        if (size < 0 || size > MAX_FRAGMENT_BYTES) throw new IllegalArgumentException("Invalid slice length");
        if (size > frame.readableBytes()) throw new IllegalArgumentException("Slice declares more bytes than remain");
        byte[] payload = new byte[size];
        frame.readBytes(payload);
        int trailing = frame.readableBytes();
        if (trailing != 0) throw new IllegalArgumentException("State part carries " + trailing + " trailing bytes");
        // The record's own constructor re-checks the layout, so a hand-written part cannot slip through.
        return new BattleStateFragmentPacket(publication, totalBytes, partCount, partIndex, active, payload,
                sessionId, epoch, generation);
    }

    @Override
    public UUID membershipSession() {
        return sessionId;
    }

    @Override
    public BattleStateFragmentPacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new BattleStateFragmentPacket(publication, totalBytes, partCount, partIndex, active, payload,
                sessionId, serverEpoch, generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public byte[] payload() {
        return payload.clone();
    }
}
