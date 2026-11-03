package com.tactical.neoforge.client.progression;

import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.network.CharacterStatePacket;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client cache's three guards and its local flow state, without a running client.
 *
 * <p>The identity source is replaced with a fake, so the production path - which reads the local player -
 * is never touched by this test.</p>
 */
class CharacterClientStateTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static volatile UUID local;

    @BeforeEach void installFakeIdentity() {
        local = OWNER;
        CharacterClientState.install(() -> local);
        CharacterClientState.reset();
    }

    @AfterEach void restoreLiveIdentity() {
        CharacterClientState.reset();
        CharacterClientState.install(null);
    }

    private static CharacterStatePacket packet(int requestId, long revision, boolean openScreen, ProgressionReason reason) {
        return new CharacterStatePacket(OWNER, requestId, openScreen, true, true, true, reason.name(),
                Component.translatable(reason.key()), revision, 2, 100, 99, new double[6], 1, 1, 1, 0, 100L, 150L, new int[6], new int[6],
                new double[6], new int[6], false, new double[6]);
    }

    /** The answer a committed-but-unreadable transaction produces: known revision, no trustworthy numbers. */
    private static CharacterStatePacket unavailable(int requestId, long revision) {
        return new CharacterStatePacket(OWNER, requestId, false, true, false, false,
                ProgressionReason.SYNC_UNAVAILABLE.name(), Component.translatable(ProgressionReason.SYNC_UNAVAILABLE.key()),
                revision, 3, 100, 99, new double[6], 1, 1, 2, 0, 0L, 150L, new int[6], new int[6],
                new double[6], new int[6], false, new double[6]);
    }

    @Test void theNewestAnswerBecomesTheState() {
        CharacterStatePacket first = packet(1, 4L, true, ProgressionReason.OK);
        assertTrue(CharacterClientState.accept(first));
        assertSame(first, CharacterClientState.current());
        assertEquals(4L, CharacterClientState.revision());
        assertEquals(OWNER, CharacterClientState.owner());
    }

    @Test void anOlderRevisionNeverRollsThePageBack() {
        CharacterClientState.accept(packet(2, 5L, false, ProgressionReason.OK));
        assertFalse(CharacterClientState.accept(packet(3, 4L, false, ProgressionReason.OK)));
        assertEquals(5L, CharacterClientState.revision());
    }

    @Test void anAnswerToAnOlderRequestIsDroppedWithinOneRevision() {
        CharacterClientState.accept(packet(4, 5L, false, ProgressionReason.OK));
        assertFalse(CharacterClientState.accept(packet(3, 5L, false, ProgressionReason.OK)));
        assertTrue(CharacterClientState.accept(packet(5, 5L, false, ProgressionReason.OK)));
    }

    @Test void aRefusalAtTheSameRevisionStillReachesThePlayer() {
        CharacterClientState.accept(packet(4, 5L, false, ProgressionReason.OK));
        CharacterStatePacket refusal = packet(5, 5L, false, ProgressionReason.INSUFFICIENT_XP);
        assertTrue(CharacterClientState.accept(refusal));
        assertSame(refusal, CharacterClientState.current());
        assertFalse(CharacterClientState.current().accepted());
    }

    @Test void aForeignOwnerIsDropped() {
        CharacterClientState.accept(packet(1, 2L, false, ProgressionReason.OK));
        CharacterStatePacket foreign = new CharacterStatePacket(OTHER, 2, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 9L, 5, 100, 99, new double[6], 1, 1, 4, 0, 0L, 0L, new int[6],
                new int[6], new double[6], new int[6], false, new double[6]);
        assertFalse(CharacterClientState.accept(foreign));
        assertEquals(2L, CharacterClientState.revision());
    }

    @Test void aDifferentOwnerWithNoLocalIdentityStartsFromNothing() {
        local = null;
        CharacterClientState.accept(packet(1, 2L, false, ProgressionReason.OK));
        CharacterStatePacket other = new CharacterStatePacket(OTHER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 1, 100, 99, new double[6], 1, 1, 0, 0, 0L, 100L, new int[6],
                new int[6], new double[6], new int[6], false, new double[6]);
        assertTrue(CharacterClientState.accept(other));
        assertEquals(OTHER, CharacterClientState.owner());
        assertEquals(1L, CharacterClientState.revision());
    }

    @Test void awaitingBlocksRepeatSubmitsUntilTheAnswerArrives() {
        assertFalse(CharacterClientState.awaiting());
        int request = CharacterClientState.beginRequest();
        assertTrue(CharacterClientState.awaiting());
        assertEquals(request, CharacterClientState.awaitingRequest());
        CharacterClientState.accept(packet(request, 3L, false, ProgressionReason.OK));
        assertFalse(CharacterClientState.awaiting());
        assertTrue(CharacterClientState.beginRequest() > request);
    }

    @Test void anAnswerToAnOlderRequestDoesNotClearTheWait() {
        CharacterClientState.accept(packet(5, 3L, false, ProgressionReason.OK));
        int request = CharacterClientState.beginRequest();
        // A stale answer for an already superseded request is dropped and the outstanding wait stands.
        assertFalse(CharacterClientState.accept(packet(3, 3L, false, ProgressionReason.OK)));
        assertTrue(CharacterClientState.awaiting());
        assertEquals(request, CharacterClientState.awaitingRequest());
    }

    @Test void anAnswerToANewerRequestEndsTheWait() {
        CharacterClientState.beginRequest();
        CharacterClientState.accept(packet(9, 4L, false, ProgressionReason.OK));
        assertFalse(CharacterClientState.awaiting());
    }

    @Test void theOpenIntentIsConsumedExactlyOnce() {
        assertFalse(CharacterClientState.openRequestPending());
        CharacterClientState.accept(packet(1, 2L, true, ProgressionReason.OK));
        assertTrue(CharacterClientState.openRequestPending());
        assertTrue(CharacterClientState.takeOpenRequest());
        assertFalse(CharacterClientState.takeOpenRequest());
        assertFalse(CharacterClientState.openRequestPending());
    }

    @Test void anUnavailableAnswerEndsTheWaitWithoutFakingUsableData() {
        CharacterStatePacket real = packet(1, 5L, false, ProgressionReason.OK);
        assertTrue(CharacterClientState.accept(real));
        int request = CharacterClientState.beginRequest();
        assertTrue(CharacterClientState.awaiting());

        CharacterStatePacket notice = unavailable(request, 6L);
        // It is an answer to our request, but it carries no trustworthy numbers: it must not become the page.
        assertFalse(CharacterClientState.accept(notice));
        assertSame(real, CharacterClientState.current(), "the last real page stays exactly as it was");
        assertEquals(5L, CharacterClientState.revision(), "a placeholder revision never replaces the real one");
        assertTrue(real.canMutate(), "the untouched real page keeps its own state");
        assertFalse(CharacterClientState.awaiting(), "the wait it answered is over");
        assertTrue(CharacterClientState.needsRefresh(), "the page must ask for a fresh snapshot");
        assertSame(notice, CharacterClientState.refreshNotice());
        assertEquals(ProgressionReason.SYNC_UNAVAILABLE, CharacterClientState.refreshNotice().refusal());
    }

    @Test void anUnavailableAnswerOnAnEmptyPageLeavesNothingFaked() {
        int request = CharacterClientState.beginRequest();
        assertFalse(CharacterClientState.accept(unavailable(request, 8L)));
        assertNull(CharacterClientState.current(), "no page is invented from placeholders");
        assertEquals(-1L, CharacterClientState.revision());
        assertFalse(CharacterClientState.awaiting());
        assertTrue(CharacterClientState.needsRefresh());
    }

    @Test void aFreshStateClearsTheRefreshDemand() {
        CharacterClientState.beginRequest();
        CharacterClientState.accept(unavailable(9, 8L));
        assertTrue(CharacterClientState.needsRefresh());
        assertTrue(CharacterClientState.accept(packet(10, 8L, false, ProgressionReason.OK)));
        assertFalse(CharacterClientState.needsRefresh());
        assertNull(CharacterClientState.refreshNotice());
    }

    @Test void resetDropsEverything() {
        CharacterClientState.accept(packet(1, 2L, true, ProgressionReason.OK));
        CharacterClientState.beginRequest();
        CharacterClientState.reset();
        assertNull(CharacterClientState.current());
        assertNull(CharacterClientState.owner());
        assertEquals(-1L, CharacterClientState.revision());
        assertFalse(CharacterClientState.awaiting());
        assertFalse(CharacterClientState.openRequestPending());
    }
}
