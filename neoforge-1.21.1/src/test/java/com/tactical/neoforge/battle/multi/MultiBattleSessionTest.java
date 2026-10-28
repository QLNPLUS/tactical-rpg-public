package com.tactical.neoforge.battle.multi;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.BattleSide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MP01 contract tests: roster isolation, stamped authority, invites and manager handover. */
class MultiBattleSessionTest {

    private static final long TTL = 20L * 60L;

    private static PlayerParticipant participant(UUID id, String name, int ap) {
        return new PlayerParticipant(id, name, new GridPos(0, 64, 0), ap, 150, 10, 100);
    }

    private static BattleEligibility.Facts eligible(int members, int capacity) {
        return BattleEligibility.Facts.of(true, true, false, true, true, false, members, capacity, false);
    }

    private static BattleSession sessionWithOneMember(int capacity, UUID manager) {
        BattleSession session = new BattleSession(capacity);
        session.addMember(participant(manager, "manager", 100));
        return session;
    }

    @Test
    void perPlayerResourcesAndMovementStayIsolated() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, first);
        session.addMember(participant(second, "second", 100));

        PlayerParticipant a = session.member(first);
        PlayerParticipant b = session.member(second);
        a.commitPaidAction(30);
        a.beginMove(List.of(new GridPos(1, 64, 0)), false, 3, 8);
        a.defending(true);
        b.commitPaidAction(10);
        b.cell(new GridPos(4, 70, 4));

        assertEquals(70, a.ap(), "attacker AP only");
        assertEquals(1, a.paidActions());
        assertEquals(90, b.ap(), "listener AP reflects only its own committed action");
        assertEquals(1, b.paidActions());
        assertTrue(a.moving());
        assertFalse(b.moving());
        assertTrue(a.defending());
        assertFalse(b.defending());
        assertEquals(new GridPos(4, 70, 4), b.cell());
        assertEquals(new GridPos(0, 64, 0), a.cell());

        a.refund(20, true);
        assertEquals(90, a.ap());
        assertEquals(0, a.paidActions(), "refund rolls back only this player's escalation");
        assertEquals(1, b.paidActions(), "another player's escalation counter is never refunded");
    }

    @Test
    void staleTurnGenerationCannotBeReplayed() {
        BattleSession session = sessionWithOneMember(4, UUID.randomUUID());
        ActionStamp stale = session.stamp();
        session.advanceTurnEpoch();
        assertFalse(session.accepts(stale), "previous turn epoch is not authoritative");
        assertTrue(session.accepts(session.stamp()));
    }

    @Test
    void foreignSessionAndClosedSessionAreRejected() {
        BattleSession session = sessionWithOneMember(4, UUID.randomUUID());
        assertFalse(session.accepts(ActionStamp.of(UUID.randomUUID(), session.turnEpoch())));
        assertFalse(session.accepts(null));
        session.close();
        assertFalse(session.accepts(session.stamp()));
    }

    @Test
    void onlyTheManagerMayInvite() {
        UUID manager = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.addMember(participant(other, "other", 100));

        assertEquals(BattleEligibility.Denial.NOT_MANAGER,
                session.invite(other, outsider, 0, TTL, eligible(2, 4)).denial());
        assertFalse(session.hasInvitation(outsider));
        assertEquals(BattleEligibility.Denial.NONE,
                session.invite(manager, outsider, 0, TTL, eligible(2, 4)).denial());
        assertTrue(session.hasInvitation(outsider));
    }

    @Test
    void anUnprivilegedRequesterIsNotManagerRatherThanUnresolved() {
        UUID manager = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.addMember(participant(member, "member", 100));

        // A player who never joined the encounter is a real actor without management rights.
        assertEquals(BattleEligibility.Denial.NOT_MANAGER,
                session.invite(outsider, target, 0, TTL, eligible(2, 4)).denial());
        assertFalse(session.hasInvitation(target), "an outsider's invitation creates nothing");

        // A joined member who is not the manager is refused the same way.
        assertEquals(BattleEligibility.Denial.NOT_MANAGER,
                session.invite(member, target, 0, TTL, eligible(2, 4)).denial());
        assertFalse(session.hasInvitation(target));

        // Only the manager succeeds, and only for an eligible target.
        assertEquals(BattleEligibility.Denial.NONE,
                session.invite(manager, target, 0, TTL, eligible(2, 4)).denial());
        assertTrue(session.hasInvitation(target));
        assertTrue(session.isManager(manager));
        assertFalse(session.isManager(member));
        assertFalse(session.isManager(outsider));
    }

    @Test
    void selfMemberAndDuplicateInvitesAreRefusedOrCollapsed() {
        UUID manager = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.addMember(participant(member, "member", 100));

        assertEquals(BattleEligibility.Denial.SELF_INVITE,
                session.invite(manager, manager, 0, TTL, eligible(2, 4)).denial());
        assertEquals(BattleEligibility.Denial.ALREADY_MEMBER,
                session.invite(manager, member, 0, TTL, eligible(2, 4)).denial());

        BattleSession.Invitation first = session.invite(manager, target, 0, TTL, eligible(2, 4)).invitation();
        BattleSession.Invitation second = session.invite(manager, target, 10, TTL, eligible(2, 4)).invitation();
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(second.issuedAt() > first.issuedAt(), "re-invite refreshes the single pending invite");
        assertEquals(List.of(target), session.invitedIds());
    }

    @Test
    void fullRosterRefusesInviteButNeverAnotherPlayersSlot() {
        UUID manager = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(2, manager);
        UUID third = UUID.randomUUID();
        // Owner count is 1 and capacity 2 with alreadyMember=false => one free slot.
        assertEquals(BattleEligibility.Denial.NONE,
                session.invite(manager, third, 0, TTL, eligible(1, 2)).denial());
        assertEquals(BattleEligibility.Denial.BATTLE_FULL,
                session.invite(manager, UUID.randomUUID(), 0, TTL, eligible(2, 2)).denial());
    }

    @Test
    void expiredInviteIsPrunedAndCannotBeAccepted() {
        UUID manager = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.invite(manager, target, 0, 100, eligible(1, 4));

        assertNull(session.invitation(target, 101));
        BattleSession.JoinResult result = session.accept(target, 101, eligible(1, 4),
                participant(target, "target", 100));
        assertEquals(BattleEligibility.Denial.NO_INVITE, result.denial());
        assertFalse(session.isMember(target));
    }

    @Test
    void decliningIsExplicitAndRepeatable() {
        UUID manager = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.invite(manager, target, 0, TTL, eligible(1, 4));

        assertEquals(BattleEligibility.Denial.NONE, session.decline(target));
        assertEquals(BattleEligibility.Denial.NO_INVITE, session.decline(target));
        assertFalse(session.isMember(target));
        assertEquals(BattleEligibility.Denial.NO_INVITE,
                session.accept(target, 0, eligible(1, 4), participant(target, "target", 100)).denial());
    }

    @Test
    void acceptRevalidatesEligibilityAndKeepsInviteForRetry() {
        UUID manager = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.invite(manager, target, 0, TTL, eligible(1, 4));

        BattleEligibility.Facts dead = BattleEligibility.Facts.of(true, false, false, true, true,
                false, 1, 4, false);
        assertEquals(BattleEligibility.Denial.DEAD,
                session.accept(target, 5, dead, participant(target, "target", 100)).denial());
        assertFalse(session.isMember(target));
        assertTrue(session.hasInvitation(target), "a failed re-check must not burn the invitation");

        BattleSession.JoinResult joined = session.accept(target, 6, eligible(1, 4),
                participant(target, "target", 100));
        assertTrue(joined.joined());
        assertTrue(session.isMember(target));
        assertFalse(session.hasInvitation(target));

        assertEquals(BattleEligibility.Denial.NO_INVITE,
                session.accept(target, 7, eligible(1, 4), participant(target, "target", 100)).denial(),
                "an accepted invitation is consumed exactly once");
    }

    @Test
    void acceptWithoutResolvedEntityIsRefused() {
        UUID manager = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.invite(manager, target, 0, TTL, eligible(1, 4));
        assertEquals(BattleEligibility.Denial.UNRESOLVED,
                session.accept(target, 0, eligible(1, 4), null).denial());
        assertEquals(BattleEligibility.Denial.UNRESOLVED,
                session.accept(target, 0, eligible(1, 4), participant(UUID.randomUUID(), "x", 1)).denial());
    }

    @Test
    void managerHandoverGoesToEarliestSurvivingMember() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, first);
        session.addMember(participant(second, "second", 100));
        session.addMember(participant(third, "third", 100));
        assertEquals(first, session.managerId());

        BattleSession.LeaveResult result = session.leave(first);
        assertTrue(result.removed());
        assertTrue(result.managerChanged());
        assertFalse(result.finished());
        assertEquals(second, result.newManager());
        assertEquals(second, session.managerId());
        assertFalse(session.isMember(first));
        assertEquals(2, session.memberCount());

        BattleSession.LeaveResult memberLeaves = session.leave(third);
        assertTrue(memberLeaves.removed());
        assertFalse(memberLeaves.managerChanged(), "a non-manager leaving keeps the manager");
        assertEquals(second, session.managerId());
    }

    @Test
    void onlyTheLastLeavingMemberFinishesTheSession() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, first);
        session.addMember(participant(second, "second", 100));

        assertFalse(session.leave(first).finished());
        BattleSession.LeaveResult last = session.leave(second);
        assertTrue(last.finished());
        assertTrue(session.closed());
        assertNull(session.managerId());
        assertFalse(session.leave(second).removed(), "leaving twice is idempotent");
        assertEquals(0, session.memberCount());
    }

    @Test
    void leavingMemberDoesNotDisturbOtherPendingInvites() {
        UUID manager = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        session.addMember(participant(member, "member", 100));
        session.invite(manager, pending, 0, TTL, eligible(2, 4));
        assertTrue(session.hasInvitation(pending));

        assertTrue(session.leave(member).removed());
        assertTrue(session.hasInvitation(pending),
                "one member leaving never clears another player's pending invitation");
        assertEquals(BattleEligibility.Denial.NONE,
                session.accept(pending, 1, eligible(1, 4), participant(pending, "pending", 100)).denial());
    }

    @Test
    void turnEpochIsSharedButResourcesAreNot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, first);
        session.addMember(participant(second, "second", 100));
        long epoch = session.advanceTurnEpoch();
        assertEquals(epoch, session.turnEpoch());
        assertTrue(session.accepts(ActionStamp.of(session.sessionId(), epoch)));
        assertFalse(session.accepts(ActionStamp.of(session.sessionId(), epoch - 1)));
        assertEquals(150, session.member(second).maxAp());
    }

    @Test
    void pveRelationPolicyKeepsPlayersAlliedAndMobsOpposed() {
        BattleRelationPolicy policy = BattleRelationPolicy.pve();
        assertTrue(policy.allied(BattleSide.PLAYER, BattleSide.PLAYER));
        assertTrue(policy.allied(BattleSide.HOSTILE, BattleSide.HOSTILE));
        assertTrue(policy.opposed(BattleSide.PLAYER, BattleSide.HOSTILE));
        assertTrue(policy.opposed(BattleSide.HOSTILE, BattleSide.PLAYER));
        assertEquals(BattleRelationPolicy.Relation.UNRELATED, policy.relationOf((BattleSide) null, BattleSide.PLAYER));
        assertEquals(BattleRelationPolicy.Relation.UNRELATED, policy.relationOf(BattleSide.PLAYER, (BattleSide) null));
    }

    @Test
    void eligibilitySeparatesParticipationFromAffiliation() {
        assertEquals(BattleEligibility.Denial.NONE,
                BattleEligibility.evaluate(BattleEligibility.Facts.of(true, true, false, true, true,
                        false, 1, 4, false)));
        assertEquals(BattleEligibility.Denial.DIFFERENT_DIMENSION,
                BattleEligibility.evaluate(BattleEligibility.Facts.of(true, true, false, false, true,
                        false, 1, 4, false)));
        assertEquals(BattleEligibility.Denial.OUTSIDE_ZONE,
                BattleEligibility.evaluate(BattleEligibility.Facts.of(true, true, false, true, false,
                        false, 1, 4, false)));
        assertEquals(BattleEligibility.Denial.SPECTATOR,
                BattleEligibility.evaluate(BattleEligibility.Facts.of(true, true, true, true, true,
                        false, 1, 4, false)));
        assertEquals(BattleEligibility.Denial.ALREADY_MEMBER,
                BattleEligibility.evaluate(BattleEligibility.Facts.of(true, true, false, true, true,
                        true, 1, 4, false)));
        assertEquals(BattleEligibility.Denial.SESSION_CLOSED,
                BattleEligibility.evaluate(BattleEligibility.Facts.of(true, true, false, true, true,
                        false, 1, 4, true)));
        assertEquals(BattleEligibility.Denial.OFFLINE, BattleEligibility.evaluate(null));
    }

    @Test
    void sessionIndexResolvesByPlayerAndBySession() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID sessionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(index.register(sessionId, "battle"), "a new session is registered");
        assertTrue(index.bindAll(sessionId, List.of(first, second)));

        assertEquals("battle", index.findById(sessionId));
        assertEquals("battle", index.findFor(first));
        assertEquals("battle", index.findFor(second));
        assertEquals("battle", index.primary());
        assertNull(index.findFor(UUID.randomUUID()));
        assertNull(index.findById(UUID.randomUUID()));
        assertEquals(sessionId, index.ownerOf(second));

        assertTrue(index.unbind(sessionId, second));
        assertNull(index.findFor(second), "a departed player no longer routes to the encounter");
        assertEquals("battle", index.findFor(first));

        index.remove(sessionId);
        assertNull(index.findFor(first));
        assertNull(index.primary());
        assertEquals(0, index.size());
    }

    @Test
    void sessionIndexSnapshotCannotBeMutatedByCallers() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID sessionId = UUID.randomUUID();
        index.register(sessionId, "battle");
        index.bind(sessionId, UUID.randomUUID());
        var snapshot = index.all();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.clear(), "all() must not be a live view of the index");
        assertEquals(1, index.size());
    }

    @Test
    void aSecondSessionCannotStealAnAlreadyOwnedActor() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertTrue(index.register(first, "first"));
        assertTrue(index.register(second, "second"));
        assertTrue(index.bind(first, player));

        // The whole point: a new encounter reports the conflict and the older binding survives.
        assertFalse(index.bind(second, player), "an owned actor is never rebound to another session");
        assertEquals("first", index.findFor(player));
        assertFalse(index.bindAll(second, List.of(UUID.randomUUID(), player)),
                "a group claim refuses as a whole when one actor is owned elsewhere");
        assertEquals(first, index.ownerOf(player));
        assertEquals(2, index.sessionCount(), "no session entry is lost by a refused claim");
    }

    @Test
    void groupClaimIsAtomicAndIdempotentForItsOwnActors() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID sessionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        index.register(sessionId, "battle");

        assertTrue(index.bindAll(sessionId, List.of(first, second)));
        assertEquals(List.of(first, second), index.actorsOf(sessionId), "claim order is preserved");
        assertTrue(index.bindAll(sessionId, List.of(first)), "re-claiming an own actor is idempotent");
        assertEquals(2, index.actorsOf(sessionId).size());
        assertFalse(index.bind(UUID.randomUUID(), first), "an unregistered session claims nothing");
        assertNull(index.ownerOf(UUID.randomUUID()));
    }

    @Test
    void unbindRequiresTheExpectedOwningSession() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        index.register(first, "first");
        index.register(second, "second");
        index.bind(second, player);

        assertFalse(index.unbind(first, player),
                "a stale encounter cannot release another encounter's player");
        assertEquals("second", index.findFor(player));
        assertTrue(index.unbind(second, player));
        assertNull(index.findFor(player));
        assertFalse(index.unbind(second, player), "releasing twice is a no-op");
    }

    @Test
    void removingOneSessionKeepsEveryOtherOwnership() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        index.register(first, "first");
        index.register(second, "second");
        index.bind(first, firstPlayer);
        index.bind(second, secondPlayer);

        index.remove(first);
        assertNull(index.findById(first));
        assertNull(index.findFor(firstPlayer), "A's binding goes with A");
        assertEquals("second", index.findFor(secondPlayer), "B is untouched by A's removal");
        assertEquals(List.of(secondPlayer), index.actorsOf(second));
        assertEquals("second", index.primary(), "a lone survivor is the compatibility view");
    }

    @Test
    void primaryAnswersOnlyForExactlyOneSession() {
        SessionIndex<String> index = new SessionIndex<>();
        assertNull(index.primary(), "no session means no compatibility view");
        UUID first = UUID.randomUUID();
        index.register(first, "first");
        assertEquals("first", index.primary());
        UUID second = UUID.randomUUID();
        index.register(second, "second");
        assertNull(index.primary(), "two live sessions must not be guessed into one");
        assertEquals(2, index.all().size(), "the snapshot still lists every session");
        index.remove(first);
        assertEquals("second", index.primary(), "back to one session, back to a compatibility view");
    }

    @Test
    void registerRefusesToShadowALiveSession() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID sessionId = UUID.randomUUID();
        assertTrue(index.register(sessionId, "battle"));
        assertTrue(index.register(sessionId, "battle"), "the same session registers idempotently");
        assertFalse(index.register(sessionId, "other"), "a second encounter cannot take the id over");
        assertEquals("battle", index.findById(sessionId));
    }

    @Test
    void substitutedRelationPolicySeesBothIdentities() {
        // A future faction policy resolves allegiance from the actor UUIDs, not from the side.
        UUID friendly = UUID.randomUUID();
        UUID hostile = UUID.randomUUID();
        BattleRelationPolicy factions = (self, other) -> {
            if (self.entityId().equals(other.entityId()))
                return BattleRelationPolicy.Relation.ALLY;
            if (self.entityId().equals(hostile) || other.entityId().equals(hostile))
                return BattleRelationPolicy.Relation.OPPOSED;
            return BattleRelationPolicy.Relation.UNRELATED;
        };
        BattleRelationPolicy.CombatantRef first =
                new BattleRelationPolicy.CombatantRef(friendly, BattleSide.PLAYER);
        BattleRelationPolicy.CombatantRef second =
                new BattleRelationPolicy.CombatantRef(hostile, BattleSide.PLAYER);
        assertTrue(factions.allied(first, first));
        assertTrue(factions.opposed(first, second),
                "identity beats the default side, which is what a capture-the-flag rule needs");
        assertFalse(factions.opposed(second, second));
    }

    @Test
    void defaultPvePolicyIgnoresIdentityButKeepsSideRules() {
        BattleRelationPolicy policy = BattleRelationPolicy.pve();
        UUID anyId = UUID.randomUUID();
        BattleRelationPolicy.CombatantRef player =
                new BattleRelationPolicy.CombatantRef(anyId, BattleSide.PLAYER);
        BattleRelationPolicy.CombatantRef mob =
                new BattleRelationPolicy.CombatantRef(UUID.randomUUID(), BattleSide.HOSTILE);
        assertTrue(policy.allied(player, player));
        assertTrue(policy.opposed(player, mob));
        assertEquals(BattleRelationPolicy.Relation.UNRELATED, policy.relationOf(null, mob));
    }

    @Test
    void claimDistinguishesANewClaimFromAPreExistingOne() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID session = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        index.register(session, "battle");
        index.register(other, "other");

        assertEquals(SessionIndex.Claim.NEW, index.claim(session, actor));
        // This is what a rollback must not mistake for its own claim: the session already owned it.
        assertEquals(SessionIndex.Claim.OWNED, index.claim(session, actor));
        assertEquals(SessionIndex.Claim.CONFLICT, index.claim(other, actor));
        assertEquals(SessionIndex.Claim.UNKNOWN_SESSION, index.claim(UUID.randomUUID(), actor));
        assertEquals(session, index.ownerOf(actor), "no refused claim moves an existing owner");
        assertEquals(List.of(actor), index.actorsOf(session));
    }

    @Test
    void aRefusedReacceptMustNotReleaseAnExistingMemberBinding() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID session = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        index.register(session, "battle");
        index.claim(session, actor);

        // Rollback rule: only a claim this call created may be released. Here the call found the
        // actor already owned, so a refused accept leaves the binding in place.
        SessionIndex.Claim claim = index.claim(session, actor);
        boolean newlyClaimed = claim == SessionIndex.Claim.NEW;
        assertFalse(newlyClaimed);
        if (newlyClaimed) index.unbind(session, actor);
        assertEquals(session, index.ownerOf(actor), "the legitimate member keeps its routing");
    }

    @Test
    void aConsumedInvitationCanBeRestoredAfterTheMemberIsRolledBack() {
        UUID manager = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        BattleSession.Invitation invitation =
                session.invite(manager, target, 0, TTL, eligible(1, 4)).invitation();
        assertNotNull(invitation);
        assertTrue(session.accept(target, 1, eligible(1, 4), participant(target, "target", 100)).joined());
        assertFalse(session.hasInvitation(target), "accepting consumes the invitation");

        // The rollback order used by ServerBattle: drop the member first, then give the invite back.
        assertTrue(session.leave(target).removed());
        assertTrue(session.restoreInvitation(invitation));
        assertTrue(session.hasInvitation(target));
        assertEquals(BattleEligibility.Denial.NONE, session.decline(target), "the restored invitation is usable");
    }

    @Test
    void restoreInvitationRefusesAMemberOrAForeignInvitation() {
        UUID manager = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BattleSession session = sessionWithOneMember(4, manager);
        BattleSession.Invitation invitation =
                session.invite(manager, target, 0, TTL, eligible(1, 4)).invitation();
        assertNotNull(invitation);

        session.addMember(participant(target, "target", 100));
        assertFalse(session.restoreInvitation(invitation),
                "a restore never re-opens an invitation for a live member");
        assertSame(invitation, session.invitation(target, 0),
                "the refused restore leaves the pending invitation untouched");

        BattleSession other = sessionWithOneMember(4, manager);
        assertFalse(other.restoreInvitation(invitation), "a foreign session's invitation is refused");
        assertFalse(other.hasInvitation(target));
    }

    @Test
    void stampEqualityIsValueBased() {
        UUID session = UUID.randomUUID();
        assertEquals(ActionStamp.of(session, 3), ActionStamp.of(session, 3));
        assertNotEquals(ActionStamp.of(session, 3), ActionStamp.of(session, 4));
        assertEquals(ActionStamp.of(session, 3).hashCode(), ActionStamp.of(session, 3).hashCode());
        assertTrue(ActionStamp.of(session, 3).sameSession(ActionStamp.of(session, 9)));
        assertFalse(ActionStamp.of(session, 3).sameSession(ActionStamp.of(UUID.randomUUID(), 3)));
    }
}
