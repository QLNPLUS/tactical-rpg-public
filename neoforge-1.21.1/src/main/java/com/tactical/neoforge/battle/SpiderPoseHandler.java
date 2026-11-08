package com.tactical.neoforge.battle;

import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.phys.Vec3;

/**
 * What vanilla's own spider tick asks this encounter, and nothing else.
 *
 * <p>Vanilla derives the climbing bit from {@code horizontalCollision} every tick and lets {@code travel} apply
 * gravity and the climb clamps. A spider this encounter holds has to keep the pose the encounter decided:
 * a wall position must not be overridden back to "not climbing" (which would lose its support), and an
 * ordinary floor position must not be allowed to hold a wall from a residual push during somebody else's turn.
 * Equally, a body whose surface the encounter is animating must not have gravity integrated through it a second
 * time in the same tick.</p>
 *
 * <p>The three questions below are deliberately separate. "Do you control this body's displacement", "where
 * must it be" and "is it in a wall pose" are different facts: a body standing on a legal floor is controlled but
 * not on a wall, and a body whose support the encounter can no longer prove is not controlled at all, so the
 * existing fall rule owns it. The single seam is installed on admission and cleared on every exit, so a wild
 * spider, a riding one, another encounter's spider or any other monster keeps vanilla behaviour untouched.</p>
 *
 * <p>Nothing here moves an entity or charges anything: each answer is a decision about this encounter's own
 * state, and the caller applies it inside vanilla's own tick.</p>
 */
public interface SpiderPoseHandler {

    /**
     * Whether this encounter controls that exact body's displacement right now.
     *
     * <p>True only while this encounter still holds the exact entity, the encounter is live, and it can really
     * account for where the body is - a proven surface node, or an edge it is animating. False hands the body
     * back to its own vanilla physics, which is what happens while a forced fall runs.</p>
     */
    boolean tactical$controls(Spider spider);

    /**
     * The feet this encounter pins the body to while it controls it, or null to leave it exactly where it is.
     *
     * <p>While an edge animates this is the position the animation really reached; between turns it is the node
     * the encounter can prove. The native movement step is refused whenever this is asked, so a controlled
     * spider neither falls nor drifts on somebody else's turn.</p>
     */
    Vec3 tactical$pinFeet(Spider spider);

    /**
     * Whether the body really holds a wall (the native climbing bit), independent of the pin.
     *
     * <p>True only for a node whose wall the encounter has really confirmed. A floor-standing body is controlled
     * and answers false here, which is what keeps it from inheriting a climbing bit it never earned.</p>
     */
    boolean tactical$wallPose(Spider spider);
}
