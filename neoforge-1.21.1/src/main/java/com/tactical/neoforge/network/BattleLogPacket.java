package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-authored ordered journal snapshot; no client-to-server log packet exists. */
public record BattleLogPacket(UUID battleId, int lifecycle, long sequence, List<Entry> history,
                              long membershipServerEpoch, long membershipGeneration)
        implements CustomPacketPayload, MemberScoped {
    public static final int START = 0;
    public static final int UPDATE = 1;
    public static final int FINISH = 2;
    public static final int CLEAR = 3;
    public static final int MAX_HISTORY = 64;
    public static final int MAX_CATEGORY = 24;
    public static final int MAX_TEXT = 240;
    public static final Type<BattleLogPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "battle_log"));
    public static final StreamCodec<FriendlyByteBuf, BattleLogPacket> STREAM_CODEC =
            StreamCodec.of(BattleLogPacket::encode, BattleLogPacket::decode);

    /** Unstamped snapshot; the network layer stamps it for each recipient. */
    public BattleLogPacket(UUID battleId, int lifecycle, long sequence, List<Entry> history) {
        this(battleId, lifecycle, sequence, history, 0L, 0L);
    }

    @Override
    public UUID membershipSession() {
        return battleId;
    }

    @Override
    public BattleLogPacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new BattleLogPacket(battleId, lifecycle, sequence, history, serverEpoch, generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    public BattleLogPacket {
        Objects.requireNonNull(battleId, "battleId");
        history = List.copyOf(history);
        if (lifecycle < START || lifecycle > CLEAR || sequence < 0 || history.size() > MAX_HISTORY) {
            throw new IllegalArgumentException("Invalid journal snapshot");
        }
        long previous = 0;
        for (Entry entry : history) {
            if (entry.sequence() <= previous || entry.sequence() > sequence) {
                throw new IllegalArgumentException("Unordered journal history");
            }
            previous = entry.sequence();
        }
        if (!history.isEmpty() && previous != sequence) {
            throw new IllegalArgumentException("Journal snapshot does not include its latest event");
        }
    }

    public record Entry(long sequence, String category, String text) {
        public Entry {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(text, "text");
            if (sequence <= 0 || category.length() > MAX_CATEGORY || text.length() > MAX_TEXT) {
                throw new IllegalArgumentException("Invalid journal entry");
            }
        }
    }

    private static void encode(FriendlyByteBuf buffer, BattleLogPacket packet) {
        buffer.writeUUID(packet.battleId);
        buffer.writeVarInt(packet.lifecycle);
        buffer.writeVarLong(packet.sequence);
        buffer.writeVarInt(packet.history.size());
        for (Entry entry : packet.history) {
            buffer.writeVarLong(entry.sequence());
            buffer.writeUtf(entry.category(), MAX_CATEGORY);
            buffer.writeUtf(entry.text(), MAX_TEXT);
        }
        // Membership stamp of the recipient this snapshot was sent to.
        buffer.writeVarLong(packet.membershipServerEpoch);
        buffer.writeVarLong(packet.membershipGeneration);
    }

    private static BattleLogPacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        int lifecycle = buffer.readVarInt();
        long sequence = buffer.readVarLong();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_HISTORY) throw new IllegalArgumentException("Invalid journal history size");
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buffer.readVarLong(), buffer.readUtf(MAX_CATEGORY), buffer.readUtf(MAX_TEXT)));
        }
        long membershipServerEpoch = buffer.readVarLong();
        long membershipGeneration = buffer.readVarLong();
        return new BattleLogPacket(id, lifecycle, sequence, entries, membershipServerEpoch, membershipGeneration);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
