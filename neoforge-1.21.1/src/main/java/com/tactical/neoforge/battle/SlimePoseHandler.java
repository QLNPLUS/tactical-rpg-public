package com.tactical.neoforge.battle;

import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.Vec3;

/**
 * What vanilla's own slime tick asks this encounter, and nothing else.
 *
 * <p>Vanilla's slime has no walk cycle: it stays where gravity and its own jump goal put it, and it damages a
 * player through contact. A body this encounter holds has to keep the position the encounter decided between
 * its own turns - a residual push or a leftover jump input must not slide it off its cell on somebody else's
 * turn - and a body whose edge is being animated must not have gravity integrated through it a second time in
 * the same tick.</p>
 *
 * <p>Two questions, deliberately separate. "Do you control this body's displacement" and "where must it be" are
 * different facts, and a controlled body keeps both answers for its whole life in this encounter: an animated
 * edge, a forced descent and a plain wait are all this encounter's own displacement, so vanilla's travel must
 * not be integrated beside any of them - that double integration is a second motion on one tick. Only a real
 * hand-back (exit, death, removal, another dimension) ends control. Nothing here moves an entity or charges
 * anything.</p>
 */
public interface SlimePoseHandler {

    /**
     * Whether this encounter controls that exact body's displacement right now.
     *
     * <p>True while this encounter still holds the exact entity and the encounter is live - including while one
     * of its edges animates and while a forced descent carries the body. Holding a proven ground node is what
     * the body can be pinned to, not a condition for keeping control; false is reserved for a real hand-back,
     * which is the only case where vanilla physics may move the body again.</p>
     */
    boolean tactical$controls(Slime slime);

    /**
     * The feet this encounter pins the body to while it controls it, or null to leave it exactly where it is.
     *
     * <p>While an edge animates this is the position the animation really reached; between turns it is the node
     * the encounter can prove. The native movement step is refused whenever this is asked, so a controlled body
     * neither falls nor drifts on somebody else's turn.</p>
     */
    Vec3 tactical$pinFeet(Slime slime);
}
