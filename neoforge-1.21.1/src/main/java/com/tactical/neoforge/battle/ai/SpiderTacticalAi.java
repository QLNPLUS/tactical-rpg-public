package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.SpiderSurface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surface tactics for exactly two vanilla types: the spider and the cave spider.
 *
 * <p>The rule the encounter keeps is the vanilla one - close on the locked opponent and bite it - but the
 * surface is the real one: a spider may hold a wall, climb it, cross a ledge and drop down, which the plain
 * four-way floor rule can never express. Every node is re-validated by the server against the live world before
 * a single tick of the route is walked, so this class only chooses; it never mutates anything.</p>
 *
 * <p>Order is the frozen one: bite when the real attack box already reaches, otherwise the cheaper of one
 * bounded surface route and one short leap, and then at most one native melee. Nothing here poisons or invents
 * damage - a cave spider's poison comes from its own {@code doHurtTarget} - and the leap itself is only a
 * movement: it carries no destination, hits nothing and is priced and proved by the server.</p>
 */
public final class SpiderTacticalAi implements TacticalMobAi {

    /** The search never reads more than this many nodes, whatever the budget allows. */
    private static final int MAX_NODES = SpiderSurface.MAX_SEARCH_NODES;
    /** One depth keeps only this many positions, closest to the opponent first. */
    private static final int LEVEL_WIDTH = 48;

    @Override
    public AiAction planTurn(AiContext context) {
        SpiderSurface.Node here = context.surfaceHere();
        if (here == null) return plainMelee(context);
        // The real attack box decides first: a spider that can already bite must not walk or leap first.
        if (context.surfaceMeleeReach(here)) return AiAction.of(AiAction.Kind.MELEE);
        List<SpiderSurface.Node> route = route(context, here);
        if (leapFirst(context, route)) return AiAction.spiderLeap();
        return route.isEmpty() ? AiAction.endTurn() : AiAction.surfaceMove(route);
    }

    /**
     * Whether one leap is the cheaper way in than the surface route this turn could really walk.
     *
     * <p>Walking is preferred whenever it already works cheaply: reaching the opponent in one or two edges is
     * cheaper than spending a main action on the leap, so a flat floor stays a walk. A leap is chosen only when
     * the ordinary route cannot reach the opponent at all this turn - no route at all, or only a bounded prefix
     * that closes in without arriving, which is not the same thing as being reachable - or when the route that
     * does arrive needs at least three edges, which is what "saves at least two edges" means here. The prefix is
     * never mistaken for a complete approach: only a route whose last node is really inside the native attack
     * box counts as arriving.</p>
     */
    private static boolean leapFirst(AiContext context, List<SpiderSurface.Node> route) {
        if (!context.canLeap()) return false;
        if (route.isEmpty()) return true;
        boolean arrives = context.surfaceMeleeReach(route.get(route.size() - 1));
        return !arrives || route.size() >= 3;
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        SpiderSurface.Node here = context.surfaceHere();
        if (here == null) return AiAction.endTurn();
        // One native melee after the route or the leap, re-validated from the real body - never a second plan,
        // never a second leap and never another walk inside the same own turn.
        return context.surfaceMeleeReach(here) ? AiAction.of(AiAction.Kind.MELEE) : AiAction.endTurn();
    }

    /**
     * One bounded shortest route towards a node whose real attack box reaches the opponent.
     *
     * <p>Every edge costs the same move price, so the route is searched in breadth-first order and the first
     * node that can bite is the cheapest answer. Two bounds keep that honest on a real map: the search never
     * discovers more than {@link #MAX_NODES} nodes, and each depth keeps only its {@link #LEVEL_WIDTH} closest
     * positions to the opponent. The second bound is what stops an open floor next to the actor from eating the
     * whole budget: level order is still by edge count, so the first reach that can bite is still the cheap one,
     * but the frontier is pulled towards the opponent instead of fanning out over every reachable floor tile.</p>
     *
     * <p>When this turn cannot reach the opponent at all - the target is further than the affordable depth, or a
     * wall is in the way for now - the answer is not "stand still forever": the legal prefix that really closes
     * in on the opponent is walked instead, and the rest of the approach belongs to the following turn. Only a
     * turn with no strict progress ends where it stands.</p>
     */
    private static List<SpiderSurface.Node> route(AiContext context, SpiderSurface.Node here) {
        int maxEdges = Math.min(context.surfaceEdgeBudget(), MAX_NODES);
        if (maxEdges <= 0) return List.of();
        GridPos goal = context.targetCell();
        Map<SpiderSurface.Node, SpiderSurface.Node> previous = new HashMap<>();
        Set<SpiderSurface.Node> seen = new HashSet<>();
        seen.add(here);
        List<SpiderSurface.Node> frontier = new ArrayList<>();
        frontier.add(here);
        SpiderSurface.Node best = null;
        int bestDistance = distance(here.cell(), goal);
        int bestDepth = 0;
        int discovered = 1;
        for (int depth = 0; depth < maxEdges && !frontier.isEmpty(); depth++) {
            if (frontier.size() > 1) frontier.sort(closestFirst(goal));
            List<SpiderSurface.Node> next = new ArrayList<>();
            for (SpiderSurface.Node current : frontier) {
                for (SpiderSurface.Node candidate : context.surfaceNeighbours(current)) {
                    if (!seen.add(candidate)) continue;
                    if (!context.surfaceInsideZone(candidate)) continue;
                    if (discovered >= MAX_NODES) {
                        return best == null ? List.of() : trace(previous, best);
                    }
                    discovered++;
                    previous.put(candidate, current);
                    // Biting is the frozen first choice, and level order makes the first reach the cheapest.
                    if (context.surfaceMeleeReach(candidate)) return trace(previous, candidate);
                    int remaining = distance(candidate.cell(), goal);
                    if (remaining < bestDistance
                            || (remaining == bestDistance && depth + 1 < bestDepth)) {
                        best = candidate;
                        bestDistance = remaining;
                        bestDepth = depth + 1;
                    }
                    next.add(candidate);
                }
                if (frontier.isEmpty()) break;
            }
            if (next.size() > LEVEL_WIDTH) {
                next.sort(closestFirst(goal));
                next = new ArrayList<>(next.subList(0, LEVEL_WIDTH));
            }
            frontier = next;
        }
        // Strict progress only: a same-distance detour is not an approach and is not worth a paid edge.
        return best == null ? List.of() : trace(previous, best);
    }

    /** Closest to the opponent first, then the stable cell order, so one plan is always reproducible. */
    private static java.util.Comparator<SpiderSurface.Node> closestFirst(GridPos goal) {
        return java.util.Comparator
                .comparingInt((SpiderSurface.Node node) -> distance(node.cell(), goal))
                .thenComparingInt(node -> node.cell().y())
                .thenComparingInt(node -> node.cell().x())
                .thenComparingInt(node -> node.cell().z())
                .thenComparingInt(SpiderSurface.Node::shiftX)
                .thenComparingInt(SpiderSurface.Node::shiftZ);
    }

    /** Manhattan distance between two cells, the same measure the grid itself uses. */
    private static int distance(GridPos from, GridPos to) {
        return Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z());
    }

    private static List<SpiderSurface.Node> trace(Map<SpiderSurface.Node, SpiderSurface.Node> previous,
                                                  SpiderSurface.Node goal) {
        List<SpiderSurface.Node> reversed = new ArrayList<>();
        for (SpiderSurface.Node node = goal; node != null; node = previous.get(node)) {
            reversed.add(node);
            if (previous.get(node) == null) break;
        }
        java.util.Collections.reverse(reversed);
        // The route excludes the node the body already holds: it is where the walk starts, not a step.
        return List.copyOf(reversed.subList(1, reversed.size()));
    }

    /**
     * The untouched fallback for a context that has no surface state.
     *
     * <p>It is the same approach-then-melee rule every unregistered mob gets, kept so this adapter can never
     * behave worse than the generic one if a fixture or an older context asks without geometry.</p>
     */
    private static AiAction plainMelee(AiContext context) {
        GridPos cell = context.actorCell();
        if (context.inMeleeRange(cell, context.targetCell())) return AiAction.of(AiAction.Kind.MELEE);
        return AiApproach.towardPlayer(context, candidate -> context.inMeleeRange(candidate, context.targetCell()));
    }
}
