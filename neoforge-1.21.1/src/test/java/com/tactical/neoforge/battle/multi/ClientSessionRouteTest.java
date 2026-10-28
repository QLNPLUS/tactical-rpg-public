package com.tactical.neoforge.battle.multi;

import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleDiscoveryClientState;
import com.tactical.neoforge.client.BattleLogState;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.JoinBattleResultPacket;
import com.tactical.neoforge.network.NearbyBattlesPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side routing of discovery answers and journal snapshots.
 *
 * <p>The encounter-authorization rules moved to the server-published membership generation and are
 * covered by {@code ClientLifecycleRetirementTest}; what remains here is the traffic that does not
 * decide membership: nearby-list request/answer ordering and the journal's own history rules.</p>
 */
class ClientSessionRouteTest {


    @AfterEach
    void resetClientState() {
        BattleClientState.clear();
        BattleDiscoveryClientState.clear();
        BattleDiscoveryClientState.transportForTesting(null);
    }

    @Test
    void aJoinerSeesTheFullLogEvenWhenTheFirstPacketIsAnUpdateOrFinish() {
        UUID canonical = UUID.randomUUID();
        List<BattleLogPacket.Entry> history = new ArrayList<>();
        history.add(new BattleLogPacket.Entry(1, "battle", "战斗开始：2 个敌人加入"));
        history.add(new BattleLogPacket.Entry(2, "damage", "僵尸 受到 3.0 伤害 · 玩家"));

        BattleLogState state = new BattleLogState();
        assertTrue(state.accept(new BattleLogPacket(canonical, BattleLogPacket.UPDATE, 2, history), 0L),
                "a joiner's first packet can be an UPDATE and still carries the whole history");
        assertEquals(canonical, state.battleId());
        assertEquals(2, state.history().size(), "the complete log is present, not just the new row");

        BattleLogState finishing = new BattleLogState();
        assertTrue(finishing.accept(new BattleLogPacket(canonical, BattleLogPacket.FINISH, 3,
                        List.of(new BattleLogPacket.Entry(1, "battle", "战斗开始"),
                                new BattleLogPacket.Entry(3, "battle", "战斗结束"))), 0L),
                "a FINISH can also be the first packet");

        BattleLogState clearing = new BattleLogState();
        assertTrue(clearing.accept(new BattleLogPacket(canonical, BattleLogPacket.START, 1,
                List.of(new BattleLogPacket.Entry(1, "battle", "战斗开始"))), 0L));
        assertTrue(clearing.accept(new BattleLogPacket(canonical, BattleLogPacket.CLEAR, 1, List.of()), 1L),
                "the same encounter's own clear is honoured");
    }

    @Test
    void aLateNearbyAnswerCannotReplaceTheListAfterANewerRequest() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();

        assertTrue(BattleDiscoveryClientState.request(), "the first request is sent");
        long firstRequest = BattleDiscoveryClientState.latestRequestId();
        transport.now += 40;
        assertTrue(BattleDiscoveryClientState.request(), "a later request is allowed and newer");
        long secondRequest = BattleDiscoveryClientState.latestRequestId();
        assertTrue(secondRequest > firstRequest);

        UUID fresh = UUID.randomUUID();
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(secondRequest, List.of(
                new NearbyBattlesPacket.Entry(fresh, "new", 4, 1, 4, true, ""))));
        assertEquals(1, BattleDiscoveryClientState.entries().size());
        assertEquals(fresh, BattleDiscoveryClientState.entries().get(0).sessionId());

        // The slow answer to the superseded request may not roll the applied list back.
        UUID stale = UUID.randomUUID();
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(firstRequest, List.of(
                new NearbyBattlesPacket.Entry(stale, "old", 3, 1, 4, true, ""))));
        assertEquals(1, BattleDiscoveryClientState.entries().size(),
                "an answer older than the applied one cannot replace the list");
        assertEquals(fresh, BattleDiscoveryClientState.entries().get(0).sessionId());
    }

    @Test
    void aRateLimitedRefreshStaysPendingAndIsRetriedWithoutLosingTheReason() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();
        UUID target = UUID.randomUUID();

        assertTrue(BattleDiscoveryClientState.request());
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                BattleDiscoveryClientState.latestRequestId(), List.of(
                new NearbyBattlesPacket.Entry(target, "A", 5, 1, 4, true, ""))));
        BattleDiscoveryClientState.join(target);
        assertEquals(target, BattleDiscoveryClientState.joining());

        // The server refuses while its own 20-tick interval is still running: the client must ask for
        // a fresh list immediately instead of silently showing nothing.
        int requestsBeforeRefusal = transport.requests;
        BattleDiscoveryClientState.accept(new JoinBattleResultPacket(target,
                com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY,
                "战斗正在结算动作，请稍后再试"));
        assertNull(BattleDiscoveryClientState.joining(), "the confirmed row is no longer pending");
        assertEquals("战斗正在结算动作，请稍后再试", BattleDiscoveryClientState.lastMessage(),
                "the refusal reason stays visible");
        assertTrue(transport.joins.contains(target), "the join intent was sent");

        assertEquals(requestsBeforeRefusal + 1, transport.requests,
                "the refusal asks for a fresh list instead of leaving the page stale");
        // And a later background refresh is bounded, not a runaway stream of ids.
        transport.now += BattleDiscoveryClientState.LOCAL_REFRESH_TICKS;
        assertFalse(BattleDiscoveryClientState.request(),
                "an unanswered request is not replaced before the timeout");
        assertTrue(BattleDiscoveryClientState.refreshPending());
    }

    @Test
    void aJoinResultForAnotherRowIsIgnored() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(BattleDiscoveryClientState.request());
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                BattleDiscoveryClientState.latestRequestId(), List.of(
                new NearbyBattlesPacket.Entry(target, "A", 5, 1, 4, true, ""),
                new NearbyBattlesPacket.Entry(other, "B", 20, 2, 4, true, ""))));
        BattleDiscoveryClientState.join(target);

        BattleDiscoveryClientState.accept(new JoinBattleResultPacket(other,
                com.tactical.neoforge.battle.multi.BattleEligibility.Denial.SESSION_CLOSED, "已结束"));
        assertEquals(target, BattleDiscoveryClientState.joining(),
                "a result for another row neither completes nor cancels this request");
        assertEquals(2, BattleDiscoveryClientState.entries().size(),
                "and it does not refresh or clear the list behind the request");
        assertEquals("", BattleDiscoveryClientState.lastMessage());
    }

    @Test
    void anOutstandingRequestKeepsItsIdUntilTheAnswerOrTheTimeout() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();

        assertTrue(BattleDiscoveryClientState.request());
        long outstanding = BattleDiscoveryClientState.latestRequestId();

        // A round trip longer than the refresh interval must not replace the id.
        transport.now += BattleDiscoveryClientState.LOCAL_REFRESH_TICKS + 5;
        assertFalse(BattleDiscoveryClientState.request(), "an unanswered request is not overwritten");
        assertEquals(outstanding, BattleDiscoveryClientState.latestRequestId());
        assertTrue(BattleDiscoveryClientState.refreshPending());

        // The late answer is still the answer to the outstanding request, so it is applied.
        UUID session = UUID.randomUUID();
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(outstanding, List.of(
                new NearbyBattlesPacket.Entry(session, "A", 6, 1, 4, true, ""))));
        assertEquals(1, BattleDiscoveryClientState.entries().size(),
                "a slow answer is not treated as stale");
        assertFalse(BattleDiscoveryClientState.awaiting());

        // Now the interval was long passed, so the next request is a real one.
        transport.now += BattleDiscoveryClientState.LOCAL_REFRESH_TICKS;
        assertTrue(BattleDiscoveryClientState.request());
        assertTrue(BattleDiscoveryClientState.latestRequestId() > outstanding);
    }

    @Test
    void aRequestDroppedByTheServerIsRetriedAfterTheTimeout() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();

        assertTrue(BattleDiscoveryClientState.request());
        long dropped = BattleDiscoveryClientState.latestRequestId();
        int sentBefore = transport.requests;

        // The server silently rate-limits that request: no answer, and no id overwrite while waiting.
        transport.now += BattleDiscoveryClientState.LOCAL_REFRESH_TICKS;
        assertFalse(BattleDiscoveryClientState.request());
        assertEquals(sentBefore, transport.requests);

        // After the bounded timeout the client retries with a new id, which is answered.
        transport.now += BattleDiscoveryClientState.RESPONSE_TIMEOUT_TICKS;
        assertTrue(BattleDiscoveryClientState.request(), "the dropped request is retried");
        long retried = BattleDiscoveryClientState.latestRequestId();
        assertTrue(retried > dropped);
        assertEquals(sentBefore + 1, transport.requests);

        UUID session = UUID.randomUUID();
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(retried, List.of(
                new NearbyBattlesPacket.Entry(session, "A", 6, 1, 4, true, ""))));
        assertEquals(1, BattleDiscoveryClientState.entries().size(), "the retry is answered normally");
        // The superseded answer stays stale.
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(dropped, List.of()));
        assertEquals(1, BattleDiscoveryClientState.entries().size(), "the older id cannot clear the list");
    }

    @Test
    void aSixtyTickRoundTripStillDeliversTheList() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();
        UUID session = UUID.randomUUID();

        assertTrue(BattleDiscoveryClientState.request());
        long first = BattleDiscoveryClientState.latestRequestId();

        // The link is slower than the first window: the client retries once instead of starving, but the
        // answer already in flight for the previous request is still a truthful answer.
        transport.now += BattleDiscoveryClientState.RESPONSE_TIMEOUT_TICKS;
        BattleDiscoveryClientState.tick();
        long second = BattleDiscoveryClientState.latestRequestId();
        assertTrue(second > first);

        transport.now += 20; // the first answer arrives at RTT 60
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(first, List.of(
                new NearbyBattlesPacket.Entry(session, "slow", 5, 1, 4, true, ""))));
        assertEquals(1, BattleDiscoveryClientState.entries().size(),
                "a 60-tick answer is applied instead of being thrown away as stale");

        // And the newer request's answer still wins when it arrives.
        UUID newer = UUID.randomUUID();
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(second, List.of(
                new NearbyBattlesPacket.Entry(newer, "slow", 5, 2, 4, true, ""))));
        assertEquals(newer, BattleDiscoveryClientState.entries().get(0).sessionId());

        // A superseded answer cannot overwrite the newer applied list.
        BattleDiscoveryClientState.accept(new NearbyBattlesPacket(first, List.of()));
        assertEquals(1, BattleDiscoveryClientState.entries().size(),
                "an answer older than the applied one never replaces it");
    }

    @Test
    void theAdaptiveWindowGrowsButStaysBoundedSoASlowLinkRecovers() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();

        assertTrue(BattleDiscoveryClientState.request());
        long previousId = BattleDiscoveryClientState.latestRequestId();
        int previousSends = transport.requests;

        // Every missed window retries with a fresh id, and the wait never exceeds the bound, so a link
        // slower than the first window still receives an answer instead of starving.
        for (int attempt = 0; attempt < 4; attempt++) {
            transport.now += BattleDiscoveryClientState.MAX_RESPONSE_TIMEOUT_TICKS;
            assertTrue(BattleDiscoveryClientState.request(),
                    "the retry interval stays bounded, attempt " + attempt);
            assertTrue(BattleDiscoveryClientState.latestRequestId() > previousId);
            assertEquals(previousSends + 1, transport.requests);
            previousId = BattleDiscoveryClientState.latestRequestId();
            previousSends = transport.requests;
        }
    }

    @Test
    void visibleStateChangesBumpTheRevisionThePageRepaintsOn() {
        StubTransport transport = new StubTransport();
        BattleDiscoveryClientState.transportForTesting(transport);
        BattleDiscoveryClientState.clear();

        long start = BattleDiscoveryClientState.revision();
        assertTrue(BattleDiscoveryClientState.request());
        assertTrue(BattleDiscoveryClientState.awaiting(), "the page shows the refresh in flight");

        // A suppressed refresh is visible information, so the page must repaint too.
        long afterRequest = BattleDiscoveryClientState.revision();
        transport.now += 5;
        assertFalse(BattleDiscoveryClientState.request());
        assertTrue(BattleDiscoveryClientState.refreshPending());
        assertTrue(BattleDiscoveryClientState.revision() > afterRequest,
                "the pending state bumps the revision the page binds to");

        // Repeated suppression without a change must not rebuild the page every tick.
        long stable = BattleDiscoveryClientState.revision();
        transport.now += 1;
        assertFalse(BattleDiscoveryClientState.request());
        assertEquals(stable, BattleDiscoveryClientState.revision());

        // A refusal keeps its reason visible while the retry state is shown.
        UUID target = UUID.randomUUID();
        BattleDiscoveryClientState.join(target);
        BattleDiscoveryClientState.accept(new JoinBattleResultPacket(target,
                com.tactical.neoforge.battle.multi.BattleEligibility.Denial.OUTSIDE_ZONE,
                "只能加入同一维度的战斗"));
        assertEquals("只能加入同一维度的战斗", BattleDiscoveryClientState.lastMessage());
        assertTrue(BattleDiscoveryClientState.revision() > start);
    }

    /** Deterministic clock and packet sink for the discovery rules. */
    private static final class StubTransport implements BattleDiscoveryClientState.Transport {
        long now = 1000L;
        int requests;
        final List<UUID> joins = new ArrayList<>();

        @Override public boolean ready() { return true; }

        @Override public long now() { return now; }

        @Override public void requestSummary(long requestId) { requests++; }

        @Override public void joinSession(UUID sessionId) { joins.add(sessionId); }
    }
}
