package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authored ordered journal snapshot; no client-to-server log packet exists.
 *
 * <p>Each row carries a native {@link Component}, not a finished sentence: the encounter is authored on
 * the server, but a row is read by every member in that member's own language, so only the display point
 * may resolve it. The text frame comes from {@link ComponentIO}, which is also where the per-row wire
 * limit lives; this class adds the packet-wide budget and the ordering/lifecycle rules.</p>
 *
 * <p>Wire layout (the packet body, framed by the game's own packet framing):</p>
 * <pre>
 *   VarInt  bodyLength          // the number of bytes the rest of this layout occupies
 *   UUID    battleId
 *   VarInt  lifecycle
 *   VarLong sequence
 *   VarInt  historySize         // 0..MAX_HISTORY
 *   repeat historySize times:
 *     VarLong sequence
 *     VarInt + UTF-8 bytes  category   (at most MAX_CATEGORY characters)
 *     ComponentIO frame                (VarInt length + native component payload)
 *   VarLong membershipServerEpoch
 *   VarLong membershipGeneration
 * </pre>
 *
 * <p>The declared body length is the packet's own total budget: it is checked against
 * {@link #MAX_BODY_BYTES} before anything is allocated or decoded, and every field above counts against
 * it, including the frame prefixes, categories, sequences, header and membership stamp - not only the
 * component payloads. Encoding builds the body in a scratch buffer capped at that same budget, so an
 * over-sized snapshot is refused instead of being half-written to the connection.</p>
 */
public record BattleLogPacket(UUID battleId, int lifecycle, long sequence, List<Entry> history,
                              long membershipServerEpoch, long membershipGeneration)
        implements CustomPacketPayload, MemberScoped {
    public static final int START = 0;
    public static final int UPDATE = 1;
    public static final int FINISH = 2;
    public static final int CLEAR = 3;
    public static final int MAX_HISTORY = 64;
    public static final int MAX_CATEGORY = 24;
    /**
     * Character limit of {@link #Entry#Entry(long, String, String)} only. A component row is bounded by
     * the wire frame ({@link ComponentIO#MAX_FRAME_BYTES}) and by {@link #MAX_BODY_BYTES}, not by this
     * number: it counts characters of one literal, while the frame counts encoded bytes of a structure.
     */
    public static final int MAX_TEXT = 240;
    /**
     * Whole-body budget of one snapshot, in wire bytes, covering every field of the layout above.
     *
     * <p>It bounds the <em>body</em>, i.e. everything the leading {@code bodyLength} counts. The VarInt
     * length prefix itself (two bytes for every body this budget admits) is outside the budget, and the
     * game's own packet framing is outside the encoder entirely.</p>
     *
     * <p>It is bounded on purpose above one legitimate worst case (64 rows each near the
     * {@link ComponentIO#MAX_FRAME_BYTES} frame limit plus their prefixes and categories, measured at
     * 512857 bytes) and far below the transport limits of this installation: a clientbound custom payload
     * is capped at 1 MiB ({@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}), a compressed frame at
     * 2 MiB and an uncompressed frame at 8 MiB. An over-budget snapshot is refused locally instead of
     * being killed by the encoder far away from its cause.</p>
     */
    public static final int MAX_BODY_BYTES = 600 * 1024;
    /**
     * Capacity of the scratch buffer used to measure one row: the payload limit plus the largest VarInt
     * length prefix that can precede it. The payload limit itself stays
     * {@link ComponentIO#MAX_FRAME_BYTES}, so a row of exactly that payload is measurable rather than
     * rejected by its own prefix.
     */
    private static final int MAX_FRAME_WIRE_BYTES = ComponentIO.MAX_FRAME_BYTES + 5;
    public static final Type<BattleLogPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "battle_log"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleLogPacket> STREAM_CODEC =
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

    /**
     * One journal row.
     *
     * <p>{@code text} is a component so that the row reaches the client unresolved. The three-argument
     * string form below is kept for callers that only have finished text: it is read as a
     * <em>literal</em>, never as a translation key, so a name or a message that merely looks like a key
     * cannot be looked up.</p>
     */
    public record Entry(long sequence, String category, Component text) {
        public Entry {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(text, "text");
            if (sequence <= 0 || category.length() > MAX_CATEGORY) {
                throw new IllegalArgumentException("Invalid journal entry");
            }
        }

        /** Compatibility form: {@code text} is literal content, not a translation key. */
        public Entry(long sequence, String category, String text) {
            this(sequence, category, literal(sequence, text));
        }

        private static Component literal(long sequence, String text) {
            Objects.requireNonNull(text, "text");
            if (sequence <= 0 || text.length() > MAX_TEXT) {
                throw new IllegalArgumentException("Invalid journal entry");
            }
            return Component.literal(text);
        }
    }

    /**
     * The row as it would actually travel, or {@code null} when it cannot.
     *
     * <p>This is the single accepted snapshot for a journal row, and it does three jobs at once by
     * running the real frame writer <em>and</em> the real frame reader on the caller's registry context:</p>
     * <ul>
     *   <li><b>Deep snapshot.</b> The result is a fresh structure decoded from the bytes, so siblings are
     *       kept, nested translatable arguments are kept, and a producer that later mutates the component
     *       it passed in can no longer rewrite a row that is already in the history. Nothing is resolved:
     *       a translation key is still a key.</li>
     *   <li><b>Sender limit.</b> The encode side enforces {@link ComponentIO#MAX_FRAME_BYTES} payload
     *       bytes; a row of exactly that payload fits, because the probe buffer reserves the prefix too.</li>
     *   <li><b>Receiver limit.</b> The decode side re-reads those same bytes under
     *       {@link ComponentIO#MAX_NBT_BYTES}. Encoding successfully is not enough: a structure can stay
     *       inside the wire limit and still need more heap than a client will grant, and that row would
     *       otherwise be stored once and then handed to every member in a snapshot none of them can decode.
     *       Only a value that survives both passes is accepted.</li>
     * </ul>
     *
     * <p>Without a registry context nothing can be measured or copied deeply, so the answer is
     * {@code null}: a caller must refuse the row rather than keep a mutable component around for a later
     * send.</p>
     */
    public static Component verifiedSnapshot(Component text, RegistryAccess registries) {
        if (text == null || registries == null) return null;
        RegistryFriendlyByteBuf probe = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, MAX_FRAME_WIRE_BYTES), registries, ConnectionType.OTHER);
        try {
            ComponentIO.write(probe, text);
            // The read is the point: it enforces the receiver's heap quota and yields the deep copy.
            return ComponentIO.read(probe);
        } catch (RuntimeException refused) {
            return null;
        } finally {
            probe.release();
        }
    }

    /** Whether {@link #verifiedSnapshot} accepts this row: both limits, not only the encode side. */
    public static boolean fitsComponentFrame(Component text, RegistryAccess registries) {
        return verifiedSnapshot(text, registries) != null;
    }

    private static void encode(RegistryFriendlyByteBuf buffer, BattleLogPacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing journal buffer");
        if (packet == null) throw new IllegalArgumentException("Missing journal snapshot");
        ByteBuf body = Unpooled.buffer(256, MAX_BODY_BYTES);
        try {
            try {
                writeBody(new RegistryFriendlyByteBuf(body, buffer.registryAccess(), buffer.getConnectionType()),
                        packet);
            } catch (RuntimeException failure) {
                // Either the component frame refused its own limit or the body outgrew the packet budget:
                // in both cases nothing of this snapshot may reach the connection.
                throw new IllegalArgumentException("Journal snapshot does not fit the "
                        + MAX_BODY_BYTES + "-byte packet budget", failure);
            }
            int size = body.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            if (buffer.maxWritableBytes() < written) {
                // Reserve the whole packet first, so a too-small destination is refused before its first
                // byte instead of leaving a length prefix without a body.
                throw new IllegalArgumentException("Journal snapshot of " + written
                        + " bytes does not fit the remaining capacity of the buffer");
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(body, body.readerIndex(), size);
            } catch (RuntimeException failure) {
                buffer.writerIndex(start);
                throw new IllegalArgumentException("Journal snapshot could not be written to the buffer", failure);
            }
        } finally {
            body.release();
        }
    }

    private static void writeBody(RegistryFriendlyByteBuf body, BattleLogPacket packet) {
        body.writeUUID(packet.battleId);
        body.writeVarInt(packet.lifecycle);
        body.writeVarLong(packet.sequence);
        body.writeVarInt(packet.history.size());
        for (Entry entry : packet.history) {
            body.writeVarLong(entry.sequence());
            body.writeUtf(entry.category(), MAX_CATEGORY);
            ComponentIO.write(body, entry.text());
        }
        // Membership stamp of the recipient this snapshot was sent to.
        body.writeVarLong(packet.membershipServerEpoch);
        body.writeVarLong(packet.membershipGeneration);
    }

    private static BattleLogPacket decode(RegistryFriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing journal buffer");
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Journal body length is unreadable", failure);
        }
        // Refuse a hostile or corrupted declaration before allocating anything for it.
        if (length < 0) throw new IllegalArgumentException("Negative journal body length " + length);
        if (length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Journal body of " + length + " bytes exceeds the "
                    + MAX_BODY_BYTES + "-byte budget");
        }
        int available = buffer.readableBytes();
        if (length > available) {
            throw new IllegalArgumentException("Journal body declares " + length + " bytes but only "
                    + available + " remain");
        }
        RegistryFriendlyByteBuf body = new RegistryFriendlyByteBuf(buffer.readSlice(length),
                buffer.registryAccess(), buffer.getConnectionType());
        UUID battleId = body.readUUID();
        int lifecycle = body.readVarInt();
        long sequence = body.readVarLong();
        int size = body.readVarInt();
        if (size < 0 || size > MAX_HISTORY) throw new IllegalArgumentException("Invalid journal history size");
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long entrySequence = body.readVarLong();
            String category = body.readUtf(MAX_CATEGORY);
            entries.add(new Entry(entrySequence, category, ComponentIO.read(body)));
        }
        long membershipServerEpoch = body.readVarLong();
        long membershipGeneration = body.readVarLong();
        int trailing = body.readableBytes();
        if (trailing != 0) throw new IllegalArgumentException("Journal body carries " + trailing + " trailing bytes");
        return new BattleLogPacket(battleId, lifecycle, sequence, entries, membershipServerEpoch,
                membershipGeneration);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
