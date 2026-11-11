package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The ground geometry of the slime family: real nodes, real edges and one bounded search.
 *
 * <p>A node is not a cell. A slime's body is {@code 0.52 * size} blocks wide and tall, so a size 4 body is
 * 2.08 blocks in both directions: it reaches into neighbouring columns and it does not fit under a two-block
 * ceiling, while a size 1 body does. Every question here is therefore asked of the real body box - never of
 * the grid's two-cell head clearance and never of a single footprint cell - which is exactly why this family
 * needs its own view instead of the plain four-way walk.</p>
 *
 * <p>Nothing here mutates an entity: a candidate is judged where it would stand, and the body is never moved
 * to find out whether a position works.</p>
 */
public final class SlimeGround {

    /** Hard ceiling of one bounded approach search, shared by planning and by the commit's re-proof. */
    public static final int MAX_SEARCH_NODES = 512;
    /** A hop edge is one horizontal cell and at most one cell up or down. */
    public static final int MAX_EDGE_HEIGHT = 1;

    private static final double EPSILON = 1.0E-7D;

    private SlimeGround() { }

    /** A real standing position: the cell it belongs to and the feet the body really holds there. */
    public record Node(GridPos cell, Vec3 feet) {
        public Node {
            if (cell == null || feet == null) throw new IllegalArgumentException("A node needs a cell and feet");
        }
    }

    /** A route in front of the body: the nodes it would really step on, nearest first, without the start. */
    public record Route(List<Node> nodes) {
        public Route {
            nodes = List.copyOf(nodes);
            if (nodes.isEmpty()) throw new IllegalArgumentException("A route has at least one node");
        }

        public Node end() { return nodes.get(nodes.size() - 1); }
    }

    // ------------------------------------------------------------------ identity

    /** Exact vanilla types only: no other monster inherits a slime's body or its hop by sharing a superclass. */
    public static boolean supports(LivingEntity actor) {
        if (actor == null) return false;
        EntityType<?> type = actor.getType();
        return type == EntityType.SLIME || type == EntityType.MAGMA_CUBE;
    }

    public static boolean isSlimeType(EntityType<?> type) {
        return type == EntityType.SLIME || type == EntityType.MAGMA_CUBE;
    }

    /**
     * Whether this encounter may take this exact body's displacement at all.
     *
     * <p>Sharing the class is not enough: a slime that is riding something, or one that carries a rider, is
     * somebody else's traffic and keeps vanilla behaviour entirely.</p>
     */
    public static boolean controllable(LivingEntity actor) {
        if (!supports(actor)) return false;
        if (actor.isPassenger() || actor.getVehicle() != null) return false;
        return actor.getPassengers().isEmpty();
    }

    // ------------------------------------------------------------------ nodes

    /**
     * The real standing position of this body on that cell, or empty when its body cannot stand there.
     *
     * <p>The body's whole footprint is judged: build height and world border, no solid block inside the body,
     * a real solid upper face under every footprint column, no liquid at all, no hazard, and nobody else
     * already standing there. Feet are only ever built on the cell's own floor - a fractional height is not a
     * legal rest position, and this ruleset never invents one.</p>
     */
    public static Optional<Node> groundNode(ServerLevel level, LivingEntity body, GridPos cell) {
        if (level == null || body == null || cell == null) return Optional.empty();
        Vec3 feet = new Vec3(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
        return legalFeet(level, body, cell, feet) ? Optional.of(new Node(cell, feet)) : Optional.empty();
    }

    /** Whether this node is still a legal standing position in the live world. */
    public static boolean legal(ServerLevel level, LivingEntity body, Node node) {
        return node != null && legalFeet(level, body, node.cell(), node.feet());
    }

    /**
     * Whether the body really still stands on that node, ignoring who is close enough to be touched.
     *
     * <p>This is the standing half of {@link #legal} without the placement rule. The two must not be confused:
     * refusing to <em>place</em> a wide body where another body already is keeps two units from stacking, while
     * a wide body that already stands next to the opponent it is touching would fail exactly that test - which
     * would forbid the contact it is supposed to make. What still has to hold is the ground itself: the real
     * feet at the node's own floor, a real support face under the whole footprint, no solid block, no liquid and
     * no hazard inside the body, and every column loaded.</p>
     */
    public static boolean holds(ServerLevel level, LivingEntity body, Node node) {
        if (level == null || body == null || node == null) return false;
        // The real position, not just the height: a node whose feet carry a different X/Z is somebody else's
        // position, and judging the node's own horizontal point would silently answer for a place the body is
        // not standing on.
        if (body.position().distanceToSqr(node.feet()) > 1.0E-8D) return false;
        var box = BodyGeometry.bodyAt(body, node.feet());
        if (box == null) return false;
        if (!BodyGeometry.loaded(level, box)) return false;
        if (!level.noCollision(body, box)) return false;
        if (level.containsAnyLiquid(box)) return false;
        if (BodyGeometry.hazardous(level, box)) return false;
        for (int x = BodyGeometry.floor(box.minX); x <= BodyGeometry.floor(box.maxX); x++) {
            for (int z = BodyGeometry.floor(box.minZ); z <= BodyGeometry.floor(box.maxZ); z++) {
                BlockPos support = new BlockPos(x, node.cell().y() - 1, z);
                if (!level.getBlockState(support).isFaceSturdy(level, support,
                        net.minecraft.core.Direction.UP)) return false;
                if (BodyGeometry.hazardousBlock(level.getBlockState(support))) return false;
            }
        }
        return true;
    }

    private static boolean legalFeet(ServerLevel level, LivingEntity body, GridPos cell, Vec3 feet) {
        if (level == null || body == null || cell == null || feet == null) return false;
        if (Math.abs(feet.y - cell.y()) > EPSILON) return false;
        var box = BodyGeometry.bodyAt(body, feet);
        if (box == null) return false;
        if (!BodyGeometry.loaded(level, box)) return false;
        // The deployment rule already proves the whole footprint, the body, the support face, liquids, hazards
        // and other bodies; reusing it keeps one standing rule instead of a second, weaker one.
        if (!BattleDeployment.isStandable(level, body, feet)) return false;
        for (int x = BodyGeometry.floor(box.minX); x <= BodyGeometry.floor(box.maxX); x++) {
            for (int z = BodyGeometry.floor(box.minZ); z <= BodyGeometry.floor(box.maxZ); z++) {
                BlockState support = level.getBlockState(new BlockPos(x, cell.y() - 1, z));
                if (BodyGeometry.hazardousBlock(support)) return false;
            }
        }
        return true;
    }

    /** The node this body really holds right now, resolved from the cell it occupies. */
    public static Optional<Node> nodeAt(ServerLevel level, LivingEntity body) {
        if (level == null || body == null) return Optional.empty();
        GridPos cell = new GridPos(net.minecraft.util.Mth.floor(body.getX()), net.minecraft.util.Mth.floor(body.getY()),
                net.minecraft.util.Mth.floor(body.getZ()));
        Optional<Node> node = groundNode(level, body, cell);
        // A body an encounter already put on a cell keeps that cell's own feet even while its animation is
        // between two frames; only the integer cell is authoritative for "where it stands".
        return node.isPresent() ? node : Optional.empty();
    }

    /**
     * The real standing position this body holds this instant: its actual feet inside the cell they occupy.
     *
     * <p>Deployment is allowed to keep a fractional X/Z - it can be both nearer and the only way a wide body
     * fits - so the position this encounter must adopt after admitting such a body is the position it really
     * has, never the centre of its cell. The whole real body is judged at those feet: loaded columns, no solid
     * block, a real support face under the whole footprint, no liquid, no hazard and nobody else already there.
     * Empty means this body is not really standing and belongs to the fall rule instead.</p>
     */
    public static Optional<Node> heldNode(ServerLevel level, LivingEntity body) {
        if (level == null || body == null) return Optional.empty();
        Vec3 feet = body.position();
        GridPos cell = new GridPos(net.minecraft.util.Mth.floor(feet.x), net.minecraft.util.Mth.floor(feet.y),
                net.minecraft.util.Mth.floor(feet.z));
        return legalFeet(level, body, cell, feet) ? Optional.of(new Node(cell, feet)) : Optional.empty();
    }

    /** Whether the whole body really stays inside the battlefield at that node. */
    public static boolean bodyInsideZone(LivingEntity body, Node node, Predicate<GridPos> zone) {
        return node != null && BodyGeometry.bodyInsideZone(body, node.feet(), zone);
    }

    // ------------------------------------------------------------------ edges

    /**
     * Whether one hop edge is inside this family's movement rule at all.
     *
     * <p>Four directions, one horizontal cell per edge and at most one cell up or down. It says nothing about
     * the world yet; {@link #leg} is the answer to whether the body can really fly it.</p>
     */
    public static boolean adjacent(GridPos from, GridPos to) {
        if (from == null || to == null) return false;
        int horizontal = Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z());
        return horizontal == 1 && Math.abs(from.y() - to.y()) <= MAX_EDGE_HEIGHT;
    }

    /**
     * The real leg of one edge, or empty when the body cannot fly it in this world.
     *
     * <p>The arc is the one {@link SlimeMotion} proves frame by frame, so the edge a plan named and the edge
     * the body really flies are the same answer from the same function.</p>
     */
    public static Optional<SlimeMotion.Leg> leg(ServerLevel level, LivingEntity body, Node from, Node to,
                                                Predicate<GridPos> zone) {
        if (level == null || body == null || from == null || to == null) return Optional.empty();
        if (!adjacent(from.cell(), to.cell())) return Optional.empty();
        return SlimeMotion.legFor(level, body, from.feet(), to.feet(), zone);
    }

    /** Whether the body can step that edge right now: a real leg exists and the target node is a real stand. */
    public static boolean canStep(ServerLevel level, LivingEntity body, Node from, Node to,
                                  Predicate<GridPos> zone) {
        if (from == null || to == null) return false;
        if (!legal(level, body, to)) return false;
        return leg(level, body, from, to, zone).isPresent();
    }

    // ------------------------------------------------------------------ bounded approach search

    /**
     * One bounded search for a route towards the opponent, or towards the best legal progress there is.
     *
     * <p>The search is a breadth-first walk over real nodes: four horizontal directions with a bounded height
     * change, every candidate proved against the live world, at most {@link #MAX_SEARCH_NODES} discovered
     * states - the start included, so the 513th is never registered - and at most {@code maxEdges} hops. The
     * first node from which the opponent is really reachable ends the search; when no such node exists inside
     * the budget, the route with the smallest real distance to the opponent that still made strict progress is
     * returned, because standing still is never a better answer than a legal step forward. A target cell that
     * belongs to another unit is never stepped on.</p>
     *
     * @param contact  whether the locked opponent's body is really reachable from that node
     * @param occupied whether another participant really holds that cell
     */
    public static Optional<Route> approach(ServerLevel level, LivingEntity body, Node from, GridPos targetCell,
                                           int maxEdges, Predicate<GridPos> occupied, Predicate<GridPos> zone,
                                           Predicate<Node> contact) {
        if (level == null || body == null || from == null || targetCell == null) return Optional.empty();
        if (maxEdges <= 0) return Optional.empty();
        // Two independent bounds: how far this turn may really walk, and how much world one plan may read.
        int limit = MAX_SEARCH_NODES;
        Set<GridPos> visited = new HashSet<>();
        visited.add(from.cell());
        ArrayDeque<List<Node>> queue = new ArrayDeque<>();
        queue.add(List.of());
        Node best = null;
        List<Node> bestPath = List.of();
        int bestDistance = manhattan(from.cell(), targetCell);
        // The start is the first discovered state; the budget counts discovered states, so the search answers at
        // exactly 512 of them and never registers the 513th.
        int discovered = 1;
        while (!queue.isEmpty()) {
            List<Node> path = queue.poll();
            if (path.size() >= maxEdges) continue;
            Node current = path.isEmpty() ? from : path.get(path.size() - 1);
            for (Node next : neighbours(level, body, current, targetCell, occupied, zone)) {
                if (visited.contains(next.cell())) continue;
                // The bound is a disclosure bound, not an off-by-one: once 512 states are known the best legal
                // prefix is already the answer, and the next discovery is refused before it is registered.
                if (discovered >= limit) return best == null ? Optional.empty() : Optional.of(new Route(bestPath));
                visited.add(next.cell());
                discovered++;
                List<Node> extended = new ArrayList<>(path);
                extended.add(next);
                if (contact != null && contact.test(next)) return Optional.of(new Route(extended));
                int distance = manhattan(next.cell(), targetCell);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = next;
                    bestPath = List.copyOf(extended);
                }
                queue.add(extended);
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Route(bestPath));
    }

    /**
     * The real legal neighbours of one node, deterministically ordered.
     *
     * <p>Candidates are generated for the bounded height band and sorted by real distance to the opponent,
     * then by height, x and z, so the same world always answers with the same route.</p>
     */
    public static List<Node> neighbours(ServerLevel level, LivingEntity body, Node from, GridPos targetCell,
                                        Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        if (level == null || body == null || from == null) return List.of();
        List<GridPos> cells = new ArrayList<>(4 * (2 * MAX_EDGE_HEIGHT + 1));
        for (int dy = -MAX_EDGE_HEIGHT; dy <= MAX_EDGE_HEIGHT; dy++) {
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                cells.add(new GridPos(from.cell().x() + step[0], from.cell().y() + dy, from.cell().z() + step[1]));
            }
        }
        GridPos reference = targetCell == null ? from.cell() : targetCell;
        cells.sort(Comparator.comparingInt((GridPos cell) -> manhattan(cell, reference))
                .thenComparingInt(GridPos::y)
                .thenComparingInt(GridPos::x)
                .thenComparingInt(GridPos::z));
        List<Node> result = new ArrayList<>();
        for (GridPos cell : cells) {
            if (zone != null && !zone.test(cell)) continue;
            if (occupied != null && occupied.test(cell)) continue;
            if (cell.equals(from.cell())) continue;
            Optional<Node> node = groundNode(level, body, cell);
            if (node.isEmpty()) continue;
            if (!bodyInsideZone(body, node.get(), zone)) continue;
            if (leg(level, body, from, node.get(), zone).isEmpty()) continue;
            result.add(node.get());
        }
        return List.copyOf(result);
    }

    private static int manhattan(GridPos from, GridPos to) {
        if (from == null || to == null) return Integer.MAX_VALUE;
        return Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z());
    }
}
