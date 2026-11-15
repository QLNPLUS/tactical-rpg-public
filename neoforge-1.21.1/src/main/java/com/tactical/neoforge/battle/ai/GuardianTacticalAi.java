package com.tactical.neoforge.battle.ai;

import com.tactical.neoforge.battle.GuardianMovement;

import java.util.List;

/**
 * Water tactics for exactly two vanilla types: the guardian and the elder guardian.
 *
 * <p>Both are ranged swimmers. Vanilla drives them with a {@code GuardianAttackGoal} that charges for 80 (60 for
 * the elder) ticks and then really calls {@code indirectMagic} and {@code doHurtTarget}, and it only keeps an
 * ordinary guardian firing while the target is strictly further than three blocks away. Nothing about them is a
 * swing: an underwater guardian that "walks up and bites" would be a different creature wearing the same skin.</p>
 *
 * <p>This batch owns their movement <em>and</em> their one real attack. The plan is therefore: fire the body's own
 * native beam from where it stands when it really can, otherwise swim to such a position with the body's own water
 * geometry, and when neither is possible end the turn. It never falls back to a generic melee, it never pretends a
 * beam it has not fired was fired, and it never fires twice inside one own turn - the launched beam owns the turn
 * until its settlement hands it back.</p>
 *
 * <p>A context that does not carry this family's policy - a third-party adapter's context, an older fixture - makes
 * the guardian end its turn. That is deliberately not the slime family's legacy fallback: a guardian's native attack
 * is a charged beam at range, so a one-cell melee would be a fake adaptation rather than a conservative one.</p>
 */
public final class GuardianTacticalAi implements TacticalMobAi {

    @Override
    public AiAction planTurn(AiContext context) {
        // Asked first, and before the actor itself: "this context carries no guardian policy" is a complete answer on
        // its own, and a third-party context may not even expose an entity.
        if (!context.guardianContext()) return plan(false, false, false, false, false, List.of());
        if (!GuardianMovement.supports(context.actor())) return plan(false, false, false, false, false, List.of());
        GuardianMovement.Node here = context.guardianHere();
        boolean fires = here != null && context.guardianFiresFrom(here);
        boolean beamReady = fires && context.guardianBeamReady();
        // Only a body that is not already in a firing position spends its movement on swimming there. A body in
        // position that cannot pay for its beam ends its turn instead of wandering away from the shot.
        List<GuardianMovement.Node> route = fires || here == null
                ? List.of() : context.guardianApproach();
        return plan(true, true, here != null, fires, beamReady, route);
    }

    /**
     * After one swim route: the body fires its own beam if the water it reached can really shoot, and otherwise the
     * turn's movement is spent and the only remaining answer is to end it.
     *
     * <p>A guardian that could not arrive does not get a second search inside the same own turn; the next own turn
     * plans again against the world as it then is. This entry point returns no route at all, by construction.</p>
     */
    @Override
    public AiAction afterMovement(AiContext context) {
        if (!context.guardianContext()) return afterMove(false, false, false, false, false);
        if (!GuardianMovement.supports(context.actor())) return afterMove(false, false, false, false, false);
        GuardianMovement.Node here = context.guardianHere();
        boolean fires = here != null && context.guardianFiresFrom(here);
        boolean beamReady = fires && context.guardianBeamReady();
        return afterMove(true, true, here != null, fires, beamReady);
    }

    /**
     * The planning decision itself, separated from the context so it can be decided without a world.
     *
     * <p>Every argument is a fact the server already resolved. Nothing here re-reads the world, so a plan can never
     * disagree with the gates the commit re-proves.</p>
     */
    static AiAction plan(boolean supported, boolean carriesPolicy, boolean holdsNode, boolean fires,
                         boolean beamReady, List<GuardianMovement.Node> route) {
        if (!supported || !carriesPolicy || !holdsNode) return AiAction.endTurn();
        if (beamReady) return AiAction.guardianBeam();
        if (fires) return AiAction.endTurn();
        return route == null || route.isEmpty() ? AiAction.endTurn() : AiAction.guardianSwim(route);
    }

    /** The post-movement decision: the beam or the end of the turn, never another route. */
    static AiAction afterMove(boolean supported, boolean carriesPolicy, boolean holdsNode, boolean fires,
                              boolean beamReady) {
        if (!supported || !carriesPolicy || !holdsNode) return AiAction.endTurn();
        return beamReady ? AiAction.guardianBeam() : AiAction.endTurn();
    }
}
