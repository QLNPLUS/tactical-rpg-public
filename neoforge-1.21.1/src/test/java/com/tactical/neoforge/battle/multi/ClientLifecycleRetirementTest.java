package com.tactical.neoforge.battle.multi;

import com.tactical.neoforge.client.BattleClientLifecycle;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleDiscoveryClientState;
import com.tactical.neoforge.client.BattleLogState;
import com.tactical.neoforge.client.BattleMembership;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.BattleStatePacket;
import com.tactical.neoforge.network.JoinBattleResultPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's membership rules, driven through the real entry points
 * ({@link BattleClientLifecycle#accept}, {@link BattleClientState#apply}, {@link BattleLogState#accept}).
 *
 * <p>The server owns membership and publishes it as a monotonic generation; nothing on the client may
 * arm a transition. These tests are the closed loop the plan requires: an exit, a different encounter,
 * the same encounter re-entered, a repeated transition, a contradiction, queued payloads of a closed
 * connection, and a life-cycle reset. The renderer, camera and AUI page are still unverified here
 * (MS05 needs a real client).</p>
 */
class ClientLifecycleRetirementTest {

    private static final long SERVER = 4242L;

    private static BattleStatePacket state(UUID session, long generation, long epoch) {
        return new BattleStatePacket(true, 2, (int) epoch + 1, 80, 150, 10,
                0, 64, 0, new int[]{0, 64, 0}, "actor", List.of("actor"), 1,
                "sword", 0, 0, 0, 64, 0, 24, false, 100, new int[]{100, 20, 20}, new int[]{50},
                1, false, new int[]{100},
                10, 8, 10, 50, 0, session, epoch, SERVER, generation);
    }

    private static BattleStatePacket exit(UUID session, long generation) {
        return new BattleStatePacket(false, com.tactical.core.combat.BattlePhase.INACTIVE.ordinal(),
                1, 0, 0, 0, 0, 0, 0, new int[0], "", List.of(), 0, "", 0, 0,
                0, 0, 0, 0, false, -1, new int[0], new int[0], 0, false, new int[0],
                0, 0, 0, 0, 0, session, 0L, SERVER, generation);
    }

    private static BattleLogPacket journal(UUID session, long generation, long sequence, String text) {
        List<BattleLogPacket.Entry> history = new ArrayList<>();
        for (long row = 1; row <= sequence; row++) {
            history.add(new BattleLogPacket.Entry(row, "battle", row == sequence ? text : "行 " + row));
        }
        return new BattleLogPacket(session, BattleLogPacket.UPDATE, sequence, history, SERVER, generation);
    }

    /** A player-owned environment with a no-op UI, because no renderer exists in a unit test. */
    private static final class StubEnvironment implements BattleClientLifecycle.Environment {
        boolean ready = true;
        boolean alive = true;
        int self = 100;
        int uiTeardowns;

        @Override public boolean ready() { return ready; }
        @Override public boolean alive() { return alive; }
        @Override public int selfEntityId() { return self; }
        @Override public void teardownUi() { uiTeardowns++; }
        @Override public void refreshActionLabels() { }
    }

    /** No packets are sent; the discovery rules are exercised without a connection. */
    private static final class StubTransport implements BattleDiscoveryClientState.Transport {
        @Override public boolean ready() { return true; }
        @Override public long now() { return 0L; }
        @Override public void requestSummary(long requestId) { }
        @Override public void joinSession(UUID sessionId) { }
    }

    private final StubEnvironment environment = new StubEnvironment();

    @AfterEach
    void reset() {
        BattleClientLifecycle.environmentForTesting(null);
        BattleClientLifecycle.end();
        BattleMembership.reset();
        BattleClientState.clear();
        BattleDiscoveryClientState.transportForTesting(null);
        BattleDiscoveryClientState.clear();
    }

    @Test
    void nothingMayBeShownWithoutAServerStampedTransition() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();

        // Clicking a row and receiving "joined" are UI facts. They authorize nothing.
        BattleDiscoveryClientState.transportForTesting(new StubTransport());
        BattleDiscoveryClientState.join(session);
        BattleDiscoveryClientState.accept(new JoinBattleResultPacket(session,
                BattleEligibility.Denial.NONE, ""));
        assertFalse(BattleClientState.apply(state(session, 0L, 1L)),
                "an unstamped payload can never enter an encounter");
        assertFalse(BattleClientState.active, "the click did not authorize a membership");
        assertNull(BattleMembership.sessionId());

        // The transition the server publishes after committing the member is what admits this client.
        BattleClientLifecycle.accept(state(session, 1L, 1L));
        assertTrue(BattleClientState.active);
        assertEquals(session, BattleClientState.sessionId);
        assertEquals(1L, BattleMembership.generation());
        assertEquals(BattleMembership.Decision.REJECT_UNBOUND,
                BattleMembership.transition(0L, 0L, session, true),
                "a payload without a generation proves nothing");
    }

    @Test
    void aHigherGenerationTakesOverAndTheLeftEncounterCannotComeBack() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        BattleClientLifecycle.accept(state(first, 1L, 2L));
        assertTrue(BattleClientState.active);

        // The server ends that membership: a newer generation, an exit.
        BattleClientLifecycle.accept(exit(first, 2L));
        assertFalse(BattleClientState.active);
        assertEquals(1L, environment.uiTeardowns, "the display was torn down");
        assertEquals(2L, BattleMembership.generation());

        // A different encounter may be entered at a newer generation.
        BattleClientLifecycle.accept(state(second, 3L, 1L));
        assertTrue(BattleClientState.active);
        assertEquals(second, BattleClientState.sessionId);

        // Every payload of the first membership is refused, on every channel.
        assertEquals(BattleMembership.Decision.REJECT_OLD_GENERATION,
                BattleMembership.transition(SERVER, 1L, first, true));
        assertFalse(BattleClientState.apply(state(first, 1L, 2L)), "a delayed state of A cannot replace B");
        assertFalse(BattleMembership.matches(SERVER, 1L, first), "A's journal is refused");
        assertFalse(BattleMembership.matches(SERVER, 1L, first), "A's skills, warnings and range too");

        BattleLogState log = new BattleLogState();
        log.retire(first, 2L);
        assertFalse(log.accept(journal(first, 1L, 1, "迟到的行"), 0L),
                "the journal of the generation that was left stays refused");
        assertTrue(log.accept(journal(second, 3L, 1, "第二场"), 0L),
                "the shown membership's journal is accepted");
    }

    @Test
    void theSameEncounterReenteredAtANewerGenerationIsShownAgain() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        BattleLogState log = new BattleLogState();

        BattleClientLifecycle.accept(state(session, 1L, 1L));
        BattleClientLifecycle.accept(exit(session, 2L));
        log.retire(session, 2L);
        assertFalse(BattleClientState.active);

        // A legitimate re-entry: the same encounter, a newer generation. No client-side arming at all.
        BattleClientLifecycle.accept(state(session, 3L, 1L));
        assertTrue(BattleClientState.active, "the newer generation reopens the same encounter");
        assertEquals(3L, BattleMembership.generation());

        assertTrue(BattleMembership.matches(SERVER, 3L, session));
        assertFalse(log.accept(journal(session, 2L, 4, "旧代次的行"), 0L),
                "the generation that was left still cannot write");
        assertTrue(log.accept(journal(session, 3L, 4, "重入后的行"), 0L),
                "the new generation lifts the journal's refusal");
        assertEquals(session, log.battleId());
    }

    @Test
    void aRepeatedTransitionIsIdempotentAndAContradictionChangesNothing() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(SERVER, 5L, session, true));
        assertEquals(BattleMembership.Decision.ACCEPT_IDEMPOTENT,
                BattleMembership.transition(SERVER, 5L, session, true),
                "the same transition again is a replay, not a new membership");
        assertEquals(BattleMembership.Decision.REJECT_CONFLICT,
                BattleMembership.transition(SERVER, 5L, other, true),
                "one generation cannot belong to two encounters");
        assertEquals(BattleMembership.Decision.REJECT_CONFLICT,
                BattleMembership.transition(SERVER, 5L, session, false),
                "and it cannot be both entered and left");
        assertSame(session, BattleMembership.sessionId(), "a contradiction never changes what is shown");
        assertTrue(BattleMembership.active());
    }

    @Test
    void aNewConnectionRefusesWhatTheOldOneQueuedButAcceptsTheNextTransition() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();

        BattleClientLifecycle.accept(state(session, 4L, 1L));
        assertTrue(BattleClientState.active);

        // Logout/login: the client forgets the encounter but remembers the highest generation.
        BattleClientLifecycle.end();
        assertFalse(BattleClientState.active);
        assertEquals(4L, BattleMembership.closedGeneration());
        BattleClientLifecycle.environmentForTesting(environment);

        assertFalse(BattleClientState.apply(state(session, 4L, 1L)),
                "a payload still queued from the previous connection is refused");
        assertEquals(BattleMembership.Decision.REJECT_OLD_GENERATION,
                BattleMembership.transition(SERVER, 3L, session, true));

        // The server's counter continues above it, so the next legitimate transition is accepted.
        BattleClientLifecycle.accept(state(session, 5L, 1L));
        assertTrue(BattleClientState.active);
        assertEquals(5L, BattleMembership.generation());
    }

    @Test
    void twoServerEpochsAreNeverComparedAgainstEachOther() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        // Server A hands out generation 100 on this connection...
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(100L, 100L, first, true));
        BattleClientLifecycle.end();

        // ...then the client reconnects to server B, whose counter starts at 1. The remembered 100
        // belongs to A and must not suppress B's transitions.
        BattleClientLifecycle.environmentForTesting(environment);
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(200L, 1L, second, true),
                "a different server instance restarts the comparison");
        assertEquals(1L, BattleMembership.generation());

        // Reconnect again on the same server: only now does the previous top matter.
        BattleClientLifecycle.end();
        BattleClientLifecycle.environmentForTesting(environment);
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(200L, 2L, second, true));
        assertEquals(BattleMembership.Decision.REJECT_OLD_GENERATION,
                BattleMembership.transition(200L, 1L, second, true),
                "and the closed connection's generation is refused");
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(200L, 3L, second, true));
    }

    @Test
    void aPayloadOfAnotherServerInstanceCannotReplaceTheLatchedMembership() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(1L, 5L, session, true));

        assertEquals(BattleMembership.Decision.REJECT_OTHER_SERVER,
                BattleMembership.transition(2L, 6L, session, true),
                "a live membership belongs to one server instance");
        assertEquals(BattleMembership.Decision.REJECT_OTHER_SERVER,
                BattleMembership.transition(2L, 1L, session, true));
        assertEquals(5L, BattleMembership.generation(), "and nothing was reset by those refusals");
        assertTrue(BattleMembership.active());
    }

    @Test
    void aRepeatedExitIsIdempotentAndDoesNotTearTheUiDownAgain() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        BattleClientLifecycle.accept(state(session, 1L, 1L));
        BattleClientLifecycle.accept(exit(session, 2L));
        int teardowns = environment.uiTeardowns;
        assertEquals(1, teardowns, "leaving tears the UI down once");

        // The server may republish the same exit (a resync, a duplicated send). The player has since
        // opened the nearby list; a second teardown would close it.
        BattleClientLifecycle.accept(exit(session, 2L));
        assertEquals(teardowns, environment.uiTeardowns,
                "an idempotent replay of the exit must not clean up again");
        assertEquals(BattleMembership.Decision.ACCEPT_IDEMPOTENT,
                BattleMembership.transition(SERVER, 2L, session, false));
        assertFalse(BattleClientState.active);
    }

    @Test
    void aMemberWhoseBootstrapWasLostIsStillConsistent() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        BattleLogState log = new BattleLogState();

        // What the server did publish: the transition snapshot. The journal and warnings never arrived.
        BattleClientLifecycle.accept(state(session, 4L, 2L));
        assertTrue(BattleClientState.active, "the entry is real even without its journal");
        assertEquals(session, BattleClientState.sessionId);

        // The repair is an authoritative resync at the same generation, not an exit.
        assertTrue(BattleClientState.apply(state(session, 4L, 2L)),
                "a republished snapshot of the same membership is accepted");
        assertTrue(BattleMembership.matches(SERVER, 4L, session));
        assertTrue(log.accept(journal(session, 4L, 2, "补发的历史"), 0L),
                "and the journal that arrives afterwards still binds to it");
        assertTrue(BattleClientState.active);
    }

    @Test
    void aRestartedServerIsNotMistakenForAStalePayload() {
        // A restarted server publishes a fresh epoch, so its counter starting over is legitimate.
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        BattleClientLifecycle.accept(state(session, 7L, 1L));
        BattleClientLifecycle.end();
        assertEquals(7L, BattleMembership.closedGeneration());
        BattleClientLifecycle.environmentForTesting(environment);

        UUID next = UUID.randomUUID();
        BattleClientLifecycle.accept(new BattleStatePacket(true, 2, 2, 80, 150, 10,
                0, 64, 0, new int[]{0, 64, 0}, "actor", List.of("actor"), 1,
                "sword", 0, 0, 0, 64, 0, 24, false, 100, new int[]{100, 20, 20}, new int[]{50},
                1, false, new int[]{100}, 10, 8, 10, 50, 0, next, 1L, 9999L, 1L));
        assertTrue(BattleClientState.active, "a different server instance may start its own counter");
        assertEquals(next, BattleClientState.sessionId);
    }

    @Test
    void insideOneMembershipTheTurnOrderStillCannotGoBackwards() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();

        BattleClientLifecycle.accept(state(session, 1L, 4L));
        assertEquals(4L, BattleClientState.turnEpoch);
        assertFalse(BattleClientState.apply(state(session, 1L, 3L)),
                "the same membership's older turn is refused");
        assertTrue(BattleClientState.apply(state(session, 1L, 4L)), "an equal turn is an ordinary resend");
        assertTrue(BattleClientState.apply(state(session, 1L, 5L)));
        assertEquals(5L, BattleClientState.turnEpoch);
    }

    @Test
    void aLifecycleResetArrivesAsAnExitAndNeverEntersAnything() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();
        BattleClientLifecycle.accept(state(session, 1L, 1L));

        // Login/respawn: an exit that names no encounter, at a newer generation.
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleClientState.applyInactive(exit(null, 2L)),
                "an exit without an encounter is a legal lifecycle reset");
        assertFalse(BattleClientState.active);
        assertEquals(2L, BattleMembership.generation());
        assertNull(BattleMembership.sessionId(), "a reset names no encounter, so nothing is entered");

        // A death/respawn therefore never puts the player back into an encounter by itself.
        assertFalse(BattleClientState.active);
        BattleClientLifecycle.accept(state(session, 3L, 1L));
        assertTrue(BattleClientState.active, "only an explicit new transition enters again");
    }

    @Test
    void anUndisplayableSnapshotDoesNotConsumeTheMembership() {
        BattleClientLifecycle.environmentForTesting(environment);
        UUID session = UUID.randomUUID();

        environment.alive = false;
        BattleClientLifecycle.accept(state(session, 1L, 1L));
        assertFalse(BattleClientState.active, "a dead client shows nothing");
        assertTrue(environment.uiTeardowns > 0, "and its UI is torn down");
        assertEquals(0L, BattleMembership.generation(),
                "the transition is still pending, so the same generation may be applied when it can be shown");

        environment.alive = true;
        BattleClientLifecycle.accept(state(session, 1L, 1L));
        assertTrue(BattleClientState.active, "once displayable, the same transition is applied");
        assertEquals(1L, BattleMembership.generation());
    }
}
