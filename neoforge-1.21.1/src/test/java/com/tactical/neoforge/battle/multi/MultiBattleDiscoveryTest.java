package com.tactical.neoforge.battle.multi;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleMembership;
import com.tactical.neoforge.client.BattleDiscoveryClientState;
import com.tactical.neoforge.network.BattleStatePacket;
import com.tactical.neoforge.network.JoinBattleIntentPacket;
import com.tactical.neoforge.network.JoinBattleResultPacket;
import com.tactical.neoforge.network.NearbyBattlesPacket;
import com.tactical.neoforge.network.NearbyBattlesRequestPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP01 discovery/join contract: payload shape, direct opt-in admission, and the client-side
 * guard that keeps an older encounter's traffic out of the current one.
 *
 * <p>Minecraft-free: only the payload codecs, the session rules and the client's session filter.
 * The socket, distance and rate-limit behaviour is covered by the server self test.</p>
 */
class MultiBattleDiscoveryTest {

    private static final long TTL = 20L * 60L;

    private static PlayerParticipant participant(UUID id, String name) {
        return new PlayerParticipant(id, name, new GridPos(0, 64, 0), 100, 150, 10, 100);
    }

    private static BattleEligibility.Facts facts(boolean online, boolean alive, boolean spectator,
                                                 boolean sameDimension, boolean insideZone,
                                                 boolean alreadyMember, int members, int capacity,
                                                 boolean closed) {
        return BattleEligibility.Facts.of(online, alive, spectator, sameDimension, insideZone,
                alreadyMember, members, capacity, closed);
    }

    private static BattleEligibility.Facts eligible(int members, int capacity) {
        return facts(true, true, false, true, true, false, members, capacity, false);
    }

    private static <T> T roundTrip(net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes(), "codec must consume exactly what it wrote");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    @Test
    void nearbySummaryRoundTripsIncludingTheEmptyState() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        NearbyBattlesPacket packet = new NearbyBattlesPacket(7L, List.of(
                new NearbyBattlesPacket.Entry(first, "TacticalA", 12, 2, 4, true, ""),
                new NearbyBattlesPacket.Entry(second, "TacticalB", 40, 4, 4, false, "参战人数已达上限")));
        NearbyBattlesPacket decoded = roundTrip(NearbyBattlesPacket.STREAM_CODEC, packet);
        assertEquals(7L, decoded.requestId());
        assertEquals(2, decoded.entries().size());
        assertEquals(first, decoded.entries().get(0).sessionId());
        assertEquals("TacticalA", decoded.entries().get(0).manager());
        assertEquals(12, decoded.entries().get(0).distance());
        assertTrue(decoded.entries().get(0).joinable());
        assertEquals("参战人数已达上限", decoded.entries().get(1).reason());
        assertFalse(decoded.entries().get(1).joinable());

        NearbyBattlesPacket empty = roundTrip(NearbyBattlesPacket.STREAM_CODEC,
                new NearbyBattlesPacket(1L, List.of()));
        assertTrue(empty.entries().isEmpty(), "an empty list is the explicit nothing-nearby state");
        assertEquals(1L, empty.requestId());
    }

    @Test
    void nearbySummaryBoundsItsFieldsAndRefusesOversizedLists() {
        NearbyBattlesPacket packet = new NearbyBattlesPacket(2L, List.of(
                new NearbyBattlesPacket.Entry(UUID.randomUUID(), "x".repeat(200), 1, 1, 4, true,
                        "y".repeat(200))));
        NearbyBattlesPacket.Entry entry = roundTrip(NearbyBattlesPacket.STREAM_CODEC, packet).entries().get(0);
        assertEquals(NearbyBattlesPacket.MAX_NAME, entry.manager().length());
        assertEquals(NearbyBattlesPacket.MAX_REASON, entry.reason().length());

        List<NearbyBattlesPacket.Entry> tooMany = new ArrayList<>();
        for (int index = 0; index <= NearbyBattlesPacket.MAX_ENTRIES; index++) {
            tooMany.add(new NearbyBattlesPacket.Entry(UUID.randomUUID(), "m", 1, 1, 4, false, ""));
        }
        assertThrows(IllegalArgumentException.class, () -> new NearbyBattlesPacket(3L, tooMany),
                "a server can never build a list larger than the page can show");
    }

    @Test
    void joinIntentAndResultRoundTripEveryDenial() {
        UUID session = UUID.randomUUID();
        assertEquals(session, roundTrip(JoinBattleIntentPacket.STREAM_CODEC,
                new JoinBattleIntentPacket(session)).sessionId());
        JoinBattleResultPacket decoded = roundTrip(JoinBattleResultPacket.STREAM_CODEC,
                new JoinBattleResultPacket(session, BattleEligibility.Denial.BATTLE_FULL,
                        BattleEligibility.describe(BattleEligibility.Denial.BATTLE_FULL)));
        assertEquals(session, decoded.sessionId());
        assertEquals(BattleEligibility.Denial.BATTLE_FULL, decoded.denial());
        assertFalse(decoded.joined());

        JoinBattleResultPacket accepted = roundTrip(JoinBattleResultPacket.STREAM_CODEC,
                new JoinBattleResultPacket(session, BattleEligibility.Denial.NONE, ""));
        assertTrue(accepted.joined());

        for (BattleEligibility.Denial denial : BattleEligibility.Denial.values()) {
            assertEquals(denial, roundTrip(JoinBattleResultPacket.STREAM_CODEC,
                    new JoinBattleResultPacket(session, denial, "")).denial());
        }
        assertEquals(42L, roundTrip(NearbyBattlesRequestPacket.STREAM_CODEC,
                new NearbyBattlesRequestPacket(42L)).requestId());
    }

    @Test
    void aDecodeOfAnUnknownDenialIsRejected() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeVarInt(BattleEligibility.Denial.values().length + 5);
            buffer.writeUtf("", JoinBattleResultPacket.MAX_MESSAGE);
            assertThrows(IllegalArgumentException.class,
                    () -> JoinBattleResultPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void directJoinNeedsNoInvitationButEveryOtherRuleStillApplies() {
        UUID manager = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        BattleSession session = new BattleSession(4);
        session.addMember(participant(manager, "manager"));
        assertFalse(session.hasInvitation(joiner));

        BattleSession.JoinResult joined = session.acceptDirect(joiner, 0, eligible(1, 4),
                participant(joiner, "joiner"));
        assertTrue(joined.joined(), "direct opt-in needs no invitation and no approval");
        assertTrue(session.isMember(joiner));

        UUID other = UUID.randomUUID();
        assertEquals(BattleEligibility.Denial.ALREADY_MEMBER,
                session.acceptDirect(joiner, 1, eligible(2, 4), participant(joiner, "joiner")).denial());
        assertEquals(BattleEligibility.Denial.BATTLE_FULL,
                session.acceptDirect(other, 1, eligible(4, 4), participant(other, "other")).denial());
        assertEquals(BattleEligibility.Denial.OUTSIDE_ZONE,
                session.acceptDirect(other, 1, facts(true, true, false, true, false, false, 2, 4, false),
                        participant(other, "other")).denial());
        assertEquals(BattleEligibility.Denial.DIFFERENT_DIMENSION,
                session.acceptDirect(other, 1, facts(true, true, false, false, true, false, 2, 4, false),
                        participant(other, "other")).denial());
        assertEquals(BattleEligibility.Denial.DEAD,
                session.acceptDirect(other, 1, facts(true, false, false, true, true, false, 2, 4, false),
                        participant(other, "other")).denial());
        assertEquals(BattleEligibility.Denial.UNRESOLVED,
                session.acceptDirect(other, 1, eligible(2, 4), null).denial());
        assertEquals(BattleEligibility.Denial.UNRESOLVED,
                session.acceptDirect(other, 1, eligible(2, 4), participant(UUID.randomUUID(), "x")).denial(),
                "the resolved participant must be the player who asked");

        session.close();
        assertEquals(BattleEligibility.Denial.SESSION_CLOSED,
                session.acceptDirect(UUID.randomUUID(), 1, eligible(1, 4),
                        participant(UUID.randomUUID(), "later")).denial());
    }

    @Test
    void directJoinConsumesAPendingInvitation() {
        UUID manager = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        BattleSession session = new BattleSession(4);
        session.addMember(participant(manager, "manager"));
        session.invite(manager, joiner, 0, TTL, eligible(1, 4));
        assertTrue(session.hasInvitation(joiner));

        assertTrue(session.acceptDirect(joiner, 1, eligible(1, 4), participant(joiner, "joiner")).joined());
        assertFalse(session.hasInvitation(joiner), "self-service join consumes the invitation");
        assertEquals(2, session.memberCount());
    }

    @Test
    void aClientOnlyAppliesTrafficOfTheMembershipTheServerPublished() {
        UUID current = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        try {
            // Nothing is shown until the server publishes a transition: auxiliary payloads that arrive
            // before it are dropped, and the server resends its full snapshot afterwards.
            assertFalse(BattleMembership.matches(1L, 1L, current),
                    "a payload before the transition authorizes nothing");

            assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                    BattleMembership.transition(1L, 4L, current, true));

            assertTrue(BattleMembership.matches(1L, 4L, current));
            assertFalse(BattleMembership.matches(1L, 4L, older),
                    "another encounter's payload is refused");
            assertFalse(BattleMembership.matches(1L, 3L, current),
                    "an older generation of this encounter is refused");
            assertFalse(BattleMembership.matches(2L, 4L, current),
                    "another server instance's stamp is refused");
            assertNotEquals(older, current);
        } finally {
            BattleMembership.reset();
        }
    }


    @Test
    void theBKeyOpensTheBattleMenuOnlyWhileABattleIsShown() {
        boolean saved = BattleClientState.active;
        try {
            BattleClientState.active = false;
            assertFalse(BattleDiscoveryClientState.bOpensBattleMenu(),
                    "outside a battle B opens the nearby list, not the battle menu");
            BattleClientState.active = true;
            assertTrue(BattleDiscoveryClientState.bOpensBattleMenu(),
                    "a participant keeps the original battle menu");
        } finally {
            BattleClientState.active = saved;
        }
    }

    @Test
    void aDismissedRowCannotBeJoinedUntilItBecomesJoinableAgain() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            BattleDiscoveryClientState.clear();
            // Answers are matched to the newest request, so the case has to start with a real request.
            BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                    requestWithStub(), List.of(
                    new NearbyBattlesPacket.Entry(first, "A", 5, 1, 4, true, ""),
                    new NearbyBattlesPacket.Entry(second, "B", 9, 4, 4, false, "参战人数已达上限"))));
            BattleDiscoveryClientState.dismiss(first);
            assertEquals(List.of(second), BattleDiscoveryClientState.visibleEntries().stream()
                    .map(NearbyBattlesPacket.Entry::sessionId).toList(),
                    "暂不加入 hides only that row");

            // A background refresh must not undo the player's choice.
            BattleDiscoveryClientState.accept(new NearbyBattlesPacket(
                    BattleDiscoveryClientState.latestRequestId(), List.of(
                    new NearbyBattlesPacket.Entry(first, "A", 5, 2, 4, true, ""))));
            assertTrue(BattleDiscoveryClientState.visibleEntries().isEmpty(),
                    "a refresh keeps 暂不加入 honoured");

            // Reopening the page offers every row again.
            BattleDiscoveryClientState.undismissAll();
            assertEquals(1, BattleDiscoveryClientState.visibleEntries().size());
            assertEquals(first, BattleDiscoveryClientState.visibleEntries().get(0).sessionId());
        } finally {
            BattleDiscoveryClientState.transportForTesting(null);
            BattleDiscoveryClientState.clear();
        }
    }

    /** Sends one request through a stub transport and returns its id. */
    private static long requestWithStub() {
        BattleDiscoveryClientState.transportForTesting(new BattleDiscoveryClientState.Transport() {
            @Override public boolean ready() { return true; }
            @Override public long now() { return 1000L; }
            @Override public void requestSummary(long requestId) { }
            @Override public void joinSession(UUID sessionId) { }
        });
        BattleDiscoveryClientState.request();
        return BattleDiscoveryClientState.latestRequestId();
    }

    @Test
    void aSessionScopedClearNamesItsEncounterAndAnUnscopedOneDoesNot() {
        UUID session = UUID.randomUUID();
        assertFalse(BattleStatePacket.inactive().active());
        assertNull(BattleStatePacket.inactive().sessionId(),
                "login/respawn resync stays unconditional");
        BattleStatePacket scoped = BattleStatePacket.inactive(session);
        assertFalse(scoped.active());
        assertEquals(session, scoped.sessionId(),
                "a leave clear names the encounter so a newer one is not erased");
    }
}
