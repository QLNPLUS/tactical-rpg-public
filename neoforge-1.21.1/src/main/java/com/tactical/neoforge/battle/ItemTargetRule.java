package com.tactical.neoforge.battle;

/**
 * The one decision of "may this entity be the target of an item action", kept free of Minecraft types so
 * the rules can be checked without a running server.
 *
 * <p>It only answers eligibility: reachability and line of sight are supplied by the caller as facts it
 * measured on the real level, and the item itself still decides whether it supports an entity at all
 * (that answer comes from the native interaction, not from here).</p>
 *
 * <p>The target must be a participant of <em>this</em> encounter. Standing inside the battle zone is not
 * enough: an item hand-over is a battle action like any other, so it may not reach a bystander, another
 * fight's unit, or a unit that already left.</p>
 *
 * <p>The answer is a stable code, never a finished sentence: the wording belongs to the display boundary
 * ({@link BattleText#itemRefusal}), so each client reads the refusal in its own language and this rule
 * layer carries no client-facing language at all.</p>
 */
public final class ItemTargetRule {

    private ItemTargetRule() {
    }

    /** Why an entity may not be an item target; {@link #NONE} means it may. */
    public enum Denial {
        NONE,
        /** The entity lives in another dimension. */
        DIFFERENT_DIMENSION,
        /** The entity is dead or has been removed. */
        TARGET_GONE,
        /** The entity is the acting member itself. */
        SELF,
        /** The entity belongs to a different encounter. */
        OTHER_BATTLE,
        /** The entity is not a participant of this encounter (a bystander, or a unit that already left). */
        NOT_PARTICIPANT,
        /** The entity is outside the native entity interaction range. */
        TOO_FAR,
        /** The member cannot see the entity. */
        BLOCKED
    }

    /**
     * @param sameLevel     the entity lives in the member's own level
     * @param alive         the entity is alive
     * @param removed       the entity has been removed
     * @param self          the entity is the acting member itself
     * @param otherBattle   the entity belongs to a different encounter
     * @param notParticipant the entity is not a participant of this encounter
     * @param foreignPlayer the entity is another player that is not a member of this encounter
     * @param reachable     the member is within the native entity interaction range
     * @param lineOfSight   the member can see the entity
     * @return {@link Denial#NONE} when the target is eligible, otherwise the first refusal that applies
     */
    public static Denial refusal(boolean sameLevel, boolean alive, boolean removed, boolean self,
                                 boolean otherBattle, boolean notParticipant, boolean foreignPlayer,
                                 boolean reachable, boolean lineOfSight) {
        if (!sameLevel) return Denial.DIFFERENT_DIMENSION;
        if (removed || !alive) return Denial.TARGET_GONE;
        if (self) return Denial.SELF;
        if (otherBattle) return Denial.OTHER_BATTLE;
        if (notParticipant) return Denial.NOT_PARTICIPANT;
        if (foreignPlayer) return Denial.NOT_PARTICIPANT;
        if (!reachable) return Denial.TOO_FAR;
        if (!lineOfSight) return Denial.BLOCKED;
        return Denial.NONE;
    }
}
