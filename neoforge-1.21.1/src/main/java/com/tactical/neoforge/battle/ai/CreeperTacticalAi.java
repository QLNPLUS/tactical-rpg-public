package com.tactical.neoforge.battle.ai;

/** Lifecycle belongs to one creeper in one encounter; no tick-based fuse. */
public final class CreeperTacticalAi implements TacticalMobAi {
    public enum State { APPROACHING, PREPARING, DETONATED }
    private State state = State.APPROACHING;

    public State state() { return state; }

    public static boolean canPrepare(AiContext context) {
        return BlastWarningGeometry.inPreparationRange(context.actorCell(), context.targetCell())
                && context.hasLineOfSight(context.actorCell(), context.targetCell());
    }

    @Override
    public AiAction planTurn(AiContext context) {
        // Only the next call at the START of this creeper's own turn detonates.
        // afterMovement deliberately cannot return DETONATE.
        if (state == State.PREPARING) return AiAction.of(AiAction.Kind.DETONATE);
        if (state == State.DETONATED) return AiAction.endTurn();
        if (canPrepare(context)) return AiAction.of(AiAction.Kind.PREPARE_EXPLOSION);
        return AiApproach.towardPlayer(context, cell ->
                BlastWarningGeometry.inPreparationRange(cell, context.targetCell())
                        && context.hasLineOfSight(cell, context.targetCell()));
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        return state == State.APPROACHING && canPrepare(context)
                ? AiAction.of(AiAction.Kind.PREPARE_EXPLOSION) : AiAction.endTurn();
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.PREPARE_EXPLOSION && state == State.APPROACHING) state = State.PREPARING;
        if (action == AiAction.Kind.DETONATE && state == State.PREPARING) state = State.DETONATED;
    }
}
