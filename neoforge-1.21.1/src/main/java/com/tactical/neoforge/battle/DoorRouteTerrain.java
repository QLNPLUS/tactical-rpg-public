package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridDirection;
import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.ai.DoorRoutePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Terrain view for the one actor that may walk through a door: the real body decides, not the block shape.
 *
 * <p>The battle's own {@link LevelGridWorld#isStandable} refuses any non-empty collision shape in the feet or
 * head block. That is right for every other unit - it never lets anyone stand inside a door - but it also
 * refuses an <em>already open</em> door, whose only remaining collision is a thin leaf on one side of the
 * block. A door-capable actor therefore asks two precise questions instead:</p>
 * <ul>
 *   <li>does the actor's real body, standing centred in the cell, intersect any real collision shape? An open
 *       leaf does not overlap a 0.6-wide body, so the open doorway is walkable while the same leaf still stops
 *       a body that would really touch it;</li>
 *   <li>for a <em>closed</em> wooden door, could the step be made if exactly that one pair were gone? Only that
 *       pair is ignored - walls, head clearance, liquid, support, step height, the battle zone and other units
 *       all stay.</li>
 * </ul>
 *
 * <p>Both questions test the swept volume of the step, not just its two endpoints, so a body can never tunnel
 * through a leaf that sits between them. A legal one-block step up is still legal: the destination's own
 * support block is not something the body can enter, so it is never treated as an obstacle.</p>
 *
 * <p>The view also answers what this battle may <em>ask</em> of the world. It is bounded to this zone and it
 * never loads a chunk: a column inside the zone whose chunk is not loaded is reported as undecided
 * ({@link #sawUnloaded()}), never as a wall, and a column outside the zone is an ordinary boundary - not a
 * gap in knowledge. This class never changes the world and never decides anything; it only answers
 * {@link DoorRoutePlanner.Terrain}, and it is only ever handed to an actor whose adapter declares that it
 * breaks doors.</p>
 */
public final class DoorRouteTerrain implements DoorRoutePlanner.Terrain {
    /** A hair of slack so a body exactly touching a face is not treated as inside it. */
    private static final double EPSILON = 1.0E-7D;

    private final ServerLevel level;
    private final LivingEntity actor;
    private final Predicate<GridPos> insideZone;
    private final Predicate<GridPos> occupiedByOthers;
    private boolean sawUnloaded;

    public DoorRouteTerrain(ServerLevel level, LivingEntity actor, Predicate<GridPos> insideZone,
                            Predicate<GridPos> occupiedByOthers) {
        this.level = level;
        this.actor = actor;
        this.insideZone = insideZone;
        this.occupiedByOthers = occupiedByOthers;
    }

    @Override
    public GridPos standCell(int x, int z, int refY) {
        // Same order as the plain terrain adapter: the reference height, one up, then one down.
        for (int dy = 0; dy <= 1; dy++) {
            GridPos cell = new GridPos(x, refY + dy, z);
            GridPos resolved = resolve(cell);
            if (resolved != null) return resolved;
        }
        return resolve(new GridPos(x, refY - 1, z));
    }

    /** The cell itself when this actor could occupy it, otherwise null. */
    private GridPos resolve(GridPos cell) {
        // Zone first: a cell outside this battle is not a place to walk, and nothing about the world is read
        // for it - so an unloaded column outside the zone is a boundary, not a gap in knowledge.
        if (!insideZone.test(cell)) return null;
        if (!known(cell)) return null;
        if (bodyFits(cell, null, null)) return cell;
        DoorRoutePlanner.Door door = closedDoorAt(cell);
        return door != null && bodyFits(cell, door, null) ? cell : null;
    }

    @Override
    public boolean canEnter(GridPos from, GridPos to) {
        // Standing in a closed door cell means that pair is already assumed gone for this route, so the same
        // pair is ignored while stepping out of it; otherwise nothing is ignored.
        return enterable(from, to, null);
    }

    @Override
    public boolean canEnterThroughDoor(GridPos from, GridPos to, DoorRoutePlanner.Door door) {
        return enterable(from, to, door);
    }

    /**
     * One real step, in the virtual world the route assumes.
     *
     * <p>Two pairs may be missing at the same time: the door this step walks through, and the door the actor
     * is standing in (a previous step of the same route already assumed that one gone). Ignoring only the
     * first would make a two-door corridor look impassable, because the step out of the first doorway would
     * still hit the first door. Nothing else is ever ignored, so no wooden door becomes air for an ordinary
     * step.</p>
     */
    private boolean enterable(GridPos from, GridPos to, DoorRoutePlanner.Door requested) {
        if (from == null || to == null || !GridMovementRules.canStep(from, to)) return false;
        if (!insideZone.test(to) || occupiedByOthers.test(to)) return false;
        if (!known(to)) return false;
        DoorRoutePlanner.Door standingIn = closedDoorAt(from);
        DoorRoutePlanner.Door first = requested != null ? requested : standingIn;
        DoorRoutePlanner.Door second = requested != null && standingIn != null && !requested.equals(standingIn)
                ? standingIn : null;
        if (!bodyFits(to, first, second)) return false;
        // The swept body of the whole step, so a leaf between two legal endpoints still stops the actor. A
        // one-block step up passes over the destination's own floor, which is not a body-shaped obstacle.
        BlockPos skipped = to.y() > from.y() ? block(to).below()
                : from.y() > to.y() ? block(from).below() : null;
        return clear(bodyBox(from).minmax(bodyBox(to)), first, second, skipped);
    }

    /**
     * The closed wooden door whose lower half is this cell, or null.
     *
     * <p>"Wooden" is the shipped {@code minecraft:wooden_doors} tag, not the method named
     * {@code isWoodenDoor}: in this version that method really answers "can be opened by hand", which is a
     * different question and must not be used to let a metal door through. Iron doors, trapdoors, fence gates
     * and walls are therefore never candidates, and an already open door is not a door to break either.</p>
     */
    @Override
    public DoorRoutePlanner.Door closedDoorAt(GridPos cell) {
        if (cell == null || !insideZone.test(cell) || !known(cell)) return null;
        BlockPos lower = block(cell);
        BlockState lowerState = level.getBlockState(lower);
        if (!isClosedWoodenDoor(lowerState) || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        BlockPos upper = lower.above();
        BlockState upperState = level.getBlockState(upper);
        if (!isClosedWoodenDoor(upperState) || upperState.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER) {
            return null;
        }
        // One pair means one object: both halves must be the same door block, and the lower half must still be
        // supported the way vanilla requires, so a piece left over from a half-broken door is never a route.
        if (!upperState.is(lowerState.getBlock()) || !lowerState.canSurvive(level, lower)) return null;
        return DoorRoutePlanner.Door.of(cell);
    }

    /** True once a question was asked about a column inside this zone whose chunk is not loaded. */
    public boolean sawUnloaded() { return sawUnloaded; }

    /**
     * The feet, head and floor blocks are loaded, i.e. the world really knows this column.
     *
     * <p>A missing chunk is recorded as undecided instead of being answered like a wall, and nothing here
     * forces a chunk to load: a route question must never generate terrain.</p>
     */
    private boolean known(GridPos cell) {
        BlockPos feet = block(cell);
        if (level.isLoaded(feet) && level.isLoaded(feet.above()) && level.isLoaded(feet.below())) return true;
        sawUnloaded = true;
        return false;
    }

    private static boolean isClosedWoodenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && state.is(BlockTags.WOODEN_DOORS)
                && door.type().canOpenByHand() && !state.getValue(DoorBlock.OPEN);
    }

    /** Real body of the actor standing centred in this cell. */
    private AABB bodyBox(GridPos cell) {
        double half = Math.max(0.05D, actor.getBbWidth()) / 2.0D;
        double height = Math.max(0.05D, actor.getBbHeight());
        double x = cell.x() + 0.5D;
        double z = cell.z() + 0.5D;
        return new AABB(x - half, cell.y(), z - half, x + half, cell.y() + height, z + half);
    }

    /**
     * Does the body fit in this cell, ignoring exactly the door pairs this step assumes gone?
     *
     * <p>Everything else is unchanged from the plain terrain rule: solid support below, no liquid, and no real
     * collision shape may overlap the body. The difference is that a shape counts only when it really
     * intersects the body, which is what lets an open door leaf through while still refusing a body that would
     * touch it.</p>
     */
    private boolean bodyFits(GridPos cell, DoorRoutePlanner.Door ignored, DoorRoutePlanner.Door alsoIgnored) {
        if (cell.y() <= level.getMinBuildHeight() || cell.y() >= level.getMaxBuildHeight() - 1) return false;
        BlockPos feet = block(cell);
        if (!known(cell)) return false;
        if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) return false;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        return clear(bodyBox(cell), ignored, alsoIgnored, null);
    }

    /** No real collision shape may intersect this box, apart from the ignored door pairs. */
    private boolean clear(AABB box, DoorRoutePlanner.Door ignored, DoorRoutePlanner.Door alsoIgnored, BlockPos skipped) {
        int minX = (int) Math.floor(box.minX + EPSILON);
        int minY = (int) Math.floor(box.minY + EPSILON);
        int minZ = (int) Math.floor(box.minZ + EPSILON);
        int maxX = (int) Math.floor(box.maxX - EPSILON);
        int maxY = (int) Math.floor(box.maxY - EPSILON);
        int maxZ = (int) Math.floor(box.maxZ - EPSILON);
        VoxelShape volume = Shapes.create(box);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (skipped != null && skipped.equals(pos)) continue;
                    if (isIgnored(pos, ignored) || isIgnored(pos, alsoIgnored)) continue;
                    if (!level.isLoaded(pos)) {
                        sawUnloaded = true;
                        return false;
                    }
                    VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
                    if (shape.isEmpty()) continue;
                    // The real shape, not its enclosing box: a shape that only fills part of the block must
                    // only stop a body that really reaches the part it fills.
                    if (Shapes.joinIsNotEmpty(shape.move(pos.getX(), pos.getY(), pos.getZ()), volume, BooleanOp.AND)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isIgnored(BlockPos pos, DoorRoutePlanner.Door ignored) {
        if (ignored == null) return false;
        return pos.getX() == ignored.lower().x() && pos.getY() == ignored.lower().y()
                && pos.getZ() == ignored.lower().z()
                || pos.getX() == ignored.upper().x() && pos.getY() == ignored.upper().y()
                && pos.getZ() == ignored.upper().z();
    }

    /** The four neighbours this actor could really step into; closed doors are never among them. */
    public List<GridPos> enterableNeighbours(GridPos from) {
        List<GridPos> result = new ArrayList<>(4);
        for (GridDirection direction : GridDirection.values()) {
            GridPos next = standCell(from.x() + direction.dx, from.z() + direction.dz, from.y());
            if (next == null || closedDoorAt(next) != null || !canEnter(from, next)) continue;
            result.add(next);
        }
        return List.copyOf(result);
    }

    /** True when that cell is the front of this door pair: orthogonally beside its lower half, same layer. */
    public boolean standsInFrontOf(GridPos actorCell, DoorRoutePlanner.Door door) {
        if (actorCell == null || door == null) return false;
        GridPos lower = door.lower();
        return lower.y() == actorCell.y() && lower.horizontalDistance(actorCell) == 1;
    }

    static BlockPos block(GridPos cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
}
