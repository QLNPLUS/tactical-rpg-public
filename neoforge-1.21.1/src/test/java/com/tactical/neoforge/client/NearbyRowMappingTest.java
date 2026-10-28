package com.tactical.neoforge.client;

import com.tactical.neoforge.network.NearbyBattlesPacket;
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
 * The rendered rows, the visible list and the row clicks must agree on one index.
 *
 * <p>Dismissing a row with "暂不加入" removes it from the visible list, so the rows below shift up.
 * If the page kept rendering the old row while the click handler recomputed the index, a click could
 * hit the encounter that moved into that slot - or, with a single row, silently do nothing. Both the
 * renderer and the handlers now go through {@link BattleDiscoveryClientState#rowAt(int)}.</p>
 */
class NearbyRowMappingTest {

    private static final class StubTransport implements BattleDiscoveryClientState.Transport {
        final List<UUID> joins = new ArrayList<>();

        @Override public boolean ready() { return true; }
        @Override public long now() { return 0L; }
        @Override public void requestSummary(long requestId) { }
        @Override public void joinSession(UUID sessionId) { joins.add(sessionId); }
    }

    private static NearbyBattlesPacket.Entry row(UUID session, String manager, int distance) {
        return new NearbyBattlesPacket.Entry(session, manager, distance, 1, 4, true, "");
    }

    private final StubTransport transport = new StubTransport();

    @AfterEach
    void reset() {
        BattleDiscoveryClientState.transportForTesting(null);
        BattleDiscoveryClientState.clear();
    }

    @Test
    void aDismissedRowLeavesTheMappingInsteadOfLeavingADeadSlot() {
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(BattleDiscoveryClientState.request());
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                BattleDiscoveryClientState.latestRequestId(),
                List.of(row(first, "A", 5), row(second, "B", 9))));

        assertSame(first, BattleDiscoveryClientState.rowAt(0).sessionId(), "row 0 is the first encounter");
        assertSame(second, BattleDiscoveryClientState.rowAt(1).sessionId(), "row 1 is the second encounter");

        long beforeDismiss = BattleDiscoveryClientState.revision();
        assertTrue(BattleDiscoveryClientState.dismissRow(0), "the visible row is dismissed");
        assertTrue(BattleDiscoveryClientState.revision() > beforeDismiss, "the page is told to repaint");

        // After the dismissal the mapping is one shorter: slot 0 now belongs to the second encounter.
        assertSame(second, BattleDiscoveryClientState.rowAt(0).sessionId(),
                "slot 0 must not keep pointing at the dismissed encounter");
        assertNull(BattleDiscoveryClientState.rowAt(1), "and there is no second slot any more");
        assertTrue(transport.joins.isEmpty(), "dismissing never joins anything");

        // Clicking the stale second slot must refresh, not act on a row that no longer exists.
        assertFalse(BattleDiscoveryClientState.joinRow(1), "a stale slot join is refused");
        assertTrue(transport.joins.isEmpty(), "and it never sends a join for the wrong encounter");
    }

    @Test
    void aJoinOnTheVisibleRowTargetsExactlyThatEncounter() {
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(BattleDiscoveryClientState.request());
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                BattleDiscoveryClientState.latestRequestId(),
                List.of(row(first, "A", 5), row(second, "B", 9))));

        assertTrue(BattleDiscoveryClientState.dismissRow(0));
        assertTrue(BattleDiscoveryClientState.joinRow(0), "the row now shown is joinable");
        assertEquals(List.of(second), transport.joins,
                "the click joined the encounter the player can see, not the dismissed one");
        assertSame(second, BattleDiscoveryClientState.joining());
    }

    @Test
    void reopeningTheListOffersEveryRowAgain() {
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();
        UUID first = UUID.randomUUID();
        assertTrue(BattleDiscoveryClientState.request());
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                BattleDiscoveryClientState.latestRequestId(), List.of(row(first, "A", 5))));

        assertTrue(BattleDiscoveryClientState.dismissRow(0));
        assertNull(BattleDiscoveryClientState.rowAt(0), "the only row is gone");
        assertFalse(BattleDiscoveryClientState.dismissRow(0), "and dismissing it again does nothing");

        // Reopening the page is what offers it again; closing never joins.
        BattleDiscoveryClientState.undismissAll();
        assertSame(first, BattleDiscoveryClientState.rowAt(0).sessionId(), "the row is offered again");
        assertTrue(transport.joins.isEmpty(), "reopening never joins by itself");
        assertNull(BattleDiscoveryClientState.joining());
    }
}
