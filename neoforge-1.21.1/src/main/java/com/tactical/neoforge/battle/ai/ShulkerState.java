package com.tactical.neoforge.battle.ai;

/**
 * What the shulker planner is allowed to know, read from the same state that prices and commits its actions.
 *
 * <p>Everything here is a decision the encounter already made: whether the body really holds an attachment,
 * whether a shot this turn would pass every gate, whether a relocation would. The planner never moves, never
 * prices and never searches - it only chooses among the answers it was given.</p>
 *
 * @param valid         this actor really is a controlled, attached shulker right now
 * @param mayShoot      one native shulker bullet would be legal from where the body really holds
 * @param shotCost      the frozen quote of that shot this turn, or {@code 0} when it cannot fire
 * @param mayTeleport   one native relocation would pass every landing gate this turn
 * @param teleportCost  the frozen quote of that relocation this turn, or {@code 0}
 * @param shotUsed      this own turn already fired its one main attack
 * @param relocated     this own turn already relocated; only one shot or the end may follow
 */
public record ShulkerState(boolean valid, boolean mayShoot, int shotCost,
                           boolean mayTeleport, int teleportCost,
                           boolean shotUsed, boolean relocated) {

    /** A context without a controlled shulker answers this, so every shulker tactic declines. */
    public static final ShulkerState NONE = new ShulkerState(false, false, 0, false, 0, false, false);

    public boolean canShootNow(int ap) {
        return valid && mayShoot && !shotUsed && shotCost > 0 && ap >= shotCost;
    }

    public boolean canTeleportNow(int ap) {
        // One main attack and one maneuver are separate per-kind quotas. A maneuver is offered while no shot is
        // available - it exists to buy one - and never twice, because it is not something a second maneuver buys.
        return valid && mayTeleport && !shotUsed && !relocated && teleportCost > 0 && ap >= teleportCost;
    }
}
