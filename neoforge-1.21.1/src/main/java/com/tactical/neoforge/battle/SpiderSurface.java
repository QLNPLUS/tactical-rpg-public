package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Wall-and-floor surfaces for exactly two vanilla types: the spider and the cave spider.
 *
 * <p>The encounter's grid lets an actor stand on a supported floor and step four ways. A spider can also
 * hold a real wall, so this class adds that surface <em>for these two types only</em> - it never loosens
 * {@link LevelGridWorld}, the player rules or any other creature, and it never turns a spider into an aerial
 * actor ({@link AerialMovement#supports} stays untouched).</p>
 *
 * <ul>
 *   <li>A node keeps its real feet, its cell, its attachment (floor or wall) and, for a wall, which side the
 *       wall is on. Two nodes in one cell are therefore different nodes, so a distance table that only
 *       remembers {@link GridPos} cannot lose the support it has to re-prove later.</li>
 *   <li>A wall position is derived from the body's real half-width: a 1.4 wide spider is pushed back from the
 *       wall face it touches, a 0.7 wide cave spider is pulled towards it. The centre of the neighbouring cell
 *       is never used, because that centre would put a wide body inside the wall.</li>
 *   <li>A floor position may be shifted inside its own cell, again from the real half-width, because a wide
 *       body standing on the centre of a two-wide platform would hang over its own ledge even when the
 *       platform is wide enough to carry it. The shift is a fixed finite lattice, so the number of positions is
 *       bounded and no float noise can multiply nodes.</li>
 *   <li>Every candidate and every swept leg is proven loaded before a block, a fluid or a collision is read,
 *       and the whole body - not two corner chunks - is proven inside the world, the zone and clear of
 *       liquids, hazards, other bodies and the roster.</li>
 *   <li>An edge is one of a small, explicitly declared set of shapes, and it may contain a transient
 *       unsupported point (rising past a ledge before stepping onto it), but only a complete legal node is ever
 *       handed over: the search never parks a body on that point.</li>
 * </ul>
 *
 * <p>Nothing here moves an entity, charges anything or starts vanilla navigation: it answers questions about
 * the live world and the caller applies the answer at its own single commit point.</p>
 */
public final class SpiderSurface {

    /** Bounded like every other movement policy here: a search never turns into a world scan. */
    public static final int MAX_SEARCH_NODES = 512;

    private static final double EPSILON = 1.0E-7;
    /** The contact tolerance: a body counted as "on the wall" really touches its face. */
    private static final double CONTACT = 1.0E-4;
    /** Vanilla's own melee reach widening ({@code Mob.DEFAULT_ATTACK_REACH}), never a per-mob guess. */
    public static final double NATIVE_ATTACK_REACH = Math.sqrt(2.04F) - 0.6F;

    /**
     * The one lattice a floor position may be shifted on, inside its own cell.
     *
     * <p>An eighth of a block is finer than any real half-width difference this policy has to express and
     * coarse enough that identity can be quantised without letting rounding noise create positions.</p>
     */
    private static final double SHIFT_STEP = 0.125D;
    /** Three steps either way keeps the feet inside their own cell ({@code |shift| <= 0.375}). */
    private static final int MAX_SHIFT = 3;

    private static final List<Direction> HORIZONTAL = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

    /** Which surface holds this node. */
    public enum Attachment { GROUND, WALL }

    /**
     * One real standing position: the cell it answers for, the feet it really holds, and its surface.
     *
     * <p>Identity is the pair (cell, surface) plus, for a floor position, which lattice shift inside that cell
     * the body holds. The shift is part of the identity on purpose: a wide body's only legal position on a
     * two-wide ledge is a shifted one, and merging it with the (illegal) centre would throw that position away.
     * The shift is quantised, so identity never drifts with real coordinates and the candidate set stays
     * finite. {@code wall} is the side the wall is on relative to the cell, and is null exactly for a floor
     * node.</p>
     */
    public record Node(GridPos cell, Vec3 feet, Attachment attachment, Direction wall) {

        public Node {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(feet, "feet");
            Objects.requireNonNull(attachment, "attachment");
            if (attachment == Attachment.WALL ? wall == null : wall != null)
                throw new IllegalArgumentException("Only a wall node names a wall side");
            if (attachment == Attachment.WALL && wall.getAxis().isVertical())
                throw new IllegalArgumentException("A wall side is horizontal");
        }

        /** The plain centred floor position of that cell. */
        public static Node ground(GridPos cell) {
            return ground(cell, 0.0D, 0.0D);
        }

        /** A floor position shifted inside its own cell on the fixed lattice. */
        public static Node ground(GridPos cell, double shiftX, double shiftZ) {
            int x = shiftKey(shiftX);
            int z = shiftKey(shiftZ);
            return new Node(cell, new Vec3(cell.x() + 0.5D + x * SHIFT_STEP, cell.y(),
                    cell.z() + 0.5D + z * SHIFT_STEP), Attachment.GROUND, null);
        }

        /**
         * A floor node at the feet a body really holds right now.
         *
         * <p>Used when re-reading a real position, so a resync never quietly rounds a legal body onto the cell
         * centre: the feet stay exactly where the body is and only the identity is quantised.</p>
         */
        public static Node groundAt(GridPos cell, Vec3 feet) {
            return new Node(cell, feet, Attachment.GROUND, null);
        }

        public static Node wall(GridPos cell, Vec3 feet, Direction wall) {
            return new Node(cell, feet, Attachment.WALL, wall);
        }

        public boolean onWall() { return attachment == Attachment.WALL; }

        /** The lattice shift inside the cell this floor position holds; always zero for a wall position. */
        public int shiftX() { return attachment == Attachment.GROUND ? shiftKey(feet.x - (cell.x() + 0.5D)) : 0; }

        public int shiftZ() { return attachment == Attachment.GROUND ? shiftKey(feet.z - (cell.z() + 0.5D)) : 0; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Node node && node.cell.equals(cell)
                    && node.attachment == attachment && node.wall == wall
                    && node.shiftX() == shiftX() && node.shiftZ() == shiftZ();
        }

        @Override
        public int hashCode() { return Objects.hash(cell, attachment, wall, shiftX(), shiftZ()); }

        @Override
        public String toString() {
            return attachment + "@" + cell + (wall == null ? "" : "/" + wall)
                    + (attachment == Attachment.GROUND && (shiftX() != 0 || shiftZ() != 0)
                    ? "(" + shiftX() + "," + shiftZ() + ")" : "");
        }
    }

    private SpiderSurface() { }

    /** The lattice index of one real shift, clamped to the positions that stay inside the cell. */
    private static int shiftKey(double shift) {
        int key = (int) Math.round(shift / SHIFT_STEP);
        return Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, key));
    }

    /** Exact vanilla types only: no other monster inherits a wall by sharing a superclass. */
    public static boolean supports(LivingEntity actor) {
        if (actor == null) return false;
        EntityType<?> type = actor.getType();
        return type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER;
    }

    public static boolean isSpiderType(EntityType<?> type) {
        return type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER;
    }

    /**
     * Whether this encounter may take this exact body's displacement at all.
     *
     * <p>Sharing the class is not enough: a spider that is riding something, or one that carries a rider, is
     * somebody else's traffic. Those individuals keep vanilla behaviour entirely - this encounter neither
     * installs its pose seam on them nor detaches a rider to make its own movement easier.</p>
     */
    public static boolean controllable(LivingEntity actor) {
        if (!supports(actor)) return false;
        if (actor.isPassenger() || actor.getVehicle() != null) return false;
        return actor.getPassengers().isEmpty();
    }

    /** The body's real half-width; a wide spider and a narrow cave spider are never treated alike. */
    public static double halfWidth(LivingEntity actor) {
        return actor.getBbWidth() / 2.0D;
    }

    // ------------------------------------------------------------------ nodes

    /** The feet a body of this real width holds against that wall side of that cell. */
    public static Vec3 anchoredFeet(GridPos cell, Direction wall, LivingEntity actor) {
        // Distance from the cell centre to the wall face is always half a block; a body wider than that has to
        // be pushed back, a narrower one has to come closer to really touch it.
        double shift = halfWidth(actor) + CONTACT - 0.5D;
        Direction away = wall.getOpposite();
        return new Vec3(cell.x() + 0.5D + away.getStepX() * shift, cell.y(),
                cell.z() + 0.5D + away.getStepZ() * shift);
    }

    /** The wall block beside that cell in that direction. */
    public static BlockPos wallBlock(GridPos cell, Direction wall) {
        return new BlockPos(cell.x(), cell.y(), cell.z()).relative(wall);
    }

    /** A full solid face pointing back at the cell: the only wall shape this policy promises. */
    public static boolean wallFaceSolid(ServerLevel level, GridPos cell, Direction wall) {
        BlockPos pos = wallBlock(cell, wall);
        if (!AmphibiousMovement.loaded(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isFaceSturdy(level, pos, wall.getOpposite());
    }

    /** The wall node of one cell for one side, or empty when that position is not legal. */
    public static Optional<Node> wallNode(ServerLevel level, LivingEntity actor, GridPos cell, Direction wall) {
        if (level == null || actor == null || !wall.getAxis().isHorizontal()) return Optional.empty();
        // Nothing about this cell is read before its own column is proven loaded.
        if (!AmphibiousMovement.loaded(level, new BlockPos(cell.x(), cell.y(), cell.z()))) return Optional.empty();
        Node node = Node.wall(cell, anchoredFeet(cell, wall, actor), wall);
        return isLegal(level, actor, node) ? Optional.of(node) : Optional.empty();
    }

    /**
     * The floor node of one cell, or empty when a body of this size cannot stand there.
     *
     * <p>The plain centre is tried first, so every ordinary body keeps exactly the position it always had. A
     * wide body that cannot stand on the centre is then offered the bounded lattice of shifts inside the same
     * cell - the only positions it can really hold on a platform whose edge is one of its own columns - and is
     * still refused when nothing in that cell carries it.</p>
     */
    public static Optional<Node> groundNode(ServerLevel level, LivingEntity actor, GridPos cell) {
        if (level == null || actor == null || cell == null) return Optional.empty();
        if (!AmphibiousMovement.loaded(level, new BlockPos(cell.x(), cell.y(), cell.z()))) return Optional.empty();
        for (Node candidate : groundPositions(cell, actor)) {
            if (isLegal(level, actor, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * The bounded ladder of floor positions inside one cell: centre first, then the two lattice rings that this
     * body's own width can need, in every quadrant.
     *
     * <p>A body wider than a block overhangs two axes at once, so a platform that carries it has to be found
     * diagonally; and the two rings are the smallest shift that reaches past the overhang and one step further.
     * Everything here is finite and ordered, so the same question always answers the same position.</p>
     */
    private static List<Node> groundPositions(GridPos cell, LivingEntity actor) {
        List<Node> candidates = new ArrayList<>(17);
        candidates.add(Node.ground(cell));
        double overhang = Math.max(0.0D, halfWidth(actor) - 0.5D);
        int first = Math.max(1, (int) Math.ceil((overhang - EPSILON) / SHIFT_STEP));
        for (int key = first; key <= Math.min(MAX_SHIFT, first + 1); key++) {
            for (int sx = -1; sx <= 1; sx++) {
                for (int sz = -1; sz <= 1; sz++) {
                    if (sx == 0 && sz == 0) continue;
                    candidates.add(Node.ground(cell, sx * key * SHIFT_STEP, sz * key * SHIFT_STEP));
                }
            }
        }
        return candidates;
    }

    /**
     * The node the body really holds right now, resolved from its feet.
     *
     * <p>A body that is beside a wall and really touches it is a wall node at <em>its own</em> feet; otherwise
     * the answer is a floor node at those same real feet. Neither answer is ever fabricated: a body three
     * tenths of a block away from a wall is not "anchored" onto it, and a resync never normalises a legal
     * position onto the cell centre or an integer height.</p>
     */
    public static Node nodeAt(ServerLevel level, LivingEntity actor, GridPos cell, Vec3 feet) {
        if (actor == null || cell == null || feet == null) return null;
        AABB real = actor.getBoundingBox();
        for (Direction wall : HORIZONTAL) {
            if (!wallFaceSolid(level, cell, wall)) continue;
            Node candidate = Node.wall(cell, feet, wall);
            if (wallContactHolds(level, actor, candidate, real)) return candidate;
        }
        return Node.groundAt(cell, feet);
    }

    /**
     * Whether this node is a legal standing position for this body.
     *
     * <p>Floor nodes use the encounter's own deployment rule, which already proves integer feet, a solid upper
     * face under every column of the footprint and a whole body free of liquids, hazards and other bodies. Wall
     * nodes add the real contact: a solid face, no block in the body, and a body face that really touches the
     * wall face over a non-zero part of its own height.</p>
     */
    public static boolean isLegal(ServerLevel level, LivingEntity actor, Node node) {
        if (level == null || actor == null || node == null) return false;
        AABB box = bodyAt(actor, node.feet());
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        // The whole body, its own cell and the support column are proven loaded before any state is read.
        if (!AmphibiousMovement.loaded(level, box)) return false;
        if (!AmphibiousMovement.loaded(level, new BlockPos(node.cell().x(), node.cell().y(), node.cell().z())))
            return false;
        if (!level.noCollision(actor, box)) return false;
        if (AmphibiousMovement.overlapsBody(level, actor, box)) return false;
        if (AmphibiousMovement.hazardous(level, box)) return false;
        if (wetted(level, box)) return false;
        if (node.onWall()) return wallContactHolds(level, actor, node, box);
        return BattleDeployment.isStandable(level, actor, node.feet());
    }

    /**
     * The wall node still really holds this body: the wall is there, the body really touches its face, and the
     * two overlap over more than a tolerance of height.
     *
     * <p>The height is part of the contact, not a detail: a body whose feet are above the top of the wall it
     * names is next to that wall, not held by it, and must not be carried by it.</p>
     */
    public static boolean wallContactHolds(ServerLevel level, LivingEntity actor, Node node, AABB box) {
        if (!node.onWall() || !wallFaceSolid(level, node.cell(), node.wall())) return false;
        BlockPos wallPos = wallBlock(node.cell(), node.wall());
        double wallBottom = wallPos.getY();
        double wallTop = wallPos.getY() + 1.0D;
        double overlap = Math.min(box.maxY, wallTop) - Math.max(box.minY, wallBottom);
        if (overlap <= CONTACT) return false;
        Direction away = node.wall().getOpposite();
        // The body face towards the wall must sit on the wall plane within the contact tolerance.
        double face = away.getStepX() > 0 ? box.minX : away.getStepX() < 0 ? box.maxX
                : away.getStepZ() > 0 ? box.minZ : box.maxZ;
        double plane = switch (node.wall()) {
            case WEST -> wallPos.getX() + 1.0D;
            case EAST -> wallPos.getX();
            case NORTH -> wallPos.getZ() + 1.0D;
            case SOUTH -> wallPos.getZ();
            default -> Double.NaN;
        };
        return Math.abs(face - plane) <= CONTACT * 2.0D;
    }

    /** Whether the body, at its real position, really holds this wall right now. */
    public static boolean holdsWall(ServerLevel level, LivingEntity actor, Node node) {
        if (level == null || actor == null || node == null || !node.onWall()) return false;
        return wallContactHolds(level, actor, node, actor.getBoundingBox());
    }

    // ------------------------------------------------------------------ edges

    /**
     * Whether the two nodes are one of the edges this policy declares at all.
     *
     * <p>Both nodes being legal is not enough: two legal positions three blocks apart are not one step, and
     * neither are two wall positions on different faces. The search may only build the neighbours
     * {@link #neighbours} offers, and this rule refuses anything else even when a planner - or a fixture -
     * proposes it, so a far pair of valid endpoints can never be joined by a single free edge.</p>
     */
    public static boolean adjacent(Node from, Node to) {
        if (from == null || to == null) return false;
        int dx = to.cell().x() - from.cell().x();
        int dy = to.cell().y() - from.cell().y();
        int dz = to.cell().z() - from.cell().z();
        if (from.onWall() && to.onWall()) {
            // Crossing a corner between two walls is not one step.
            if (from.wall() != to.wall()) return false;
            if (dx == 0 && dz == 0) return Math.abs(dy) == 1;
            // Sideways along the same face: parallel to it, one cell, same height.
            boolean along = from.wall().getAxis() == Direction.Axis.Z ? dx != 0 && dz == 0 : dz != 0 && dx == 0;
            return dy == 0 && along && Math.abs(dx) + Math.abs(dz) == 1;
        }
        if (!from.onWall() && to.onWall()) {
            // Entering a wall of the cell the body occupies, or of one of the four columns beside it,
            // including one block up - the only places the neighbour pass offers.
            return dy >= 0 && dy <= 1 && Math.abs(dx) + Math.abs(dz) <= 1;
        }
        if (from.onWall()) {
            // Down onto the floor of the same cell, or the declared mantle onto the top of this very wall.
            if (dx == 0 && dy == 0 && dz == 0) return true;
            BlockPos top = wallBlock(from.cell(), from.wall());
            return to.cell().equals(new GridPos(top.getX(), top.getY() + 1, top.getZ()));
        }
        return GridMovementRules.canStep(from.cell(), to.cell());
    }

    /**
     * Whether the whole body may travel one edge.
     *
     * <p>Both endpoints must be legal now, the edge must be one of the declared shapes, and every leg of the
     * route the animation really takes must sweep clear of blocks and of any liquid at all: a rise is a
     * vertical move and then a horizontal one, a drop is the other way round, so the ledge of a platform is
     * crossed by the body instead of through it. The transient point between the legs needs no support - that
     * is what makes a mantle possible - but it is never a node the caller may stop on.</p>
     */
    public static boolean canStep(ServerLevel level, LivingEntity actor, Node from, Node to) {
        return canStep(level, actor, from, to, null);
    }

    /** The same edge, with the whole swept body also required to stay inside the battlefield. */
    public static boolean canStep(ServerLevel level, LivingEntity actor, Node from, Node to,
                                  Predicate<GridPos> zone) {
        if (level == null || actor == null || from == null || to == null) return false;
        if (!adjacent(from, to)) return false;
        if (!isLegal(level, actor, from) || !isLegal(level, actor, to)) return false;
        if (zone != null) {
            if (!boxInsideZone(bodyAt(actor, from.feet()), zone)) return false;
            if (!boxInsideZone(bodyAt(actor, to.feet()), zone)) return false;
            if (!boxInsideZone(swept(actor, from.feet(), to.feet()), zone)) return false;
        }
        Vec3 via = AmphibiousMovement.via(from.feet(), to.feet());
        if (via == null) return legClear(level, actor, from.feet(), to.feet());
        if (zone != null && !boxInsideZone(bodyAt(actor, via), zone)) return false;
        return AmphibiousMovement.clearAt(level, actor, via)
                && legClear(level, actor, from.feet(), via)
                && legClear(level, actor, via, to.feet());
    }

    /** One leg is clear only when nothing solid is in the swept body and nothing in it is liquid. */
    private static boolean legClear(ServerLevel level, LivingEntity actor, Vec3 from, Vec3 to) {
        return AmphibiousMovement.sweepClear(level, actor, from, to) && !wetted(level, swept(actor, from, to));
    }

    /**
     * Every node this body may step to from that node, in one bounded pass.
     *
     * <p>Floor nodes keep the encounter's ordinary four-way rule, plus entering a wall in the same cell. Wall
     * nodes add a vertical rise/descent, a sideways move along the same wall, stepping down onto a floor, and
     * the mantle onto the top of the wall. Nothing else: no ceiling, no free air, no diagonal.</p>
     */
    public static List<Node> neighbours(ServerLevel level, LivingEntity actor, Node from,
                                        Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        if (level == null || actor == null || from == null) return List.of();
        List<Node> out = new ArrayList<>(16);
        GridPos cell = from.cell();
        if (!from.onWall()) {
            for (Direction direction : HORIZONTAL) {
                GridPos flat = cell.offset(direction.getStepX(), 0, direction.getStepZ());
                // Bounded heights, then the body's own answer. The shared `standCellAt` asks for a fixed
                // two-block headroom, which a 0.9 tall spider standing under a one block ceiling never has; the
                // real standing test inside `groundNode`/`isLegal` is this body's own box, which is what decides
                // whether it really fits. Rises and drops stay bounded to one block by the ordinary step rule.
                for (int dy : new int[]{0, 1, -1}) {
                    GridPos stand = flat.offset(0, dy, 0);
                    if (!GridMovementRules.canStep(cell, stand)) continue;
                    if (!admissible(level, actor, stand, occupied, zone)) continue;
                    groundNode(level, actor, stand).ifPresent(out::add);
                }
            }
            // Entering a wall of the cell the body already occupies is a real displacement, so it is an edge.
            for (Direction wall : HORIZONTAL) {
                wallNode(level, actor, cell, wall).ifPresent(out::add);
            }
            // The same for the four neighbouring columns, including one block up: a wide body reaches a wall it
            // is about to hug or start climbing from the cell beside it, and insisting on the floor node of the
            // wall's own cell would refuse that body the only position it can really take next to a wall.
            for (Direction direction : HORIZONTAL) {
                for (int dy : new int[]{0, 1}) {
                    GridPos beside = cell.offset(direction.getStepX(), dy, direction.getStepZ());
                    if (!admissible(level, actor, beside, occupied, zone)) continue;
                    for (Direction wall : HORIZONTAL) {
                        wallNode(level, actor, beside, wall).ifPresent(out::add);
                    }
                }
            }
        } else {
            // Up and down while the wall really continues.
            for (int dy : new int[]{1, -1}) {
                GridPos next = cell.offset(0, dy, 0);
                if (!admissible(level, actor, next, occupied, zone)) continue;
                wallNode(level, actor, next, from.wall()).ifPresent(out::add);
            }
            // Sideways along the same wall.
            for (Direction direction : HORIZONTAL) {
                if (direction.getAxis() == from.wall().getAxis()) continue;
                GridPos next = cell.offset(direction.getStepX(), 0, direction.getStepZ());
                if (!admissible(level, actor, next, occupied, zone)) continue;
                wallNode(level, actor, next, from.wall()).ifPresent(out::add);
            }
            // Down onto a floor in the same cell.
            if (admissible(level, actor, cell, occupied, zone)) {
                groundNode(level, actor, cell).ifPresent(out::add);
            }
            // The mantle: only where this wall really ends here, and the top of it carries this body. A wide
            // body is offered its shifted positions on that top, so a platform wide enough to carry it is
            // reached while a one-column ledge still refuses it.
            BlockPos top = wallBlock(cell, from.wall());
            if (!wallFaceSolid(level, cell.above(), from.wall())) {
                GridPos landing = new GridPos(top.getX(), top.getY() + 1, top.getZ());
                if (admissible(level, actor, landing, occupied, zone)) {
                    groundNode(level, actor, landing).ifPresent(out::add);
                }
            }
        }
        List<Node> eligible = new ArrayList<>(out.size());
        for (Node candidate : out) {
            // A cell that is occupied or outside the zone is refused even when the body would only clip it.
            if (candidate.equals(from)) continue;
            if (!canStep(level, actor, from, candidate, zone)) continue;
            eligible.add(candidate);
        }
        return List.copyOf(eligible);
    }

    /** Cell-level gates the caller owns, applied before anything about the candidate is read. */
    private static boolean admissible(ServerLevel level, LivingEntity actor, GridPos cell,
                                      Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        if (cell == null) return false;
        if (occupied != null && occupied.test(cell)) return false;
        if (zone != null && !zone.test(cell)) return false;
        return AmphibiousMovement.loaded(level, new BlockPos(cell.x(), cell.y(), cell.z()));
    }

    /**
     * The whole body really stays inside the zone for this node.
     *
     * <p>Asked in addition to the cell test, because a 1.4 wide body reaches into a neighbouring column: a unit
     * whose feet cell is in the battlefield can still have its shoulders over the boundary.</p>
     */
    public static boolean bodyInsideZone(LivingEntity actor, Node node, Predicate<GridPos> zone) {
        if (zone == null) return true;
        if (actor == null || node == null) return false;
        return boxInsideZone(bodyAt(actor, node.feet()), zone);
    }

    /** The same test for a swept body: every cube the animation can occupy is inside the battlefield. */
    public static boolean sweptInsideZone(LivingEntity actor, Vec3 from, Vec3 to, Predicate<GridPos> zone) {
        if (zone == null) return true;
        if (actor == null || from == null || to == null) return false;
        return boxInsideZone(swept(actor, from, to), zone);
    }

    private static boolean boxInsideZone(AABB box, Predicate<GridPos> zone) {
        for (int x = floor(box.minX); x <= floor(box.maxX); x++) {
            for (int y = floor(box.minY); y <= floor(box.maxY); y++) {
                for (int z = floor(box.minZ); z <= floor(box.maxZ); z++) {
                    if (!zone.test(new GridPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    private static int floor(double value) {
        return net.minecraft.util.Mth.floor(value + EPSILON);
    }

    // ------------------------------------------------------------------ geometry primitives

    public static AABB bodyAt(LivingEntity actor, Vec3 feet) {
        return actor.getBoundingBox().move(feet.subtract(actor.position()));
    }

    /** The union of the body at both ends of one leg: every cube that sweep can touch. */
    public static AABB swept(LivingEntity actor, Vec3 from, Vec3 to) {
        return bodyAt(actor, from).minmax(bodyAt(actor, to));
    }

    /** The body's real attack box at that feet position: vanilla's horizontal-only reach widening. */
    public static AABB attackBoxAt(LivingEntity actor, Vec3 feet) {
        return bodyAt(actor, feet).inflate(NATIVE_ATTACK_REACH, 0.0D, NATIVE_ATTACK_REACH);
    }

    /** Any liquid at all refuses the position, exactly as the encounter's own standing rule does. */
    private static boolean wetted(ServerLevel level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(floor(box.minX), floor(box.minY), floor(box.minZ),
                floor(box.maxX), floor(box.maxY), floor(box.maxZ))) {
            if (!level.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    /**
     * The frames one edge needs to be animated without cutting its own corner.
     *
     * <p>A two-leg route - up and then across, or across and then down - needs both legs to be visited: with a
     * single leg's worth of frames the interpolation would jump from one leg straight onto the other and sweep
     * the block corner between them, which is exactly what the rule proved clear.</p>
     */
    public static int stepFrames(int base, Vec3 from, Vec3 to) {
        int ticks = Math.max(1, base);
        return AmphibiousMovement.via(from, to) == null ? ticks : ticks * 2;
    }

    /**
     * The feet position one edge really passes through at that progress.
     *
     * <p>The same route the rule proved clear is the route the animation takes, so a step never sweeps a leg
     * that was not checked: a rise goes up and then across, a drop goes across and then down.</p>
     */
    public static Vec3 stepPoint(Vec3 from, Vec3 to, double progress) {
        Vec3 middle = AmphibiousMovement.via(from, to);
        if (middle == null) return from.lerp(to, progress);
        double half = 0.5D;
        return progress <= half
                ? from.lerp(middle, progress / half)
                : middle.lerp(to, (progress - half) / half);
    }

    /** Whether every edge of a planned route still holds in the live world. */
    public static boolean routeHolds(ServerLevel level, LivingEntity actor, List<Node> route, Node start) {
        return routeHolds(level, actor, route, start, null);
    }

    /** The same route, with every swept leg also required to stay inside the battlefield. */
    public static boolean routeHolds(ServerLevel level, LivingEntity actor, List<Node> route, Node start,
                                     Predicate<GridPos> zone) {
        if (level == null || actor == null || route == null || start == null) return false;
        if (route.size() > MAX_SEARCH_NODES) return false;
        Node previous = start;
        for (Node next : route) {
            if (next == null) return false;
            if (!canStep(level, actor, previous, next, zone)) return false;
            previous = next;
        }
        return true;
    }
}
