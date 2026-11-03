package com.tactical.neoforge.api.companion;

/**
 * One order a controlled companion may receive from its controller.
 *
 * <p>This is an advisory capability set, not a permission: the battle re-validates the phase, the queue
 * slot, the live owner, the cost, the range and the busy gate on every single intent. Declaring an
 * ability here only decides which orders the command surface offers; it can never widen what the server
 * accepts.</p>
 */
public enum CompanionAbility {
    /** Move along the existing ground grid path, paying its own move cost. */
    MOVE,
    /** Attack one legal hostile participant with its own native attack. */
    ATTACK,
    /** Open the companion skill page. Empty in this version: no companion skill is executed. */
    SKILL,
    /** Raise the shared no-shield guard for the next own turn and hand the turn over. */
    DEFEND,
    /** Hand the turn over after a facing confirmation, keeping the remaining AP. */
    END_TURN;

    /** A stable bit for the state snapshot, so the client never has to guess the set. */
    public int mask() {
        return 1 << ordinal();
    }

    /** Every ability, as one mask: the default for a fully capable companion. */
    public static int all() {
        int mask = 0;
        for (CompanionAbility ability : values()) mask |= ability.mask();
        return mask;
    }

    /** The bit this ability owns, or 0 for an unknown ordinal coming off the wire. */
    public static int maskOf(int ordinal) {
        CompanionAbility[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal].mask() : 0;
    }
}
