package com.tactical.neoforge.battle.ai;

/**
 * Read-only enderman state the planner is allowed to see.
 *
 * <p>Everything here is decided by the server from the same state that prices and commits the teleport: how many
 * teleports this own turn may still spend, whether the unit is exposed to water or rain right now, whether an
 * unowned hurt left a vanilla escape pending, and whether the unit is in a phase that may not relocate at all
 * (falling, leaving, or in the middle of another controlled action).</p>
 *
 * <p>A planner never reads the world through this record, so a decision taken here is a decision about what the
 * unit wants, never a claim about what is legal: legality is asked again at the commit point.</p>
 */
public record EndermanState(int teleportsLeft, boolean exposed, boolean pendingEscape, boolean falling,
                            boolean busy, boolean safeLanding) {

    /** Nothing is known: no teleport, nothing exposed, no pending escape. */
    public static final EndermanState NONE = new EndermanState(0, false, false, false, true, false);

    /** Whether one more teleport may be attempted this own turn. */
    public boolean mayTeleport() {
        return teleportsLeft > 0 && !busy && !falling;
    }
}
