package com.tactical.neoforge.battle.ai;

/**
 * What the breeze planner is allowed to know, read from the same state that prices and commits its actions.
 *
 * <p>Everything here is a decision the encounter already made: whether a native wind charge would pass every gate
 * from where the body really stands, whether one native long jump has a proved landing this turn, and what each
 * of them costs right now. The planner never searches, never prices and never moves - it chooses among answers it
 * was given.</p>
 *
 * <p>The two quotas are per kind on purpose. The shared successful-action surcharge is a different counter: a
 * jump advances it, and reading it as "the shot is spent" would make the one shot a jump exists for impossible -
 * exactly the mistake the shell family already had to repair.</p>
 *
 * @param valid      this actor really is a controlled breeze right now
 * @param mayShoot   one native wind charge would be legal from where the body really stands
 * @param shotCost   the frozen quote of that shot this turn, or {@code 0} when it cannot fire
 * @param mayJump    one native long jump has a proved landing and buys a firing position this turn
 * @param jumpCost   the frozen quote of that jump this turn, or {@code 0}
 * @param shotUsed   this own turn already fired its one main attack
 * @param maneuvered this own turn already jumped; only one shot or the end may follow
 */
public record BreezeState(boolean valid, boolean mayShoot, int shotCost,
                          boolean mayJump, int jumpCost,
                          boolean shotUsed, boolean maneuvered) {

    /** A context without a controlled breeze answers this, so every breeze tactic declines. */
    public static final BreezeState NONE = new BreezeState(false, false, 0, false, 0, false, false);

    public boolean canShootNow(int ap) {
        return valid && mayShoot && !shotUsed && shotCost > 0 && ap >= shotCost;
    }

    public boolean canJumpNow(int ap) {
        return valid && mayJump && !shotUsed && !maneuvered && jumpCost > 0 && ap >= jumpCost;
    }
}
