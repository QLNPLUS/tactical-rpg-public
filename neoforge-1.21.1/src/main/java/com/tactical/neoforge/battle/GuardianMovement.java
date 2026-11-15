package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The water geometry of exactly two vanilla types: the guardian and the elder guardian.
 *
 * <p>Neither of them is a walker. Vanilla gives them a {@code WaterBoundPathNavigation}, a real swim control and
 * a body that never stands on a floor, so the encounter cannot describe them with a floor cell: a held position
 * is a <em>point under water</em>, and the two bodies are different sizes ({@code 0.85} and {@code 1.9975} blocks
 * in both directions, read from this target version's own {@link EntityType}). That is the whole reason this
 * class exists instead of an entry in {@link AmphibiousMovement}: the drowned half of that file also carries a
 * shore rule, and a guardian may never be placed on land.</p>
 *
 * <ul>
 *   <li>A held position keeps the real, fractional feet the body has. Nothing here recentres a body on a cell.</li>
 *   <li>A candidate is legal only when the feet <em>and</em> the real eye are in water, the whole body has no
 *       collision, no other or hazardous liquid and no other body inside it, and every cube it would occupy is
 *       already loaded. Nothing is ever force-loaded to make a candidate work.</li>
 *   <li>An edge is one of the 26 neighbouring cells, and it is legal only where the whole swept body travels:
 *       the union of the two body boxes is re-proved as water, empty of blocks, hazards and other bodies.</li>
 *   <li>Every search is bounded by {@link #MAX_SEARCH_NODES} discovered states, the start included, so the 513th
 *       is never registered; when no goal is reachable inside the budget the best <em>real</em> prefix is
 *       returned, because a legal step forward is still better than standing still.</li>
 * </ul>
 *
 * <p>Nothing here mutates an entity, starts vanilla navigation or writes a position: every method answers a
 * question about the live world, and the caller applies the answer.</p>
 */
public final class GuardianMovement {

    /** Hard ceiling of one bounded swim search, shared by planning and by the commit's re-proof. */
    public static final int MAX_SEARCH_NODES = 512;

    /**
     * Vanilla's own near gate, as the squared distance {@code Guardian.GuardianAttackGoal} uses: an ordinary
     * guardian only keeps firing while it is strictly further than three blocks from its target. The elder
     * guardian has no such gate.
     */
    public static final double NATIVE_NEAR_GATE_SQR = 9.0D;

    /** Vanilla's own line-of-sight reach, read from {@code LivingEntity#hasLineOfSight}. */
    public static final double NATIVE_SIGHT_RANGE = 128.0D;

    private GuardianMovement() { }

    /** A real position under water: the cell it belongs to and the feet the body really holds there. */
    public record Node(GridPos cell, Vec3 feet) {
        public Node {
            if (cell == null || feet == null) throw new IllegalArgumentException("A node needs a cell and feet");
        }
    }

    /** A route in front of the body: the nodes it would really swim through, nearest first, without the start. */
    public record Route(List<Node> nodes) {
        public Route {
            nodes = List.copyOf(nodes);
            if (nodes.isEmpty()) throw new IllegalArgumentException("A route has at least one node");
        }

        public Node end() { return nodes.get(nodes.size() - 1); }
    }

    // ------------------------------------------------------------------ identity

    /** Exact vanilla types only: no other monster inherits a guardian's body or its swim by superclass. */
    public static boolean supports(LivingEntity actor) {
        return actor != null && isGuardianType(actor.getType());
    }

    public static boolean isGuardianType(EntityType<?> type) {
        return type == EntityType.GUARDIAN || type == EntityType.ELDER_GUARDIAN;
    }

    /** The elder guardian is the bigger body, the shorter native charge and the one type without a near gate. */
    public static boolean elder(LivingEntity actor) {
        return actor != null && actor.getType() == EntityType.ELDER_GUARDIAN;
    }

    /**
     * Whether this encounter may take this exact body's displacement at all.
     *
     * <p>Sharing the class is not enough: a guardian that is riding something, or one that carries a rider, is
     * somebody else's traffic and keeps vanilla behaviour entirely.</p>
     */
    public static boolean controllable(LivingEntity actor) {
        if (!supports(actor)) return false;
        if (actor.isPassenger() || actor.getVehicle() != null) return false;
        return actor.getPassengers().isEmpty();
    }

    // ------------------------------------------------------------------ nodes

    /**
     * The position this body would hold on that cell, or empty when the whole water body does not fit there.
     *
     * <p>The feet keep the fractional offset the body really has, exactly like the drowned's water rule: an
     * offset captured at admission is what lets a wide body pass a place its centre could not. Nothing is read at
     * a position whose cubes are not already loaded.</p>
     */
    public static Optional<Node> waterNode(ServerLevel level, LivingEntity body, GridPos cell, Vec3 offset) {
        if (level == null || body == null || cell == null) return Optional.empty();
        Node node = new Node(cell, AerialMovement.feet(cell, offset == null ? Vec3.ZERO : offset));
        return legal(level, body, node) ? Optional.of(node) : Optional.empty();
    }

    /**
     * The position this body really holds this instant, derived from its own position.
     *
     * <p>Admission is allowed to keep a fractional X/Z because it can be both nearer and the only way a wide body
     * fits, so the position this encounter must adopt is the position the body really has - never the centre of
     * its cell. Empty means the body is not legally under water and belongs to the safety path instead.</p>
     */
    public static Optional<Node> heldNode(ServerLevel level, LivingEntity body) {
        if (level == null || body == null) return Optional.empty();
        Vec3 feet = body.position();
        Node node = new Node(cellOf(feet), feet);
        return legal(level, body, node) ? Optional.of(node) : Optional.empty();
    }

    /** Whether that exact node still holds in the live world. */
    public static boolean legal(ServerLevel level, LivingEntity body, Node node) {
        if (level == null || body == null || node == null) return false;
        Vec3 feet = node.feet();
        // The node's own cell has to be the cell its feet really occupy, or it is somebody else's position.
        if (!cellOf(feet).equals(node.cell())) return false;
        if (feet.y < level.getMinBuildHeight()) return false;
        AABB box = BodyGeometry.bodyAt(body, feet);
        if (box == null) return false;
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        // Every cube this position needs is proven loaded before anything is queried: the whole body, the feet
        // cell and the eye cell. An unloaded column is refused, never read and never loaded to make it work.
        if (!BodyGeometry.loaded(level, box)) return false;
        if (!AmphibiousMovement.loaded(level, BlockPos.containing(feet))) return false;
        BlockPos eyeCell = eyeCell(body, feet);
        if (!AmphibiousMovement.loaded(level, eyeCell)) return false;
        if (!level.noCollision(body, box)) return false;
        if (AmphibiousMovement.overlapsBody(level, body, box)) return false;
        if (BodyGeometry.hazardous(level, box)) return false;
        if (!waterOnly(level, box)) return false;
        // Both the real feet and the real eye have to be under water: a guardian is not a shore creature, and a
        // body whose eye is above the surface cannot legally hold this position.
        return isWater(level, BlockPos.containing(feet)) && isWater(level, eyeCell);
    }

    /** Whether the body really is on that node right now - the real sample, not only the cell. */
    public static boolean holds(ServerLevel level, LivingEntity body, Node node) {
        if (level == null || body == null || node == null) return false;
        if (body.position().distanceToSqr(node.feet()) > 1.0E-8D) return false;
        return legal(level, body, node);
    }

    /** Whether the whole body - not only its feet cell - stays inside the battlefield at that node. */
    public static boolean bodyInsideZone(LivingEntity body, Node node, Predicate<GridPos> zone) {
        return node != null && BodyGeometry.bodyInsideZone(body, node.feet(), zone);
    }

    /** The cell a real feet point occupies, with the tolerance the rest of this package uses. */
    public static GridPos cellOf(Vec3 feet) {
        return new GridPos(BodyGeometry.floor(feet.x), BodyGeometry.floor(feet.y), BodyGeometry.floor(feet.z));
    }

    /** The real eye position of that body at those feet: vanilla's own eye height above the feet. */
    public static Vec3 eye(LivingEntity body, Vec3 feet) {
        return new Vec3(feet.x, feet.y + body.getEyeHeight(), feet.z);
    }

    private static BlockPos eyeCell(LivingEntity body, Vec3 feet) {
        return BlockPos.containing(eye(body, feet));
    }

    // ------------------------------------------------------------------ medium

    /** Whether that block holds real water; the caller has already proven the column loaded. */
    public static boolean isWater(ServerLevel level, BlockPos pos) {
        return level != null && pos != null && level.getFluidState(pos).is(FluidTags.WATER);
    }

    /**
     * Whether the only fluid inside that box is water.
     *
     * <p>Lava and every other liquid refuse the position exactly as they do for the other movement policies;
     * water is the medium this body is allowed to occupy, so it is not refused here - the feet and eye tests are
     * what decide whether the position is really under water.</p>
     */
    public static boolean waterOnly(ServerLevel level, AABB box) {
        if (level == null || box == null) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BodyGeometry.floor(box.minX), BodyGeometry.floor(box.minY),
                BodyGeometry.floor(box.minZ), BodyGeometry.floor(box.maxX), BodyGeometry.floor(box.maxY),
                BodyGeometry.floor(box.maxZ))) {
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ edges

    /** One of the 26 neighbouring cells; water movement is three-dimensional, like the drowned's. */
    public static boolean adjacent(GridPos from, GridPos to) {
        return from != null && to != null && AerialMovement.adjacent(from, to);
    }

    /**
     * Whether the real body may travel this one edge.
     *
     * <p>The destination has to be a legal water body, the whole swept union has to clear - inside the world, fully
     * loaded, no block, no hazard, no other or non-water liquid and nobody else in the way - and the medium has to
     * hold for the <em>whole</em> leg, not only at its two ends: see {@link #waterThroughout}. The swept box is the
     * conservative answer for a diagonal in three dimensions, so no corner is cut between two animated frames.</p>
     */
    public static boolean canStep(ServerLevel level, LivingEntity body, Node from, Node to,
                                  Predicate<GridPos> zone) {
        if (level == null || body == null || from == null || to == null) return false;
        if (!adjacent(from.cell(), to.cell())) return false;
        if (!legal(level, body, to)) return false;
        if (!bodyInsideZone(body, to, zone)) return false;
        return waterThroughout(level, body, from.feet(), to.feet())
                && sweepClear(level, body, from.feet(), to.feet(), zone);
    }

    /**
     * Whether the medium really holds for the whole leg: every block the real feet and the real eye pass through.
     *
     * <p>Two water endpoints prove nothing about the leg between them. A diagonal step from one water cell to
     * another leaves through a corner whose third cell may be air, and the body would then swim across air for part
     * of the animation - a crossing this ruleset does not support. The conservative answer is used here: the
     * straight leg can only ever enter blocks inside the union of its two endpoint blocks, so every block of that
     * union is asked, for the feet and for the real eye. A water corner between two water cells is refused, and a
     * continuous pool is not.</p>
     *
     * <p>The same function is asked for the whole edge before it starts and for every animated frame, so planning
     * and execution share one answer instead of two similar ones. Every block is proven loaded first, so nothing is
     * ever read at an unloaded position.</p>
     */
    public static boolean waterThroughout(ServerLevel level, LivingEntity body, Vec3 from, Vec3 to) {
        if (level == null || body == null || from == null || to == null) return false;
        if (!bothUnderWater(level, body, from) || !bothUnderWater(level, body, to)) return false;
        return mediumAlong(level, from, to) && mediumAlong(level, eye(body, from), eye(body, to));
    }

    /** The feet and the real eye of one body position are both in water. */
    public static boolean bothUnderWater(ServerLevel level, LivingEntity body, Vec3 feet) {
        if (level == null || body == null || feet == null) return false;
        return isWater(level, BlockPos.containing(feet)) && isWater(level, eyeCell(body, feet));
    }

    /** Every block of the union between those two points is water, proven loaded before it is read. */
    private static boolean mediumAlong(ServerLevel level, Vec3 from, Vec3 to) {
        BlockPos a = BlockPos.containing(from);
        BlockPos b = BlockPos.containing(to);
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());
        if (!BodyGeometry.loaded(level, minX, minY, minZ, maxX, maxY, maxZ)) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!isWater(level, new BlockPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    /**
     * One animated leg of this family, proved as one full body sweep.
     *
     * <p>Asked for the whole edge before it starts and again for every single frame the animation takes, so a
     * block placed into the arc, a drained cell, another body stepping in, an unloaded column or a shrunken
     * battlefield stops the body where it really is instead of being walked through.</p>
     */
    public static boolean sweepClear(ServerLevel level, LivingEntity body, Vec3 from, Vec3 to,
                                     Predicate<GridPos> zone) {
        if (level == null || body == null || from == null || to == null) return false;
        AABB box = BodyGeometry.swept(body, from, to);
        if (box == null) return false;
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        if (!BodyGeometry.loaded(level, box)) return false;
        if (!level.noCollision(body, box)) return false;
        if (AmphibiousMovement.overlapsBody(level, body, box)) return false;
        if (BodyGeometry.hazardous(level, box)) return false;
        if (!waterOnly(level, box)) return false;
        return BodyGeometry.boxInsideZone(box, zone);
    }

    /**
     * The real legal neighbours of one node, in three dimensions and deterministically ordered.
     *
     * <p>Candidates are sorted by distance to the reference cell, then by height, x and z, so the same world
     * always answers with the same route.</p>
     */
    public static List<Node> neighbours(ServerLevel level, LivingEntity body, Node from, GridPos reference,
                                        Vec3 offset, Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        if (level == null || body == null || from == null) return List.of();
        List<GridPos> cells = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    cells.add(from.cell().offset(dx, dy, dz));
                }
            }
        }
        GridPos anchor = reference == null ? from.cell() : reference;
        cells.sort(Comparator.comparingInt((GridPos cell) -> manhattan(cell, anchor))
                .thenComparingInt(GridPos::y)
                .thenComparingInt(GridPos::x)
                .thenComparingInt(GridPos::z));
        List<Node> result = new ArrayList<>();
        for (GridPos cell : cells) {
            if (zone != null && !zone.test(cell)) continue;
            if (occupied != null && occupied.test(cell)) continue;
            if (cell.equals(from.cell())) continue;
            Optional<Node> node = waterNode(level, body, cell, offset);
            if (node.isEmpty()) continue;
            if (!bodyInsideZone(body, node.get(), zone)) continue;
            if (!canStep(level, body, from, node.get(), zone)) continue;
            result.add(node.get());
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ bounded swim search

    /**
     * One bounded search for a route to a firing position, or to the best real progress there is.
     *
     * <p>The search is a breadth-first walk over real water nodes: 26 neighbours per node, every candidate proved
     * against the live world, at most {@link #MAX_SEARCH_NODES} discovered states - the start included, so the
     * 513th is never registered - and at most {@code maxEdges} edges. The first node from which the caller's own
     * goal really holds ends the search; when no such node exists inside the budget, the route whose real
     * distance to the reference cell is smallest is returned, because standing still is never a better answer
     * than a legal step. A cell another participant holds is never stepped on.</p>
     *
     * @param goal whether the caller's firing position really holds at that node
     */
    public static Optional<Route> swimTo(ServerLevel level, LivingEntity body, Node from, GridPos reference,
                                         Vec3 offset, int maxEdges, Predicate<GridPos> occupied,
                                         Predicate<GridPos> zone, Predicate<Node> goal,
                                         java.util.function.ToIntFunction<Node> score) {
        if (level == null || body == null || from == null) return Optional.empty();
        if (maxEdges <= 0) return Optional.empty();
        int limit = MAX_SEARCH_NODES;
        Set<GridPos> visited = new HashSet<>();
        visited.add(from.cell());
        ArrayDeque<List<Node>> queue = new ArrayDeque<>();
        queue.add(List.of());
        Node best = null;
        List<Node> bestPath = List.of();
        GridPos anchor = reference == null ? from.cell() : reference;
        // A prefix is only an answer when it is really better than standing still, measured by the caller's own
        // firing band. A single "closer to the target" score would swim an ordinary guardian towards a target it
        // is already too close to; that is why the comparison is strict and the score is the band's, not a distance.
        int bestScore = score == null ? Integer.MAX_VALUE : score.applyAsInt(from);
        // The start is the first discovered state; the budget counts discovered states, so the search answers at
        // exactly 512 of them and never registers the 513th.
        int discovered = 1;
        while (!queue.isEmpty()) {
            List<Node> path = queue.poll();
            if (path.size() >= maxEdges) continue;
            Node current = path.isEmpty() ? from : path.get(path.size() - 1);
            for (Node next : neighbours(level, body, current, anchor, offset, occupied, zone)) {
                if (visited.contains(next.cell())) continue;
                if (discovered >= limit) return best == null ? Optional.empty() : Optional.of(new Route(bestPath));
                visited.add(next.cell());
                discovered++;
                List<Node> extended = new ArrayList<>(path);
                extended.add(next);
                if (goal != null && goal.test(next)) return Optional.of(new Route(extended));
                int value = score == null ? manhattan(next.cell(), anchor) : score.applyAsInt(next);
                if (value < bestScore) {
                    bestScore = value;
                    best = next;
                    bestPath = List.copyOf(extended);
                }
                queue.add(extended);
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Route(bestPath));
    }

    // ------------------------------------------------------------------ deployment

    /**
     * Nearest position this body can really hold under water, or empty when nothing inside the bounded search
     * does.
     *
     * <p>The body's own position is tried first and returned unchanged - fractional coordinates included - so a
     * guardian that is already in legal water is never moved onto a cell centre. Only when it cannot hold where it
     * is does a bounded neighbourhood get searched, nearest first, and only over positions whose whole body is
     * already loaded and inside the battlefield that would be committed. A body with no legal water position is
     * refused; nothing here is force-loaded, no land position is ever offered and the entity is never moved to
     * find out.</p>
     */
    public static Optional<Vec3> findNearestHold(ServerLevel level, LivingEntity body,
                                                 Predicate<BlockPos> occupied, Predicate<GridPos> zone,
                                                 Predicate<Vec3> ready) {
        if (level == null || body == null) return Optional.empty();
        Vec3 source = body.position();
        // Admission may adjust a wet body's safe position, but never teleports a dry body into a nearby pool.
        if (!BodyGeometry.loaded(level, BodyGeometry.bodyAt(body, source))
                || !bothUnderWater(level, body, source)) return Optional.empty();
        if (holdable(level, body, source, occupied, zone, ready)) return Optional.of(source);
        // The fractional offset is kept, because it can be both nearer and the only way a wide body fits.
        Vec3 anchor = new Vec3(BodyGeometry.floor(source.x) + 0.5D, source.y, BodyGeometry.floor(source.z) + 0.5D);
        Vec3 offset = source.subtract(anchor);
        List<Vec3> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                for (int dy = -8; dy <= 8; dy++) {
                    GridPos cell = new GridPos(BodyGeometry.floor(source.x) + dx,
                            BodyGeometry.floor(source.y) + dy, BodyGeometry.floor(source.z) + dz);
                    candidates.add(AerialMovement.feet(cell, offset));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Vec3 point) -> point.distanceToSqr(source))
                .thenComparingDouble(point -> Math.abs(point.y - source.y))
                .thenComparingDouble(point -> point.y).thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.z));
        for (Vec3 candidate : candidates) {
            if (holdable(level, body, candidate, occupied, zone, ready)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** One candidate feet position, judged before any block is read: occupied, inside, ready, then really legal. */
    private static boolean holdable(ServerLevel level, LivingEntity body, Vec3 feet, Predicate<BlockPos> occupied,
                                    Predicate<GridPos> zone, Predicate<Vec3> ready) {
        BlockPos cell = BlockPos.containing(feet);
        GridPos grid = new GridPos(cell.getX(), cell.getY(), cell.getZ());
        if (occupied != null && occupied.test(cell)) return false;
        if (zone != null && !zone.test(grid)) return false;
        if (ready != null && !ready.test(feet)) return false;
        return legal(level, body, new Node(grid, feet));
    }

    // ------------------------------------------------------------------ native sight

    /**
     * Vanilla's own line of sight, asked from the real eye this body would have at those feet.
     *
     * <p>The rule is the one {@code LivingEntity#hasLineOfSight} applies - a collider clip from the viewer's eye to
     * the target's eye that ignores fluids and refuses beyond 128 blocks - so planning and the native attack goal
     * share one answer instead of two similar ones. Water is not an obstruction, which is what lets an underwater
     * guardian see its target through the medium it swims in.</p>
     */
    public static boolean lineOfSight(ServerLevel level, LivingEntity body, Vec3 feet, LivingEntity target) {
        if (level == null || body == null || feet == null || target == null) return false;
        if (target.level() != level) return false;
        Vec3 eye = eye(body, feet);
        Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        if (targetEye.distanceTo(eye) > NATIVE_SIGHT_RANGE) return false;
        return level.clip(new ClipContext(eye, targetEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body))
                .getType() == HitResult.Type.MISS;
    }

    private static int manhattan(GridPos from, GridPos to) {
        if (from == null || to == null) return Integer.MAX_VALUE;
        return Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z());
    }

    /**
     * One bounded slice of a real-feet descent for this family.
     *
     * <p>A body this encounter had to let go of is never snapped onto a cell centre: it keeps the real horizontal
     * position it reached and descends continuously, and every slice is proved against the world as the whole body
     * it really is. Water is the medium it fell out of, so water is not an obstruction here - blocks, hazards and
     * other bodies are.</p>
     */
    public static boolean descentSliceClear(ServerLevel level, LivingEntity body, Vec3 from, Vec3 to) {
        if (level == null || body == null || from == null || to == null) return false;
        AABB box = BodyGeometry.swept(body, from, to);
        if (box == null) return false;
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        if (!BodyGeometry.loaded(level, box)) return false;
        if (!level.noCollision(body, box)) return false;
        if (AmphibiousMovement.overlapsBody(level, body, box)) return false;
        return !BodyGeometry.hazardous(level, box);
    }

    /**
     * The real position this body can settle on after a descent, or empty when it is not a legal water point.
     *
     * <p>The horizontal position is the one it really has - never the centre of the cell it happens to be in - and
     * the height is the top of the support it really reached. Empty means the water it fell out of is gone, which
     * is the shared safe release's answer and never a reason to snap the body somewhere it never swam to.</p>
     */
    public static Optional<Node> landingNode(ServerLevel level, LivingEntity body, Vec3 feet) {
        if (level == null || body == null || feet == null) return Optional.empty();
        Node node = new Node(cellOf(feet), feet);
        return legal(level, body, node) ? Optional.of(node) : Optional.empty();
    }

    /** The whole real body of that candidate is already loaded, asked before any block is read. */
    public static boolean bodyLoaded(ServerLevel level, LivingEntity body, Vec3 feet) {
        AABB box = BodyGeometry.bodyAt(body, feet);
        return box != null && BodyGeometry.loaded(level, box)
                && AmphibiousMovement.loaded(level, BlockPos.containing(feet));
    }
}
