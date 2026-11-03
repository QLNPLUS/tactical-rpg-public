package com.tactical.neoforge.client;

import com.tactical.neoforge.network.BattleStateFragmentPacket;
import com.tactical.neoforge.network.BattleStatePacket;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide which parts may be joined, and when held bytes must be dropped.
 *
 * <p>The decoding itself is the real body codec and only exists in a booted game, so the byte-level half is
 * covered by the {@code battle_state_wire} fixture, which runs real encoded snapshots through this very
 * class. Here the decoder and the sink are recording seams: what is pinned is the membership preview, the
 * ordering, the header/body binding, the duplicate, and the life-cycle reset that must leave nothing behind
 * for a later part to join with.</p>
 */
class BattleStateAssemblerTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final long EPOCH = 77L;
    private static final long GENERATION = 3L;

    /** Every body the assembler decoded, in order. */
    private final List<byte[]> decoded = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    /** Every snapshot the sink would have applied. */
    private final List<BattleStatePacket> applied = new ArrayList<>();
    private boolean sinkAccepts = true;

    @BeforeEach
    void installSeams() {
        decoded.clear();
        lengths.clear();
        applied.clear();
        sinkAccepts = true;
        BattleMembership.reset();
        BattleStateAssembler.reset();
        BattleStateAssembler.decoderForTesting((wire, length) -> {
            decoded.add(java.util.Arrays.copyOf(wire, length));
            lengths.add(length);
            return snapshot(GENERATION, length);
        });
        BattleStateAssembler.sinkForTesting(snapshot -> {
            if (!sinkAccepts) return false;
            applied.add(snapshot);
            return true;
        });
        BattleClientLifecycle.environmentForTesting(new BattleClientLifecycle.Environment() {
            @Override public boolean ready() { return true; }
            @Override public boolean alive() { return true; }
            @Override public int selfEntityId() { return 72; }
            @Override public void teardownUi() { }
            @Override public void refreshActionLabels() { }
        });
    }

    @AfterEach
    void restoreSeams() {
        BattleStateAssembler.decoderForTesting(null);
        BattleStateAssembler.sinkForTesting(null);
        BattleStateAssembler.reset();
        BattleMembership.reset();
        BattleClientLifecycle.environmentForTesting(null);
        BattleClientState.clear();
    }

    private static BattleStatePacket snapshot(long generation, int size) {
        return new BattleStatePacket(true, 2, 1, size, 150, 10, 0, 99, 0, new int[]{0, 99, 0},
                Component.literal("actor"), List.of(Component.literal("actor")), 0,
                Component.literal("bow"), 0, 0, 0, 99, 0, 24, false, 72, new int[]{72, 20, 20},
                new int[]{10}, 1, false, new int[]{72}, 10, 8, 10, 10, 50, 0, false,SESSION, 1L, EPOCH, generation);
    }

    private static BattleStateFragmentPacket part(long publication, int totalBytes, int index, long epoch,
                                                  long generation) {
        int parts = BattleStateFragmentPacket.partsFor(totalBytes);
        int offset = BattleStateFragmentPacket.offsetOf(index);
        int length = BattleStateFragmentPacket.bytesOf(totalBytes, index);
        byte[] slice = new byte[length];
        for (int i = 0; i < length; i++) slice[i] = (byte) ((offset + i) & 0x7F);
        return new BattleStateFragmentPacket(publication, totalBytes, parts, index, true, slice, SESSION, epoch,
                generation);
    }

    private static BattleStateFragmentPacket part(long publication, int totalBytes, int index) {
        return part(publication, totalBytes, index, EPOCH, GENERATION);
    }

    @Test
    void aScopedExitKeepsTheSettledPublicationWatermark() {
        BattleStatePacket exit = BattleStatePacket.inactive(SESSION).withMembership(EPOCH, GENERATION + 1);
        BattleStateAssembler.decoderForTesting((wire, length) -> {
            decoded.add(java.util.Arrays.copyOf(wire, length));
            return exit;
        });
        BattleStateAssembler.sinkForTesting(BattleClientLifecycle::accept);
        BattleStateFragmentPacket part = new BattleStateFragmentPacket(100L, 2, 1, 0,
                false, new byte[2], SESSION, EPOCH, GENERATION + 1);

        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part));
        assertFalse(BattleStateAssembler.holding());
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part));
        assertEquals(1, decoded.size(), "a scoped UI teardown must not reopen an already settled exit");
    }

    @Test
    void aSingleSliceSnapshotIsAppliedOnce() {
        assertEquals(BattleStateAssembler.Result.APPLIED,
                BattleStateAssembler.accept(part(5L, 3, 0)), "one slice is a complete snapshot");
        assertEquals(1, decoded.size());
        assertEquals(1, applied.size());
        assertEquals(3, lengths.get(0));
        assertEquals(0, BattleStateAssembler.heldParts());
        assertFalse(BattleStateAssembler.holding());
    }

    @Test
    void anIncompleteSnapshotIsHeldAndNeverApplied() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 10;
        assertEquals(BattleStateAssembler.Result.INCOMPLETE, BattleStateAssembler.accept(part(6L, body, 0)),
                "one of two slices is not a snapshot");
        assertTrue(BattleStateAssembler.holding());
        assertEquals(1, BattleStateAssembler.heldParts());
        assertTrue(decoded.isEmpty(), "nothing was decoded from a partial body");
        assertTrue(applied.isEmpty(), "and nothing was applied");
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(6L, body, 1)),
                "the second slice completes it");
        assertEquals(body, lengths.get(0));
    }

    @Test
    void partsOfOneSnapshotAreJoinedInTheirOwnRange() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 4;
        BattleStateAssembler.accept(part(9L, body, 0));
        BattleStateAssembler.accept(part(9L, body, 1));
        byte[] joined = decoded.get(0);
        assertEquals(body, joined.length);
        // The slice fill pattern proves each part landed at its own offset, not on top of the other.
        assertEquals((byte) 0, joined[0]);
        assertEquals((byte) (BattleStateFragmentPacket.MAX_FRAGMENT_BYTES & 0x7F),
                joined[BattleStateFragmentPacket.MAX_FRAGMENT_BYTES]);
    }

    @Test
    void aDuplicateSliceCarriesNothingNew() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;
        BattleStateAssembler.accept(part(4L, body, 0));
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part(4L, body, 0)),
                "the same index twice is refused");
        assertEquals(1, BattleStateAssembler.heldParts());
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(4L, body, 1)));
        assertEquals(1, applied.size());
    }

    // ---- what the membership preview decides ------------------------------------------------

    @Test
    void aStaleOrConflictingMembershipIsRefusedBeforeAnythingIsHeld() {
        // Latch a later generation, exactly as an earlier snapshot would have.
        BattleMembership.transition(EPOCH, GENERATION, SESSION, true);
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;

        assertEquals(BattleStateAssembler.Result.REFUSED,
                BattleStateAssembler.accept(part(50L, body, 0, EPOCH, GENERATION - 1)),
                "an older generation with a bigger publication is refused");
        assertFalse(BattleStateAssembler.holding(), "nothing was allocated for it");

        assertEquals(BattleStateAssembler.Result.REFUSED,
                BattleStateAssembler.accept(new BattleStateFragmentPacket(51L, body,
                        BattleStateFragmentPacket.partsFor(body), 0, true,
                        new byte[BattleStateFragmentPacket.bytesOf(body, 0)], UUID.randomUUID(), EPOCH, GENERATION)),
                "the same generation claiming another encounter is a contradiction");
        assertFalse(BattleStateAssembler.holding());

        assertEquals(BattleStateAssembler.Result.REFUSED,
                BattleStateAssembler.accept(part(52L, body, 0, EPOCH + 1, GENERATION + 1)),
                "another server instance's stamp cannot take over a latched membership");
        assertFalse(BattleStateAssembler.holding());
    }

    @Test
    void aPartOfAClosedConnectionIsRefused() {
        assertTrue(BattleMembership.decided(BattleMembership.transition(EPOCH, GENERATION, SESSION, true)));
        BattleMembership.beginConnection(); // the connection that carried that membership is gone
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;
        assertEquals(BattleStateAssembler.Result.REFUSED,
                BattleStateAssembler.accept(part(60L, body, 0, EPOCH, GENERATION)),
                "a part of the closed connection's generation is refused before any allocation");
        assertFalse(BattleStateAssembler.holding());
    }

    @Test
    void aStalePublicationIsRefusedAfterOneWasApplied() {
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(20L, 4, 0)));
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part(19L, 4, 0)),
                "an older publication is refused");
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part(20L, 4, 0)),
                "a full replay of the settled publication is refused, not applied again");
        assertEquals(1, applied.size(), "the replay never reached the client");
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(21L, 4, 0)),
                "a newer publication still applies");
        assertEquals(2, applied.size());
    }

    @Test
    void aStalePublicationIsRefusedWhileANewerOneIsAssembling() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;
        BattleStateAssembler.accept(part(30L, body, 0));
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part(29L, body, 1)),
                "an older publication cannot join it");
        assertEquals(1, BattleStateAssembler.heldParts());
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(30L, body, 1)));
        assertEquals(1, applied.size());
    }

    // ---- what the parts and the body must agree on ------------------------------------------

    @Test
    void aPartOfAnotherSnapshotIsNotMixedInAndThatPublicationStaysDead() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;
        BattleStateAssembler.accept(part(40L, body, 0));
        // The same publication with a different total length is another body: joining them would corrupt it.
        BattleStateFragmentPacket stranger = new BattleStateFragmentPacket(40L, body + 1, 2, 1, true,
                new byte[2], SESSION, EPOCH, GENERATION);
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(stranger),
                "a part that disagrees about the body is refused");
        assertFalse(BattleStateAssembler.holding(), "the whole publication is dropped instead of mixed");
        assertTrue(applied.isEmpty());
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part(40L, body, 1)),
                "the dropped publication cannot be revived by its remaining part");
        assertEquals(BattleStateAssembler.Result.INCOMPLETE, BattleStateAssembler.accept(part(41L, body, 0)),
                "a newer publication still starts cleanly");
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(41L, body, 1)));
    }

    @Test
    void aPartOfAnotherMembershipIsNotMixedIn() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;
        BattleStateAssembler.accept(part(50L, body, 0));
        // Another generation is another snapshot's stamp: it cannot complete the held one, and the two are
        // never joined - the publication is dropped instead of being assembled from disagreeing parts.
        BattleStateFragmentPacket otherGeneration = new BattleStateFragmentPacket(50L, body, 2, 1, true,
                new byte[1], SESSION, EPOCH, GENERATION + 1);
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(otherGeneration),
                "another membership's stamp cannot complete it");
        assertFalse(BattleStateAssembler.holding(), "the disagreeing publication is dropped");
        assertTrue(applied.isEmpty());
    }

    @Test
    void aBodyThatDisagreesWithItsPartsIsDecodedButNotApplied() {
        // The header claims the current membership; the decoded body claims another generation.
        boolean[] decodedOnce = {false};
        BattleStateAssembler.decoderForTesting((wire, length) -> {
            decodedOnce[0] = true;
            return snapshot(GENERATION - 1, length);
        });
        assertEquals(BattleStateAssembler.Result.DECODED_NOT_APPLIED, BattleStateAssembler.accept(part(70L, 3, 0)),
                "a body that contradicts its parts is never applied");
        assertTrue(decodedOnce[0], "it was decoded");
        assertTrue(applied.isEmpty(), "but the client never received it");
    }

    @Test
    void aBodyThatDoesNotDecodeIsDroppedInsteadOfApplied() {
        BattleStateAssembler.decoderForTesting((wire, length) -> {
            throw new IllegalArgumentException("not a state body");
        });
        assertEquals(BattleStateAssembler.Result.REFUSED, BattleStateAssembler.accept(part(71L, 2, 0)),
                "a refused body applies nothing");
        assertFalse(BattleStateAssembler.holding());
    }

    @Test
    void aClientThatRefusesTheSnapshotIsNotReportedAsApplied() {
        sinkAccepts = false;
        assertEquals(BattleStateAssembler.Result.DECODED_NOT_APPLIED, BattleStateAssembler.accept(part(80L, 2, 0)),
                "a decoded body the client refuses is not reported as applied");
        assertTrue(applied.isEmpty());
    }

    @Test
    void aLifeCycleResetDropsTheHeldBytesAndTheOrdering() {
        int body = BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1;
        BattleStateAssembler.accept(part(90L, body, 0));
        assertTrue(BattleStateAssembler.holding());
        BattleStateAssembler.reset();
        assertFalse(BattleStateAssembler.holding());
        assertEquals(0, BattleStateAssembler.heldParts());
        // After a reset the next snapshot is judged on its own; a lower publication is accepted again
        // because the client's mirror no longer shows anything of the earlier one.
        assertEquals(BattleStateAssembler.Result.APPLIED, BattleStateAssembler.accept(part(1L, 2, 0)));
        assertEquals(1, applied.size());
    }
}
