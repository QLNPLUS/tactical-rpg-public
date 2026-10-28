package com.tactical.neoforge.battle.multi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One encounter's multiplayer roster, invitation book and turn generation.
 *
 * <p>Holds the authoritative {@code Map<UUID, PlayerParticipant>}: every player's AP,
 * escalation counter, move cost, defence flag and movement animation is stored per
 * player here, never as a single-owner field on the battle. The encounter itself is
 * still a single global battle (approved scope A); this type only removes the
 * "one owner" assumption, so adding session isolation later is a registry change.</p>
 *
 * <p>Pure model: no Minecraft types, so roster/invite/authority rules are unit tested
 * directly. Entity resolution and packet fan-out stay in the server battle.</p>
 */
public final class BattleSession {

    public static final long DEFAULT_INVITE_TTL_TICKS = 20L * 60L;

    public record Invitation(UUID sessionId, UUID inviter, UUID invitee, long issuedAt, long expiresAt) {
        public Invitation {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(invitee, "invitee");
        }

        public boolean live(long now) { return now <= expiresAt; }
    }

    public record InviteResult(BattleEligibility.Denial denial, Invitation invitation) {
        public boolean accepted() { return denial == BattleEligibility.Denial.NONE; }
    }

    public record JoinResult(BattleEligibility.Denial denial, PlayerParticipant member) {
        public boolean joined() { return denial == BattleEligibility.Denial.NONE && member != null; }
    }

    public record LeaveResult(boolean removed, boolean managerChanged, boolean finished, UUID newManager) {
        public static LeaveResult noop() { return new LeaveResult(false, false, false, null); }
    }

    private final UUID sessionId = UUID.randomUUID();
    private final int capacity;
    private final Map<UUID, PlayerParticipant> members = new LinkedHashMap<>();
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();
    private UUID managerId;
    private long turnEpoch;
    private boolean closed;

    public BattleSession(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    // ------------------------------------------------------------------ identity

    public UUID sessionId() { return sessionId; }

    public int capacity() { return capacity; }

    public boolean closed() { return closed; }

    public void close() { closed = true; }

    public UUID managerId() { return managerId; }

    public boolean isManager(UUID playerId) { return !closed && playerId != null && playerId.equals(managerId); }

    public List<PlayerParticipant> members() { return List.copyOf(members.values()); }

    public List<UUID> memberIds() { return List.copyOf(members.keySet()); }

    public int memberCount() { return members.size(); }

    public PlayerParticipant member(UUID playerId) { return playerId == null ? null : members.get(playerId); }

    public boolean isMember(UUID playerId) { return playerId != null && members.containsKey(playerId); }

    /** First joined member becomes manager; later members never steal it implicitly. */
    public void addMember(PlayerParticipant participant) {
        Objects.requireNonNull(participant, "participant");
        if (members.putIfAbsent(participant.playerId(), participant) == null && managerId == null) {
            managerId = participant.playerId();
        }
    }

    // ------------------------------------------------------------------ turn epoch

    public long turnEpoch() { return turnEpoch; }

    /** Called exactly once per authoritative turn start; invalidates every older intent. */
    public long advanceTurnEpoch() { return ++turnEpoch; }

    public ActionStamp stamp() { return new ActionStamp(sessionId, turnEpoch); }

    /** Rejects a dead session and any stamp from an older turn generation. */
    public boolean accepts(ActionStamp stamp) {
        return !closed && stamp != null && stamp.matches(sessionId, turnEpoch);
    }

    // ------------------------------------------------------------------ invitations

    public InviteResult invite(UUID requester, UUID invitee, long now, long ttlTicks,
                               BattleEligibility.Facts targetFacts) {
        if (closed) return new InviteResult(BattleEligibility.Denial.SESSION_CLOSED, null);
        if (!isManager(requester)) return new InviteResult(BattleEligibility.Denial.NOT_MANAGER, null);
        if (invitee == null) return new InviteResult(BattleEligibility.Denial.UNRESOLVED, null);
        if (invitee.equals(requester)) return new InviteResult(BattleEligibility.Denial.SELF_INVITE, null);
        if (isMember(invitee)) return new InviteResult(BattleEligibility.Denial.ALREADY_MEMBER, null);
        BattleEligibility.Denial denial = BattleEligibility.evaluate(targetFacts);
        if (denial != BattleEligibility.Denial.NONE) return new InviteResult(denial, null);
        long ttl = ttlTicks <= 0L ? DEFAULT_INVITE_TTL_TICKS : ttlTicks;
        Invitation invitation = new Invitation(sessionId, requester, invitee, now, now + ttl);
        // Re-inviting the same player refreshes the existing invitation instead of stacking.
        invitations.put(invitee, invitation);
        return new InviteResult(BattleEligibility.Denial.NONE, invitation);
    }

    /** Live invitation for this player, pruning an expired one; null when none is pending. */
    public Invitation invitation(UUID invitee, long now) {
        Invitation invitation = invitee == null ? null : invitations.get(invitee);
        if (invitation == null) return null;
        if (!invitation.live(now)) {
            invitations.remove(invitee);
            return null;
        }
        return invitation;
    }

    public boolean hasInvitation(UUID invitee) { return invitee != null && invitations.containsKey(invitee); }

    /**
     * Rollback-only: put back the invitation that an immediately preceding {@code accept} consumed
     * and whose join then had to be undone.
     *
     * <p>The invitation is this session's own authoritative record, so it is restored as-is instead
     * of being re-validated. A closed session, a foreign session id or a player who is already a
     * member is refused, so this can never re-open a live membership.</p>
     */
    public boolean restoreInvitation(Invitation invitation) {
        if (closed || invitation == null || !sessionId.equals(invitation.sessionId())) return false;
        if (invitation.invitee() == null || isMember(invitation.invitee())) return false;
        invitations.put(invitation.invitee(), invitation);
        return true;
    }

    /** Idempotent: declining twice is not an error and never touches another player's invite. */
    public BattleEligibility.Denial decline(UUID invitee) {
        if (closed) return BattleEligibility.Denial.SESSION_CLOSED;
        Invitation removed = invitee == null ? null : invitations.remove(invitee);
        return removed == null ? BattleEligibility.Denial.NO_INVITE : BattleEligibility.Denial.NONE;
    }

    /**
     * Explicit accept. The caller re-resolves live entity facts and builds the participant
     * from current native attributes; nothing from the invite is trusted as state.
     * Eligibility is re-checked here so an invite cannot outlive a death, an exit from the
     * zone, a dimension change or a roster that filled up meanwhile.
     */
    public JoinResult accept(UUID invitee, long now, BattleEligibility.Facts facts,
                             PlayerParticipant resolved) {
        if (closed) return new JoinResult(BattleEligibility.Denial.SESSION_CLOSED, null);
        Invitation invitation = invitee == null ? null : invitations.get(invitee);
        if (invitation == null) return new JoinResult(BattleEligibility.Denial.NO_INVITE, null);
        if (!invitation.sessionId().equals(sessionId)) {
            invitations.remove(invitee);
            return new JoinResult(BattleEligibility.Denial.SESSION_MISMATCH, null);
        }
        if (!invitation.live(now)) {
            invitations.remove(invitee);
            return new JoinResult(BattleEligibility.Denial.INVITE_EXPIRED, null);
        }
        if (isMember(invitee)) {
            invitations.remove(invitee);
            return new JoinResult(BattleEligibility.Denial.ALREADY_MEMBER, null);
        }
        BattleEligibility.Denial denial = BattleEligibility.evaluate(facts);
        if (denial != BattleEligibility.Denial.NONE) return new JoinResult(denial, null);
        if (resolved == null || !resolved.playerId().equals(invitee)) {
            return new JoinResult(BattleEligibility.Denial.UNRESOLVED, null);
        }
        invitations.remove(invitee);
        addMember(resolved);
        return new JoinResult(BattleEligibility.Denial.NONE, resolved);
    }

    public int expireInvitations(long now) {
        int before = invitations.size();
        invitations.values().removeIf(invitation -> !invitation.live(now));
        return before - invitations.size();
    }

    /**
     * Explicit self-service join: the player chose this encounter in the nearby list, so no
     * invitation is involved and nobody has to approve it.
     *
     * <p>Every other rule of {@link #accept} still applies - closed session, already a member, live
     * eligibility (including capacity and dimension) and a resolved participant - and a pending
     * invitation for the same player is consumed so it cannot be replayed. The payload never decides
     * any of this; the server resolves the candidate itself.</p>
     */
    public JoinResult acceptDirect(UUID joiner, long now, BattleEligibility.Facts facts,
                                   PlayerParticipant resolved) {
        if (closed) return new JoinResult(BattleEligibility.Denial.SESSION_CLOSED, null);
        if (joiner == null) return new JoinResult(BattleEligibility.Denial.UNRESOLVED, null);
        if (isMember(joiner)) return new JoinResult(BattleEligibility.Denial.ALREADY_MEMBER, null);
        BattleEligibility.Denial denial = BattleEligibility.evaluate(facts);
        if (denial != BattleEligibility.Denial.NONE) return new JoinResult(denial, null);
        if (resolved == null || !resolved.playerId().equals(joiner)) {
            return new JoinResult(BattleEligibility.Denial.UNRESOLVED, null);
        }
        invitations.remove(joiner);
        addMember(resolved);
        return new JoinResult(BattleEligibility.Denial.NONE, resolved);
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Removes exactly one member. The manager role moves to the earliest still-present
     * member (join order); the encounter only reports {@code finished} when the last
     * member has left. Leaving twice is a no-op.
     */
    public LeaveResult leave(UUID playerId) {
        if (playerId == null || !members.containsKey(playerId)) return LeaveResult.noop();
        members.remove(playerId);
        invitations.values().removeIf(invitation -> invitation.invitee().equals(playerId));
        boolean wasManager = playerId.equals(managerId);
        if (members.isEmpty()) {
            managerId = null;
            closed = true;
            return new LeaveResult(true, wasManager, true, null);
        }
        UUID newManager = managerId;
        if (wasManager) {
            newManager = members.keySet().iterator().next();
            managerId = newManager;
        }
        return new LeaveResult(true, wasManager, false, newManager);
    }

    /** Snapshot copy used by tests and diagnostics; never exposes the live maps. */
    public Map<UUID, String> describeMembers() {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerParticipant> entry : members.entrySet()) {
            result.put(entry.getKey(), entry.getValue().name());
        }
        return result;
    }

    public List<UUID> invitedIds() { return new ArrayList<>(invitations.keySet()); }
}
