package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Water-and-land movement for exactly one vanilla type: the drowned.
 *
 * <p>This encounter's grid refuses liquid cells for every other unit, which is right for a zombie, an iron golem
 * or a player walking a floor. A drowned is a real swimmer, so the water rule is added here, for
 * {@code EntityType.DROWNED} only, instead of loosening {@link LevelGridWorld} or turning the drowned into an
 * aerial actor.</p>
 *
 * <ul>
 *   <li>In water the body keeps its real, fractional position and needs no floor.</li>
 *   <li>On land it uses the exact support height of its cell - the ordinary rule, integer feet included - so a
 *       fractional water altitude can never leak onto a shore cell and make every dry cell illegal.</li>
 *   <li>A step is legal only where the whole body will really travel: a step up is a vertical move first and
 *       then a horizontal one, a step down is the other way round, and the cubes the animation sweeps are the
 *       cubes the rule tested. Nothing is granted by ignoring the lower half of the body.</li>
 *   <li>Every candidate and every swept box is proven fully loaded before a single block or fluid state is read,
 *       so a cell next to an unloaded chunk is refused instead of silently reading the world.</li>
 *   <li>Lava, every other non-water liquid and every hazardous block is refused exactly as for the other
 *       movement policies.</li>
 * </ul>
 *
 * <p>Nothing here starts vanilla navigation, changes a vanilla flag or moves an entity: it answers questions,
 * and the caller applies the answer.</p>
 */
public final class AmphibiousMovement {

    private static final double EPSILON = 1.0E-7;

    /** Bounded, like the aerial policy: a search never turns into a world scan. */
    public static final int MAX_SEARCH_NODES = AerialMovement.MAX_SEARCH_NODES;

    private AmphibiousMovement() { }

    /**
     * Exact vanilla type only.
     *
     * <p>A husk, a zombie or any other water-capable creature must not inherit this by sharing a superclass.</p>
     */
    public static boolean supports(LivingEntity actor) {
        return actor != null && actor.getType() == EntityType.DROWNED;
    }

    /**
     * The feet position this drowned really holds at one cell.
     *
     * <p>In water it is the real position: the cell centre plus the offset captured at deployment, fractional
     * altitude included. On land it is the cell's own support height and centre, because that is the only feet
     * position the dry rule accepts - a water altitude of {@code y + 0.4} must not be carried onto a shore cell,
     * or every shore cell would be an illegal non-integer feet position.</p>
     *
     * <p>The chunk is proven loaded first: an unloaded cell answers with the plain centre and is then refused by
     * {@link #isHoldable}, never read.</p>
     */
    public static Vec3 holdFeet(ServerLevel level, GridPos cell, Vec3 offset) {
        if (cell == null) return Vec3.ZERO;
        BlockPos pos = block(cell);
        if (!loaded(level, pos)) return centre(cell);
        return level.getFluidState(pos).is(FluidTags.WATER) ? AerialMovement.feet(cell, offset) : centre(cell);
    }

    /** The plain dry position of one cell. */
    public static Vec3 centre(GridPos cell) {
        return new Vec3(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
    }

    /**
     * A cell the drowned can hold: either really in water with a clear body, or really standing on support.
     *
     * <p>"In water" uses the feet block, so the surface cell - feet in water, body partly in air - counts:
     * without it a drowned could never leave a lake. Every load, bound, body and hazard requirement is the same
     * in both media, and every one of them is decided before the world is read at a position that may not be
     * loaded.</p>
     */
    public static boolean isHoldable(ServerLevel level, LivingEntity actor, Vec3 feet) {
        if (level == null || actor == null || feet == null) return false;
        BlockPos cell = BlockPos.containing(feet);
        if (!commonChecksPass(level, actor, feet, cell)) return false;
        FluidState fluid = level.getFluidState(cell);
        if (!fluid.isEmpty()) return fluid.is(FluidTags.WATER);
        return BattleDeployment.isStandable(level, actor, feet);
    }

    private static boolean commonChecksPass(ServerLevel level, LivingEntity actor, Vec3 feet, BlockPos cell) {
        if (feet.y < level.getMinBuildHeight()) return false;
        AABB box = bodyAt(actor, feet);
        if (!insideWorld(level, box)) return false;
        // Every cube this position needs is proven loaded before anything is queried: the whole body, the cell
        // itself and the support column below the feet.
        if (!loaded(level, box) || !loaded(level, cell) || !loaded(level, cell.below())) return false;
        if (!level.noCollision(actor, box)) return false;
        if (overlapsBody(level, actor, box)) return false;
        return !hazardous(level, box);
    }

    public static AABB bodyAt(LivingEntity actor, Vec3 feet) {
        return actor.getBoundingBox().move(feet.subtract(actor.position()));
    }

    private static boolean insideWorld(ServerLevel level, AABB box) {
        return box.minY >= level.getMinBuildHeight() && box.maxY <= level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(box);
    }

    /** True when every chunk the box touches is already loaded; never loads anything. */
    public static boolean loaded(ServerLevel level, AABB box) {
        if (level == null || box == null) return false;
        int minChunkX = Mth.floor(box.minX + EPSILON) >> 4;
        int maxChunkX = Mth.floor(box.maxX - EPSILON) >> 4;
        int minChunkZ = Mth.floor(box.minZ + EPSILON) >> 4;
        int maxChunkZ = Mth.floor(box.maxZ - EPSILON) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    public static boolean loaded(ServerLevel level, BlockPos pos) {
        return level != null && level.hasChunkAt(pos);
    }

    public static boolean overlapsBody(ServerLevel level, LivingEntity actor, AABB box) {
        return !level.getEntities(actor, box, other -> !other.isSpectator() && other.isAlive()
                && (other instanceof LivingEntity || other.canBeCollidedWith())).isEmpty();
    }

    public static boolean hazardous(ServerLevel level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX + EPSILON), Mth.floor(box.minY + EPSILON),
                Mth.floor(box.minZ + EPSILON), Mth.floor(box.maxX - EPSILON),
                Mth.floor(box.maxY - EPSILON), Mth.floor(box.maxZ - EPSILON))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.WITHER_ROSE)) return true;
        }
        return false;
    }

    /**
     * The intermediate feet position a step of this shape really travels through.
     *
     * <p>A step up rises first and then moves across; a step down moves across first and then drops. That is the
     * route the animation takes, and having the rule ask about the same route is what makes "the shore is
     * reachable" a statement about the real body instead of about a straight line through the bank.</p>
     */
    public static Vec3 via(Vec3 from, Vec3 to) {
        if (to.y > from.y + EPSILON) return new Vec3(from.x, to.y, from.z);
        if (to.y < from.y - EPSILON) return new Vec3(to.x, from.y, to.z);
        return null;
    }

    /** One body position is clear: inside the world, fully loaded, no block, no hazard, nobody in the way. */
    public static boolean clearAt(ServerLevel level, LivingEntity actor, Vec3 feet) {
        if (level == null || actor == null || feet == null) return false;
        AABB box = bodyAt(actor, feet);
        if (!insideWorld(level, box) || !loaded(level, box)) return false;
        if (!loaded(level, BlockPos.containing(feet))) return false;
        if (!level.noCollision(actor, box)) return false;
        if (overlapsBody(level, actor, box)) return false;
        return !hazardous(level, box);
    }

    /** The whole body really sweeps this leg: true only where nothing solid or hazardous is in the union. */
    public static boolean sweepClear(ServerLevel level, LivingEntity actor, Vec3 from, Vec3 to) {
        if (level == null || actor == null || from == null || to == null) return false;
        AABB box = bodyAt(actor, from).minmax(bodyAt(actor, to));
        if (!insideWorld(level, box) || !loaded(level, box)) return false;
        if (!level.noCollision(actor, box)) return false;
        if (overlapsBody(level, actor, box)) return false;
        return !hazardous(level, box);
    }

    /**
     * Whether the real body may travel this one edge.
     *
     * <p>Both endpoint cells must be legal, both legs of the real route must sweep clear, and every fluid the
     * route touches must be water. The destination is re-checked here rather than trusted from the plan: a drained
     * cell, a closed shore or a body that stepped in refuses the edge outright.</p>
     */
    public static boolean canStep(ServerLevel level, LivingEntity actor, Vec3 fromFeet, Vec3 toFeet) {
        if (level == null || actor == null || fromFeet == null || toFeet == null) return false;
        if (!clearAt(level, actor, fromFeet) || !clearAt(level, actor, toFeet)) return false;
        if (!isHoldable(level, actor, toFeet)) return false;
        if (!waterOnly(level, fromFeet) || !waterOnly(level, toFeet)) return false;
        Vec3 middle = via(fromFeet, toFeet);
        if (middle == null) return sweepClear(level, actor, fromFeet, toFeet);
        return clearAt(level, actor, middle)
                && sweepClear(level, actor, fromFeet, middle)
                && sweepClear(level, actor, middle, toFeet);
    }

    /** A liquid outside the water tag - lava above all - makes the whole position illegal. */
    private static boolean waterOnly(ServerLevel level, Vec3 feet) {
        BlockPos pos = BlockPos.containing(feet);
        if (!loaded(level, pos)) return false;
        FluidState fluid = level.getFluidState(pos);
        return fluid.isEmpty() || fluid.is(FluidTags.WATER);
    }

    /**
     * The cells this drowned may step to, in one bounded pass.
     *
     * <p>Water-touching edges are free in all 26 directions; two dry cells keep the ordinary orthogonal one-step
     * rule, so land movement stays exactly what every other ground actor has. The load checks come before any
     * fluid read, including the source cell's own.</p>
     */
    public static List<GridPos> neighbors(ServerLevel level, LivingEntity actor, GridPos from, Vec3 offset,
            Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        if (level == null || actor == null || from == null) return List.of();
        if (!loaded(level, block(from))) return List.of();
        List<GridPos> result = new ArrayList<>(26);
        Vec3 source = holdFeet(level, from, offset);
        boolean sourceWet = isWet(level, from);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            GridPos next = from.offset(dx, dy, dz);
            if (occupied.test(next)) continue;
            if (!zone.test(next)) continue;
            // Nothing about the candidate is read before its own cell is proven loaded.
            if (!loaded(level, block(next))) continue;
            boolean wetEdge = sourceWet || isWet(level, next);
            if (!wetEdge && !GridMovementRules.canStep(from, next)) continue;
            Vec3 target = holdFeet(level, next, offset);
            if (!isHoldable(level, actor, target)) continue;
            if (!canStep(level, actor, source, target)) continue;
            result.add(next);
        }
        return List.copyOf(result);
    }

    private static boolean isWet(ServerLevel level, GridPos cell) {
        return level.getFluidState(block(cell)).is(FluidTags.WATER);
    }

    private static BlockPos block(GridPos cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    /**
     * Nearest position this drowned can really hold, or empty when nothing inside the bounded search does.
     *
     * <p>The actor's own position is tried first and returned unchanged - fractional coordinates included - so a
     * drowned that is already in clear water is never relocated onto a shoreline. Only when it cannot hold where
     * it is does a bounded neighbourhood get searched, nearest first, and only over cells that are already
     * loaded.</p>
     */
    public static Optional<Vec3> findNearestHold(ServerLevel level, LivingEntity actor,
            Predicate<BlockPos> occupied, Predicate<BlockPos> zone) {
        if (level == null || actor == null) return Optional.empty();
        Vec3 source = actor.position();
        BlockPos current = BlockPos.containing(source);
        if (!occupied.test(current) && zone.test(current) && isHoldable(level, actor, source)) {
            return Optional.of(source);
        }
        List<Vec3> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                for (int dy = -8; dy <= 8; dy++) {
                    candidates.add(new Vec3(Mth.floor(source.x) + dx + .5D, Mth.floor(source.y) + dy,
                            Mth.floor(source.z) + dz + .5D));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Vec3 point) -> point.distanceToSqr(source))
                .thenComparingDouble(point -> Math.abs(point.y - source.y))
                .thenComparingDouble(point -> point.y).thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.z));
        for (Vec3 candidate : candidates) {
            BlockPos cell = BlockPos.containing(candidate);
            if (occupied.test(cell)) continue;
            if (!zone.test(cell)) continue;
            if (!loaded(level, cell)) continue;
            if (isHoldable(level, actor, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }
}
