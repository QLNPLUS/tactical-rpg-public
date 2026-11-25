package com.tactical.neoforge.battle.ai;

import net.minecraft.world.entity.Mob;

/**
 * Shulker tactics: one native bullet, one relocation, and nothing invented in between.
 *
 * <p>A shulker does not walk, does not swing and cannot be pushed. Its whole vocabulary is a shell that opens to
 * fire and a body that either holds its face or relocates to another one. So this adapter chooses between exactly
 * two things, in this order:</p>
 *
 * <ol>
 *   <li><b>one native bullet</b> at the opponent this own turn locked, when the encounter says a shot is legal
 *       from where the body really holds and the frozen price is affordable;</li>
 *   <li><b>one native relocation</b>, when a shot is not the right answer and a landing point passes every gate -
 *       the point itself is never named here, because vanilla resolves it inside its own entry after the
 *       cancelable event had its say;</li>
 *   <li>otherwise the turn ends.</li>
 * </ol>
 *
 * <p>After a relocation the same own turn continues with at most one shot or its end: a relocation never buys a
 * second one, and never a new plan. The adapter never opens or closes the shell itself either - the encounter owns
 * that presentation at its own commit point, so the visible window stays a window.</p>
 */
public final class ShulkerTacticalAi implements TacticalMobAi {

    private boolean shotUsed;
    private boolean relocated;

    /** Registry factory form: the adapter takes the actor and asks the context for everything else. */
    public ShulkerTacticalAi(Mob actor) {
        this();
    }

    /** Allows deterministic decision tests without a world. */
    ShulkerTacticalAi() { }

    @Override
    public AiAction planTurn(AiContext context) {
        shotUsed = false;
        relocated = false;
        return decide(context);
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        // A relocation ends the planning: whatever follows is one shot that is legal right now, or the end.
        if (relocated) return shootOrEnd(context);
        return decide(context);
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.SHULKER_SHOT) shotUsed = true;
        if (action == AiAction.Kind.SHULKER_TELEPORT) relocated = true;
    }

    private AiAction decide(AiContext context) {
        ShulkerState state = context.shulker();
        if (!state.valid()) return AiAction.endTurn();
        // This adapter's own record of what it already did this turn is part of the decision: the context is a
        // snapshot, and a snapshot cannot know that the shot it was asked about has just been resolved.
        if (!shotUsed && state.canShootNow(context.ap())) return AiAction.of(AiAction.Kind.SHULKER_SHOT);
        if (!shotUsed && !relocated && state.canTeleportNow(context.ap())) return AiAction.shulkerTeleport();
        return AiAction.endTurn();
    }

    private AiAction shootOrEnd(AiContext context) {
        ShulkerState state = context.shulker();
        // A relocation never buys a second relocation, but it does buy exactly one shot that is legal from where
        // the body really landed - that is the whole point of relocating.
        return !shotUsed && state.canShootNow(context.ap())
                ? AiAction.of(AiAction.Kind.SHULKER_SHOT)
                : AiAction.endTurn();
    }
}
