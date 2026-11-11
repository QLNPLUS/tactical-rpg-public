package com.tactical.neoforge.battle.ai;

import com.tactical.neoforge.battle.SlimeGround;

import java.util.List;

/**
 * Ground tactics for exactly two vanilla types: the slime and the magma cube.
 *
 * <p>The rule the encounter keeps is the vanilla one - close on the locked opponent and touch it - but both
 * halves are the real ones. The movement is the body's own: a slime and a magma cube have no walk cycle at all,
 * they cover a cell per hop and they are {@code 0.52 * size} blocks wide and tall, so a size 4 body has to fit a
 * 2.08 block box through the world while a size 1 body fits places it never could. The damage is the native
 * {@code dealDamage}, whose attack box spans about two cells for a large body and which a size 1 slime may not
 * use at all.</p>
 *
 * <p>Order is frozen: touch when the native attack box already reaches, otherwise one bounded hop route, then
 * at most one native contact after that route. This class only chooses; every gate, price and frame is
 * re-proved by the server against the live world, and nothing here moves an entity or writes damage.</p>
 *
 * <p>A context that does not carry this family's policy - a third-party adapter's context, an older fixture -
 * falls back to the complete legacy behaviour, {@code afterMovement} included. A controlled body never falls
 * back to it: that would be a fake one-cell melee for a type whose melee is a contact box.</p>
 */
public final class SlimeTacticalAi implements TacticalMobAi {

    /** The complete legacy behaviour, used only for a context that knows nothing about this family. */
    private final GenericMeleeAi legacy = new GenericMeleeAi();

    @Override
    public AiAction planTurn(AiContext context) {
        if (!SlimeGround.supports(context.actor())) return AiAction.endTurn();
        if (!context.slimeContext()) return legacy.planTurn(context);
        SlimeGround.Node here = context.slimeHere();
        if (here == null) return AiAction.endTurn();
        if (contactReady(context, here)) return AiAction.slimeContact();
        List<SlimeGround.Node> route = context.slimeApproach();
        return route.isEmpty() ? AiAction.endTurn() : AiAction.slimeHop(route);
    }

    /**
     * After one hop route: at most one native contact, never another route.
     *
     * <p>The turn already spent its movement on this hop, so the only remaining legal action is the contact it
     * moved for. A body that cannot reach from where it landed ends its turn; it does not get a second search
     * inside the same own turn.</p>
     */
    @Override
    public AiAction afterMovement(AiContext context) {
        if (!SlimeGround.supports(context.actor())) return AiAction.endTurn();
        if (!context.slimeContext()) return legacy.afterMovement(context);
        SlimeGround.Node here = context.slimeHere();
        if (here == null) return AiAction.endTurn();
        return contactReady(context, here) ? AiAction.slimeContact() : AiAction.endTurn();
    }

    /**
     * Whether one native contact is really available right now: a price this body may pay, enough AP, and the
     * real attack box reaching the locked opponent with a clear line of sight from its own eye.
     */
    private static boolean contactReady(AiContext context, SlimeGround.Node here) {
        int cost = context.slimeContactCost();
        return cost > 0 && context.ap() >= cost && context.slimeContactReaches(here);
    }
}
