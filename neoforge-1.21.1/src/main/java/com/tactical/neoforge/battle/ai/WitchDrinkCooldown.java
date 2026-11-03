package com.tactical.neoforge.battle.ai;

/**
 * The shared self-drink cooldown of one witch, counted in her own turns instead of wall-clock ticks.
 *
 * <p>One instance belongs to one witch inside one encounter, so two witches never share a cooldown and a
 * second encounter cannot shorten the first one's. Only {@link #settle(int)} moves it, and the server calls
 * that from the single place where a drink really took effect: planning, a refusal, insufficient AP or
 * someone else's turn cannot advance or reset it. With a gap of three she may drink on her first own turn,
 * not on the second or third, and again on the fourth.</p>
 */
public final class WitchDrinkCooldown {
    private final int gapTurns;
    private int readyTurn = 1;

    public WitchDrinkCooldown(int gapTurns) {
        this.gapTurns = Math.max(1, gapTurns);
    }

    /** True when a drink is allowed on the own turn with this one-based index. */
    public boolean ready(int ownTurns) {
        return ownTurns >= readyTurn;
    }

    /** This own turn really spent a drink; the next allowed turn is {@code gapTurns} turns later. */
    public void settle(int ownTurns) {
        readyTurn = ownTurns + gapTurns;
    }

    /** The first own-turn index that may drink again, for diagnostics and fixtures. */
    public int readyTurn() { return readyTurn; }

    /** The configured gap this instance was built with. */
    public int gapTurns() { return gapTurns; }
}
