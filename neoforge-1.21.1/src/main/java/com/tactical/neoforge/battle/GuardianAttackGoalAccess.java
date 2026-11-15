package com.tactical.neoforge.battle;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The one piece of a native attack goal's own arithmetic this mod may read and give back.
 *
 * <p>{@code Guardian.GuardianAttackGoal} keeps its pre-heat/charge counter in a private field. An admission that
 * borrows the body has to be able to snapshot and restore that arithmetic exactly - a body taken over mid-charge
 * must not come back looking like it had never charged - so it is reached through the goal's own mixin instead of
 * being guessed from tick counts or from the synched presentation.</p>
 */
public interface GuardianAttackGoalAccess {

    /** Vanilla's own {@code attackTime}: {@code -10} at a start, then one increment per driven tick. */
    int tactical$attackTime();

    /** Put that arithmetic back to a saved value, so a rolled-back admission is exactly what it was. */
    void tactical$attackTime(int attackTime);

    /** Whether {@code goal} is one of these goals at all. */
    static boolean supports(Goal goal) {
        return goal instanceof GuardianAttackGoalAccess;
    }
}
