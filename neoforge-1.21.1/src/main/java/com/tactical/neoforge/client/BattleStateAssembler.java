package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleStateFragmentPacket;
import com.tactical.neoforge.network.BattleStatePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.BitSet;
import java.util.Objects;

/**
 * Reassembles the bounded parts of one battle snapshot, then applies it once.
 *
 * <p>A snapshot is never applied in pieces: the client mirror is only touched with a complete, decoded
 * snapshot, so a half-received body can never show one publication's AP next to another's roster.</p>
 *
 * <p>Four kinds of check stand between a part and an allocation, in this order:</p>
 * <ol>
 *   <li><b>Membership</b> - {@link BattleMembership#preview} answers what this client would do with the
 *       part's stamp <em>without changing anything</em>. A part of a membership this client already
 *       replaced, of a closed connection, of another server instance, or one that contradicts the current
 *       membership is refused before a byte is held. The transition itself still only happens when the
 *       complete snapshot is applied.</li>
 *   <li><b>Ordering</b> - only a publication newer than the last settled one is accepted. A settled
 *       publication is one that was applied <em>or</em> abandoned, so a replay of a finished snapshot and
 *       a publication whose parts contradicted each other can never come back.</li>
 *   <li><b>Header agreement</b> - total length, part count, enter/leave flag, session and both stamps must
 *       match the parts already held; a contradicting part drops that whole publication instead of being
 *       joined into a corrupted body.</li>
 *   <li><b>Body agreement</b> - the decoded snapshot must itself carry the identity the parts claimed
 *       (session, server epoch, generation and the enter/leave flag). A body that disagrees is decoded but
 *       never applied.</li>
 * </ol>
 *
 * <p>At most one snapshot is held, and the seam it hands a finished snapshot to answers whether the client
 * really accepted it: "a complete body was decoded" and "the client applied it" are reported separately
 * ({@link Result}). Everything is dropped on every life-cycle reset ({@link #reset()}).</p>
 *
 * <p>Main client thread only, like the rest of the client mirror.</p>
 */
public final class BattleStateAssembler {

    /** Decoding seam, so the ordering and clean-up rules can be driven without a booted component codec. */
    public interface Decoder {
        BattleStatePacket decode(byte[] wire, int length);
    }

    /** Where a finished snapshot goes; the answer is whether the client accepted it. */
    public interface Sink {
        boolean apply(BattleStatePacket snapshot);
    }

    /** What happened to one part. */
    public enum Result {
        /** The part completed a snapshot and the client accepted it. */
        APPLIED,
        /** The part completed a snapshot that was decoded, but the client did not take it. */
        DECODED_NOT_APPLIED,
        /** The part belongs to the snapshot being received; more are needed. */
        INCOMPLETE,
        /** The part was refused before anything was held for it. */
        REFUSED
    }

    private static final Decoder LIVE_DECODER = (wire, length) -> {
        var level = Minecraft.getInstance() == null ? null : Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("no client level to decode with");
        return BattleStatePacket.fromWireBytes(level.registryAccess(), ConnectionType.OTHER, wire, length);
    };

    private static final Sink LIVE_SINK = BattleClientLifecycle::accept;

    private static Decoder decoder = LIVE_DECODER;
    private static Sink sink = LIVE_SINK;

    private static Pending pending;
    private static long settledEpoch = Long.MIN_VALUE;
    private static long settledPublication = Long.MIN_VALUE;

    private BattleStateAssembler() { }

    /** One snapshot being received. */
    private static final class Pending {
        final long epoch;
        final long publication;
        final int totalBytes;
        final int partCount;
        final boolean active;
        final java.util.UUID sessionId;
        final long generation;
        final byte[] body;
        final BitSet received;
        int receivedCount;

        Pending(BattleStateFragmentPacket part) {
            this.epoch = part.membershipServerEpoch();
            this.publication = part.publication();
            this.totalBytes = part.totalBytes();
            this.partCount = part.partCount();
            this.active = part.active();
            this.sessionId = part.sessionId();
            this.generation = part.membershipGeneration();
            this.body = new byte[totalBytes];
            this.received = new BitSet(partCount);
        }

        boolean sameHead(BattleStateFragmentPacket part) {
            return totalBytes == part.totalBytes()
                    && partCount == part.partCount()
                    && active == part.active()
                    && generation == part.membershipGeneration()
                    && Objects.equals(sessionId, part.sessionId());
        }
    }

    /** Install a decoding seam ({@code null} restores the real client codec). */
    public static void decoderForTesting(Decoder replacement) {
        decoder = replacement == null ? LIVE_DECODER : replacement;
    }

    /** Install a completion seam ({@code null} restores the real client application point). */
    public static void sinkForTesting(Sink replacement) {
        sink = replacement == null ? LIVE_SINK : replacement;
    }

    /** Drop an incomplete body without forgetting publications already settled on this connection. */
    public static void discardPending() {
        pending = null;
    }

    /** Forget this connection's held bytes and ordering, on disconnect/reconnect or fixture reset. */
    public static void reset() {
        discardPending();
        settledEpoch = Long.MIN_VALUE;
        settledPublication = Long.MIN_VALUE;
    }

    /** How many parts of the snapshot currently being received have arrived (diagnostics and tests). */
    public static int heldParts() {
        return pending == null ? 0 : pending.receivedCount;
    }

    /** True while a partially received snapshot is held. */
    public static boolean holding() {
        return pending != null;
    }

    /** The publication currently being received, or {@code -1} when nothing is held. */
    public static long heldPublication() {
        return pending == null ? -1L : pending.publication;
    }

    /** Accept one part. */
    public static Result accept(BattleStateFragmentPacket part) {
        if (part == null) return Result.REFUSED;
        BattleMembership.Decision membership = BattleMembership.preview(part.membershipServerEpoch(),
                part.membershipGeneration(), part.sessionId(), part.active());
        if (!BattleMembership.decided(membership)) return Result.REFUSED;
        if (!isNewer(part.membershipServerEpoch(), part.publication(), settledEpoch, settledPublication)) {
            // Settled: already applied, or abandoned because its parts disagreed.
            return Result.REFUSED;
        }
        if (pending == null) {
            pending = new Pending(part);
        } else if (isNewer(part.membershipServerEpoch(), part.publication(), pending.epoch, pending.publication)) {
            // A newer snapshot started: the older partial one is abandoned for good.
            settle(pending);
            pending = new Pending(part);
        } else if (part.publication() != pending.publication
                || part.membershipServerEpoch() != pending.epoch) {
            return Result.REFUSED;
        } else if (!pending.sameHead(part)) {
            // Parts that disagree about which snapshot they belong to cannot be joined: drop the whole
            // publication so that later parts of it cannot revive it.
            Tactical.LOGGER.warn("[Tactical] battle state parts disagreed; the snapshot was dropped");
            settle(pending);
            pending = null;
            return Result.REFUSED;
        }
        Pending held = pending;
        if (held.received.get(part.partIndex())) return Result.REFUSED;
        byte[] slice = part.payload();
        System.arraycopy(slice, 0, held.body, BattleStateFragmentPacket.offsetOf(part.partIndex()), slice.length);
        held.received.set(part.partIndex());
        held.receivedCount++;
        if (held.receivedCount < held.partCount) return Result.INCOMPLETE;

        pending = null;
        settle(held);
        BattleStatePacket snapshot;
        try {
            snapshot = decoder.decode(held.body, held.totalBytes);
        } catch (RuntimeException refused) {
            Tactical.LOGGER.warn("[Tactical] assembled battle state did not decode: {}", refused.toString());
            return Result.REFUSED;
        }
        if (!sameIdentity(held, snapshot)) {
            // The body claims another encounter, server instance, generation or enter/leave than the parts
            // did: it is decoded, and it is not applied.
            Tactical.LOGGER.warn("[Tactical] assembled battle state disagreed with its parts; it was not applied");
            return Result.DECODED_NOT_APPLIED;
        }
        return sink.apply(snapshot) ? Result.APPLIED : Result.DECODED_NOT_APPLIED;
    }

    private static boolean sameIdentity(Pending held, BattleStatePacket snapshot) {
        return snapshot != null
                && snapshot.active() == held.active
                && snapshot.membershipServerEpoch() == held.epoch
                && snapshot.membershipGeneration() == held.generation
                && Objects.equals(snapshot.sessionId(), held.sessionId);
    }

    private static void settle(Pending held) {
        if (!isNewer(held.epoch, held.publication, settledEpoch, settledPublication)) return;
        settledEpoch = held.epoch;
        settledPublication = held.publication;
    }

    /** Lexicographic comparison of (server epoch, publication): the server instance comes first. */
    private static boolean isNewer(long epoch, long publication, long thanEpoch, long thanPublication) {
        if (epoch != thanEpoch) return epoch > thanEpoch;
        return publication > thanPublication;
    }
}
