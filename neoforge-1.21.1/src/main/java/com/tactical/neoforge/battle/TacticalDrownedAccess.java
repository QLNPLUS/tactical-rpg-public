package com.tactical.neoforge.battle;

/**
 * The one drowned-owned flag this encounter has to hold, exposed by the mixin and nothing else.
 *
 * <p>Vanilla {@code Drowned#updateSwimming} only enters its swimming state when
 * {@code isEffectiveAi()} is true, which the NoAI lock that every admitted unit carries always makes false.
 * A drowned in water would therefore never show its own swimming pose, and it would keep being pushed by
 * fluid because {@code isPushedByFluid()} answers {@code !isSwimming()}. The mixin answers this flag so the
 * encounter can present the vanilla pose and the vanilla "is swimming" answer while it holds the unit, and
 * gives it back the moment control ends.</p>
 */
public interface TacticalDrownedAccess {

    void tactical$setControlled(boolean controlled);

    boolean tactical$isControlled();
}
