package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/**
 * One encountered spider's surface state: the node it really holds, and the bounded route it is walking.
 *
 * <p>Deliberately small and free of any world read. The geometry lives in {@link SpiderSurface}, the price and
 * the animation live in the encounter, and this object only keeps the facts both of them have to agree on: the
 * surface the body is on, whether that surface still carries it, which edge of the route is being walked, and
 * how far through it the body is.</p>
 *
 * <p>It answers three different questions separately, because they are different facts: whether this encounter
 * controls the body's displacement at all, where the body has to be pinned while it does, and whether the body
 * is really in a wall pose. Merging them is what would let a moving body be integrated twice, or let a
 * floor-standing spider inherit a climbing bit it never earned.</p>
 */
public final class SpiderTraversal {

    private SpiderSurface.Node held;
    private List<SpiderSurface.Node> route = List.of();
    private int index;
    private int progress;
    /** Where the executor really is while an edge animates; null while nothing is in flight. */
    private Vec3 motionFeet;
    /** Whether the encounter could prove, at its last look, that this node still carries the body. */
    private boolean heldValid = true;

    public SpiderTraversal(SpiderSurface.Node start) {
        this.held = Objects.requireNonNull(start, "start node");
    }

    /** The node the body really holds; never null once the encounter admitted it. */
    public SpiderSurface.Node node() { return held; }

    /** The cell that node answers for, which is the encounter's authoritative cell. */
    public GridPos cell() { return held.cell(); }

    /** The real feet of that node. */
    public Vec3 feet() { return held.feet(); }

    /** Whether this body is holding a wall rather than standing on a floor. */
    public boolean onWall() { return held.onWall(); }

    /** Record the surface the body really reached (a new node, or the same one after a settle). */
    public void settle(SpiderSurface.Node node) {
        this.held = Objects.requireNonNull(node, "node");
    }

    /**
     * Record whether the encounter could still prove this hold against the live world.
     *
     * <p>The answer is refreshed once per encounter tick, before the entity tick asks for it, so the native
     * movement gate never has to read the world itself and never disagrees with the encounter about whether the
     * body is carried.</p>
     */
    public void heldValid(boolean value) { this.heldValid = value; }

    public boolean heldValid() { return heldValid; }

    /** Whether the encounter may pin this body at all: a proven hold, or an edge it is really walking. */
    public boolean controls() { return walking() || heldValid; }

    /**
     * The feet to pin the body to while the encounter controls it.
     *
     * <p>An edge in flight is owned by its animation, so the pin is the position the animation really put the
     * body at; between turns it is the node itself, which is what stops a held spider sliding or starting to
     * climb on somebody else's turn.</p>
     */
    public Vec3 pinFeet() {
        return motionFeet != null ? motionFeet : held.feet();
    }

    /** Take one planned route; the caller has already validated and charged for it. */
    public void beginRoute(List<SpiderSurface.Node> nodes) {
        this.route = List.copyOf(nodes);
        this.index = 0;
        this.progress = 0;
        this.motionFeet = null;
    }

    /** The one edge in flight really moved the body to this position. */
    public void motion(Vec3 feet) { this.motionFeet = feet; }

    /** Abandon the remainder of a route; nothing is charged or refunded here. */
    public List<SpiderSurface.Node> abandonRoute() {
        List<SpiderSurface.Node> dropped = remaining();
        this.route = List.of();
        this.index = 0;
        this.progress = 0;
        this.motionFeet = null;
        return dropped;
    }

    public boolean walking() { return index < route.size(); }

    public List<SpiderSurface.Node> route() { return route; }

    /** The nodes that have not been walked yet: the ones a refund may give back. */
    public List<SpiderSurface.Node> remaining() {
        return index >= route.size() ? List.of() : List.copyOf(route.subList(index, route.size()));
    }

    /**
     * The edges a stop may really give back: the ones the body has not walked and is not walking.
     *
     * <p>An edge that already moved the body is not free: it was priced when the route was committed and it
     * stays priced when the route ends early, so a partial step can never be refunded into a free move. Only
     * the edges in front of the body are returned here.</p>
     */
    public List<SpiderSurface.Node> refundable() {
        int firstUnwalked = index + (progress > 0 ? 1 : 0);
        return firstUnwalked >= route.size() ? List.of() : List.copyOf(route.subList(firstUnwalked, route.size()));
    }

    /** The number of edges whose price stays with the body after a stop. */
    public int spentEdges() { return Math.min(route.size(), index + (progress > 0 ? 1 : 0)); }

    public SpiderSurface.Node stepTarget() {
        return index < route.size() ? route.get(index) : null;
    }

    public int progress() { return progress; }

    public void progress(int value) { this.progress = Math.max(0, value); }

    /** The step really arrived: the body now holds that node and the route advances by one edge. */
    public void landed() {
        if (index >= route.size()) return;
        held = route.get(index);
        index++;
        progress = 0;
        motionFeet = null;
        heldValid = true;
    }
}
