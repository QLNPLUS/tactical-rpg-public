package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Breeze tactics: one native wind charge, one native long jump, and ordinary ground edges in between.
 *
 * <p>A breeze does not swing, does not wear a weapon and never runs its Brain in this encounter. Its whole
 * vocabulary is a wind charge it inhales for and a body that flies in one native arc, so this adapter chooses
 * between exactly these things, in this order:</p>
 *
 * <ol>
 *   <li><b>one native wind charge</b> at the opponent this own turn locked, when the encounter says the native
 *       {@code (2, 16]} band and every other gate really hold;</li>
 *   <li><b>one native long jump</b>, when a shot is not legal from where the body stands and a proved landing
 *       inside the band exists - the landing itself is never named here, because the arc is the encounter's
 *       bounded proposal, not a payload a plan may carry;</li>
 *   <li><b>ordinary ground edges</b> - a bounded slide or back-off over the same per-edge price every other body
 *       pays, and deliberately never an invented melee: a breeze has no swing at all;</li>
 *   <li>otherwise the turn ends.</li>
 * </ol>
 *
 * <p>After a jump the same own turn continues with at most one newly legal shot or its end: a jump never buys a
 * second manoeuvre, never a fresh jump, and never a new slide. The adapter never writes a pose, plays a sound or
 * spawns anything - the encounter owns those at its own commit points.</p>
 */
public final class BreezeTacticalAi implements TacticalMobAi {

    private boolean shotUsed;
    private boolean jumped;

    /** Registry factory form: the adapter takes the actor and asks the context for everything else. */
    public BreezeTacticalAi(Mob actor) {
        this();
    }

    /** Allows deterministic decision tests without a world. */
    BreezeTacticalAi() { }

    @Override
    public AiAction planTurn(AiContext context) {
        shotUsed = false;
        jumped = false;
        return decide(context);
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        // One own turn walks one bounded route, jumps one arc, or fires - and what follows any movement is only a
        // shot that is legal right now, or the end. Re-deciding a whole turn here would let the same turn chain
        // slide after slide until the AP ran out, which is a different game than "one move and one action".
        return shootOrEnd(context);
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.BREEZE_SHOT) shotUsed = true;
        if (action == AiAction.Kind.BREEZE_JUMP) jumped = true;
    }

    private AiAction decide(AiContext context) {
        BreezeState state = context.breeze();
        if (!state.valid()) return AiAction.endTurn();
        // This adapter's own record of what it already did this turn is part of the decision: the context is a
        // snapshot, and a snapshot cannot know that the shot it was asked about has just been resolved.
        if (!shotUsed && state.canShootNow(context.ap())) return AiAction.of(AiAction.Kind.BREEZE_SHOT);
        if (!shotUsed && !jumped && state.canJumpNow(context.ap())) return AiAction.breezeJump();
        // Ground edges are ordinary movement: the encounter re-proves every cell and prices every edge exactly as
        // it does for any other body, and nothing here may be turned into a contact attack.
        List<GridPos> slide = context.breezeSlide();
        return slide.isEmpty() ? AiAction.endTurn() : AiAction.move(slide);
    }

    private AiAction shootOrEnd(AiContext context) {
        BreezeState state = context.breeze();
        // A jump never buys a second jump, but it does buy exactly one shot that is legal from where the body
        // really landed - that is the whole point of jumping.
        return !shotUsed && state.canShootNow(context.ap())
                ? AiAction.of(AiAction.Kind.BREEZE_SHOT)
                : AiAction.endTurn();
    }
}
