package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
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
 */
public record NearbyBattlesPacket(long requestId, List<Entry> entries) implements CustomPacketPayload {
    public static final Type<NearbyBattlesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "nearby_battles"));
    public static final int MAX_ENTRIES = 4;
    public static final int MAX_NAME = 32;
    public static final int MAX_REASON = 48;
    public static final StreamCodec<FriendlyByteBuf, NearbyBattlesPacket> STREAM_CODEC = StreamCodec.of(
            NearbyBattlesPacket::encode, NearbyBattlesPacket::decode);

    public NearbyBattlesPacket {
        entries = List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many nearby encounters");
    }

    /** One row: who runs it, how far it is, how full it is, and whether this player may join now. */
    public record Entry(UUID sessionId, String manager, int distance, int members, int capacity,
                        boolean joinable, String reason) {
        public Entry {
            sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
            manager = bounded(manager, MAX_NAME);
            reason = bounded(reason, MAX_REASON);
            if (distance < 0 || members < 0 || capacity < 0) throw new IllegalArgumentException("Negative field");
        }
    }

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static void encode(FriendlyByteBuf buffer, NearbyBattlesPacket packet) {
        buffer.writeVarLong(packet.requestId());
        buffer.writeVarInt(packet.entries().size());
        for (Entry entry : packet.entries()) {
            buffer.writeUUID(entry.sessionId());
            buffer.writeUtf(entry.manager(), MAX_NAME);
            buffer.writeVarInt(entry.distance());
            buffer.writeVarInt(entry.members());
            buffer.writeVarInt(entry.capacity());
            buffer.writeBoolean(entry.joinable());
            buffer.writeUtf(entry.reason(), MAX_REASON);
        }
    }

    private static NearbyBattlesPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid entry count");
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buffer.readUUID(), buffer.readUtf(MAX_NAME), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readUtf(MAX_REASON)));
        }
        return new NearbyBattlesPacket(requestId, entries);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
