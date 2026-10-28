package com.tactical.neoforge.battle.ai;

/** Preserves the original approach-then-melee behavior for all unregistered mobs. */
public final class GenericMeleeAi implements TacticalMobAi {
    @Override
    public AiAction planTurn(AiContext context) {
        if (context.inMeleeRange(context.actorCell(), context.targetCell())) {
            return AiAction.of(AiAction.Kind.MELEE);
        }
        return AiApproach.towardPlayer(context, cell -> context.inMeleeRange(cell, context.targetCell()));
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        return context.inMeleeRange(context.actorCell(), context.targetCell())
                ? AiAction.of(AiAction.Kind.MELEE) : AiAction.endTurn();
    }
}
