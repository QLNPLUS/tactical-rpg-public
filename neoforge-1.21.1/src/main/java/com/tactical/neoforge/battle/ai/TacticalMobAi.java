package com.tactical.neoforge.battle.ai;

/** One instance per participant, created by {@link TacticalAiRegistry}. */
public interface TacticalMobAi {
    AiAction planTurn(AiContext context);
    default AiAction afterMovement(AiContext context) { return AiAction.endTurn(); }
    default void onActionResolved(AiAction.Kind action) { }
    default void close() { }
}
