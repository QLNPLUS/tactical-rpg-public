package com.tactical.neoforge.battle.multi;

import com.tactical.neoforge.client.BattleMembership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-lifecycle identity of the membership counter.
 *
 * <p>A single-player JVM keeps its statics when the player leaves a world and enters another one, so the
 * second server instance must not publish the first instance's epoch: the counters restart at 1, and a
 * client that had already closed generation 2 would refuse its own new encounter. The rotation is driven
 * through the production stop seam ({@link MembershipGenerations#onServerStopped()}, the call the
 * server-stopped event makes) instead of hand-written epochs, and the client side is read through the
 * real {@link BattleMembership} entry points.</p>
 */
class MembershipGenerationEpochTest {

    private static final UUID PLAYER = UUID.fromString("bf8308eb-ed73-3ea9-b5fa-a235825ad642");
    private static final UUID SESSION = UUID.fromString("d3a11131-7a50-430c-8ccb-be00df1bd807");
    private static final UUID OTHER_SESSION = UUID.fromString("73ca698d-b4da-4c28-944d-2d2bda738f70");

    @BeforeEach
    void isolate() {
        BattleMembership.reset();
        MembershipGenerations.onServerStopped();
    }

    @AfterEach
    void forget() {
        BattleMembership.reset();
        MembershipGenerations.onServerStopped();
    }

    @Test
    void aSecondWorldInTheSameJvmPublishesANewEpochAndItsLowGenerationIsAccepted() {
        long firstEpoch = MembershipGenerations.epoch();
        MembershipGenerations.Membership first = MembershipGenerations.activate(PLAYER, SESSION);
        assertEquals(1L, first.generation(), "a fresh instance starts its counter at one");

        // the client joins, sees the membership, and closes it when the world is left
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(firstEpoch, first.generation(), SESSION, true));
        BattleMembership.beginConnection();
        assertEquals(first.generation(), BattleMembership.closedGeneration());

        // production stop seam: the next world is a different server instance in the same JVM
        MembershipGenerations.onServerStopped();
        long secondEpoch = MembershipGenerations.epoch();
        assertNotEquals(firstEpoch, secondEpoch, "a stopped server must not keep publishing its identity");

        MembershipGenerations.Membership second = MembershipGenerations.activate(PLAYER, OTHER_SESSION);
        assertEquals(1L, second.generation(), "the new instance counts from one again");

        BattleMembership.beginConnection();
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(secondEpoch, second.generation(), OTHER_SESSION, true),
                "the new world's generation 1 must not be refused by the previous world's closed generation");
        assertTrue(BattleMembership.active());
        assertEquals(OTHER_SESSION, BattleMembership.sessionId());
    }

    @Test
    void aPacketOfTheStoppedServerIsRefusedOnceTheNewEpochIsLatched() {
        long firstEpoch = MembershipGenerations.epoch();
        MembershipGenerations.activate(PLAYER, SESSION);
        MembershipGenerations.onServerStopped();
        long secondEpoch = MembershipGenerations.epoch();
        MembershipGenerations.Membership second = MembershipGenerations.activate(PLAYER, OTHER_SESSION);

        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(secondEpoch, second.generation(), OTHER_SESSION, true));
        assertEquals(BattleMembership.Decision.REJECT_OTHER_SERVER,
                BattleMembership.transition(firstEpoch, 7L, SESSION, true),
                "after the new epoch is latched the stopped server's epoch is unrelated to the live instance");
        assertTrue(BattleMembership.active());
        assertEquals(second.generation(), BattleMembership.generation());
    }

    /**
     * Not covered here on purpose: a stale higher generation of the previous epoch arriving before the
     * new instance's first packet. Whether the live client can observe that order depends on the real
     * handler/context sequence and is still under diagnosis, so no assertion is written for it.
     */

    @Test
    void aBattleResetInTheRunningServerKeepsTheEpochAndKeepsCountingUp() {
        MembershipGenerations.onServerStopped();
        long epoch = MembershipGenerations.epoch();
        assertEquals(1L, MembershipGenerations.activate(PLAYER, SESSION).generation());

        // a battle reset ends published memberships but never the server process
        MembershipGenerations.endMemberships();
        MembershipGenerations.Membership after = MembershipGenerations.activate(PLAYER, OTHER_SESSION);

        assertEquals(epoch, MembershipGenerations.epoch(), "a reset inside one server keeps the epoch");
        assertEquals(2L, after.generation(), "the counter keeps increasing so a live client can follow it");
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(epoch, after.generation(), OTHER_SESSION, true));
    }

    @Test
    void anOrdinaryReconnectOnTheSameServerStillRefusesTheOlderGeneration() {
        MembershipGenerations.onServerStopped();
        long epoch = MembershipGenerations.epoch();
        MembershipGenerations.Membership first = MembershipGenerations.activate(PLAYER, SESSION);
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(epoch, first.generation(), SESSION, true));

        BattleMembership.beginConnection();
        assertEquals(BattleMembership.Decision.REJECT_OLD_GENERATION,
                BattleMembership.transition(epoch, first.generation(), SESSION, true),
                "only a new epoch may restart the counter; a reconnect may not");

        MembershipGenerations.Membership second = MembershipGenerations.activate(PLAYER, OTHER_SESSION);
        assertEquals(first.generation() + 1, second.generation());
        assertEquals(BattleMembership.Decision.ACCEPT_TRANSITION,
                BattleMembership.transition(epoch, second.generation(), OTHER_SESSION, true));
    }
}
