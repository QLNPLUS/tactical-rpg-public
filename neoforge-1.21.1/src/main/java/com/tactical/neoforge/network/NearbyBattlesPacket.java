package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The nearby encounters one player may see, with a per-entry reason when joining is refused.
 *
 * <p>Only summaries of encounters in the player's own dimension and close enough to reach are
 * included; nothing about another encounter's units, log or skills is exposed. An empty list is the
 * explicit "nothing nearby" state and never implies a battle was started.</p>
 *
 * <p>The refusal reason is structure, not a finished sentence: the server names the reason (its own
 * translation key plus typed arguments) and each client reads it in its own language. The manager is
 * different on purpose - it is the name a player typed, so it travels as text and is never treated as a
 * key. Four rows at most, so this stays one bounded payload and is never paged.</p>
 */
public record NearbyBattlesPacket(long requestId, List<Entry> entries) implements CustomPacketPayload {
    public static final Type<NearbyBattlesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "nearby_battles"));
    public static final int MAX_ENTRIES = 4;
    public static final int MAX_NAME = 32;
    /**
     * Ceiling of one list body: four rows of a session id, a bounded manager name, three counters, the flag
     * and one component frame each ({@code 4 * (16 + 99 + 15 + 1 + 8197) + 15} = 33 327).
     */
    public static final int MAX_BODY_BYTES = 34 * 1024;
    public static final StreamCodec<RegistryFriendlyByteBuf, NearbyBattlesPacket> STREAM_CODEC = StreamCodec.of(
            NearbyBattlesPacket::encode, NearbyBattlesPacket::decode);

    public NearbyBattlesPacket {
        entries = List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many nearby encounters");
    }

    /** One row: who runs it, how far it is, how full it is, and whether this player may join now. */
    public record Entry(UUID sessionId, String manager, int distance, int members, int capacity,
                        boolean joinable, Component reason) {
        public Entry {
            sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
            manager = bounded(manager, MAX_NAME);
            reason = reason == null ? Component.empty() : reason;
            if (distance < 0 || members < 0 || capacity < 0) throw new IllegalArgumentException("Negative field");
        }

        /** Compatibility form: a caller that still holds finished text keeps it as literal text. */
        public Entry(UUID sessionId, String manager, int distance, int members, int capacity,
                     boolean joinable, String reason) {
            this(sessionId, manager, distance, members, capacity, joinable,
                    reason == null || reason.isEmpty() ? Component.empty() : Component.literal(reason));
        }
    }

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /**
     * The whole list is assembled in one bounded scratch body and committed once.
     *
     * <p>A row's reason is a component frame, and a frame can refuse its own limit; without this the caller
     * would be left with a request id and three rows of a list that never existed. Nothing reaches the
     * caller's buffer until the complete body is known to fit.</p>
     */
    private static void encode(RegistryFriendlyByteBuf buffer, NearbyBattlesPacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing nearby buffer");
        if (packet == null) throw new IllegalArgumentException("Missing nearby list");
        ByteBuf body = Unpooled.buffer(128, MAX_BODY_BYTES);
        try {
            try {
                writeBody(new RegistryFriendlyByteBuf(body, buffer.registryAccess(), buffer.getConnectionType()), packet);
            } catch (RuntimeException refused) {
                throw new IllegalArgumentException("Nearby list does not fit the " + MAX_BODY_BYTES
                        + "-byte body budget", refused);
            }
            int size = body.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            if (buffer.maxWritableBytes() < written) {
                throw new IllegalArgumentException("Nearby list of " + written
                        + " bytes does not fit the remaining capacity of the buffer");
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(body, body.readerIndex(), size);
            } catch (RuntimeException failure) {
                buffer.writerIndex(start);
                throw new IllegalArgumentException("Nearby list could not be written to the buffer", failure);
            }
        } finally {
            body.release();
        }
    }

    private static void writeBody(RegistryFriendlyByteBuf body, NearbyBattlesPacket packet) {
        body.writeVarLong(packet.requestId());
        body.writeVarInt(packet.entries().size());
        for (Entry entry : packet.entries()) {
            body.writeUUID(entry.sessionId());
            body.writeUtf(entry.manager(), MAX_NAME);
            body.writeVarInt(entry.distance());
            body.writeVarInt(entry.members());
            body.writeVarInt(entry.capacity());
            body.writeBoolean(entry.joinable());
            ComponentIO.write(body, entry.reason());
        }
    }

    private static NearbyBattlesPacket decode(RegistryFriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing nearby buffer");
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Nearby list length is unreadable", failure);
        }
        if (length < 0 || length > MAX_BODY_BYTES) throw new IllegalArgumentException("Nearby list exceeds the body budget");
        if (length > buffer.readableBytes()) throw new IllegalArgumentException("Nearby list declares more bytes than remain");
        RegistryFriendlyByteBuf body = new RegistryFriendlyByteBuf(buffer.readSlice(length),
                buffer.registryAccess(), buffer.getConnectionType());
        long requestId = body.readVarLong();
        int count = body.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid entry count");
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(body.readUUID(), body.readUtf(MAX_NAME), body.readVarInt(),
                    body.readVarInt(), body.readVarInt(), body.readBoolean(),
                    ComponentIO.read(body)));
        }
        int trailing = body.readableBytes();
        if (trailing != 0) throw new IllegalArgumentException("Nearby list carries " + trailing + " trailing bytes");
        return new NearbyBattlesPacket(requestId, entries);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
