package com.tactical.neoforge.battle.ai;

import com.tactical.core.combat.RangedWeapon;

/** One instance per participant, created by {@link TacticalAiRegistry}. */
public interface TacticalMobAi {
    AiAction planTurn(AiContext context);
    default AiAction afterMovement(AiContext context) { return AiAction.endTurn(); }
    default void onActionResolved(AiAction.Kind action) { }
    default void close() { }

    /**
     * The one ranged weapon type this adapter can fire, or {@code null} when it never shoots.
     *
     * <p>The server resolves the firing hand against this type instead of guessing from held items, so the
     * weapon the planner priced, the weapon the legality check validated and the native method that really
     * fires are one and the same. An adapter that fires nothing declares nothing and keeps the original melee
     * behaviour, whatever ranged item it happens to hold.</p>
     */
    default RangedWeapon rangedWeapon() { return null; }

    /**
     * Whether this adapter's actor may plan a wooden door breaking at all.
     *
     * <p>Only such an actor is given a door-aware terrain view and a door-route snapshot; every other melee
     * actor keeps the untouched navigator, so no other creature gains a door adjacency. An already open door is
     * handled by the same view, because the plain terrain adapter refuses the thin leaf of an open door as well.</p>
     */
    default boolean breaksDoors() { return false; }
}
