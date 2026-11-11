package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Bounded deployment query. This class never mutates AI, position or persistent mob state. */
public final class BattleDeployment {
    private static final int HORIZONTAL_RADIUS = 4;
    private static final int VERTICAL_RADIUS = 8;
    private static final double EPSILON = 1.0E-7;

    private BattleDeployment() {}

    /**
     * Returns a collision-checked physical feet position, or empty (caller must skip the mob).
     * Predicates receive the candidate feet cell. Do not recenter the returned position:
     * preserving fractional X/Z can be both nearer and necessary for a wide entity to fit.
     */
    public static Optional<Vec3> findNearestStandable(ServerLevel level, LivingEntity entity,
            Predicate<BlockPos> occupied, Predicate<BlockPos> zone) {
        return findNearestStandable(level, entity, occupied, zone, null);
    }

    /**
     * The same bounded search with one extra gate asked at the real candidate feet before any block is read.
     *
     * <p>It exists for the bodies whose footprint is not one cell: a candidate whose own chunk - or any chunk
     * its whole body would touch, or the zone it would be committed into - is not already loaded must be
     * refused, not silently read as free air, and nothing here force-loads a column to make a candidate work.
     * The gate answers about a position only, so it can never move, claim or mutate the entity.</p>
     */
    public static Optional<Vec3> findNearestStandable(ServerLevel level, LivingEntity entity,
            Predicate<BlockPos> occupied, Predicate<BlockPos> zone, Predicate<Vec3> ready) {
        Vec3 source = entity.position();
        List<Vec3> candidates = new ArrayList<>();
        candidates.add(source);
        int sourceY = Mth.floor(source.y);
        // The physical source column must be considered even while it intersects a block.
        for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
            candidates.add(new Vec3(source.x, sourceY + dy, source.z));
        }
        for (int x = Mth.floor(source.x) - HORIZONTAL_RADIUS; x <= Mth.floor(source.x) + HORIZONTAL_RADIUS; x++) {
            for (int z = Mth.floor(source.z) - HORIZONTAL_RADIUS; z <= Mth.floor(source.z) + HORIZONTAL_RADIUS; z++) {
                double dx = x + .5 - source.x, dz = z + .5 - source.z;
                if (dx * dx + dz * dz > HORIZONTAL_RADIUS * HORIZONTAL_RADIUS) continue;
                for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
                    candidates.add(new Vec3(x + .5, sourceY + dy, z + .5));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Vec3 point) -> point.distanceToSqr(source))
                .thenComparingDouble(point -> point.y).thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.z));
        for (Vec3 candidate : candidates) {
            BlockPos feet = BlockPos.containing(candidate);
            if (occupied.test(feet) || !zone.test(feet)) continue;
            if (ready != null && !ready.test(candidate)) continue;
            if (isStandable(level, entity, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Uses the entity's actual current bounding box, including pose and width, without moving it. */
    public static boolean isStandable(ServerLevel level, LivingEntity entity, Vec3 feet) {
        AABB box = entity.getBoundingBox().move(feet.subtract(entity.position()));
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box) || !level.noCollision(entity, box)
                || level.containsAnyLiquid(box)) return false;
        // Vanilla movement collisions omit ordinary pushable living actors. Deployment
        // must not stack on a bystander either, even when the occupancy predicate only
        // knows about participants; retain native noCollision for boats/border/blocks.
        if (!level.getEntities(entity, box, other -> !other.isSpectator() && other.isAlive()
                && (other instanceof LivingEntity || other.canBeCollidedWith())).isEmpty()) return false;
        // Require an actual solid upper face at exactly feet height, not a guessed heightmap
        // surface or an entity collision. Checking the footprint also supports wide mobs.
        if (Math.abs(feet.y - Math.rint(feet.y)) > EPSILON) return false;
        int supportY = Mth.floor(feet.y - EPSILON);
        for (int x = Mth.floor(box.minX + EPSILON); x <= Mth.floor(box.maxX - EPSILON); x++) {
            for (int z = Mth.floor(box.minZ + EPSILON); z <= Mth.floor(box.maxZ - EPSILON); z++) {
                BlockPos support = new BlockPos(x, supportY, z);
                BlockState state = level.getBlockState(support);
                if (!state.isFaceSturdy(level, support, Direction.UP) || hazardous(state)) return false;
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX + EPSILON), Mth.floor(box.minY + EPSILON),
                Mth.floor(box.minZ + EPSILON), Mth.floor(box.maxX - EPSILON),
                Mth.floor(box.maxY - EPSILON), Mth.floor(box.maxZ - EPSILON))) {
            if (hazardous(level.getBlockState(pos))) return false;
        }
        return true;
    }

    private static boolean hazardous(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }

    // ------------------------------------------------------------------ outside-zone placement

    /** Which survival rule an evicted mob needs at its new feet position. */
    public enum Stance {
        /** Needs a solid, non-hazardous support face at exactly feet height. */
        GROUND,
        /** Needs clear air; no support face is required. */
        FLYING,
        /** Needs water at feet and head; never placed on dry land. */
        AQUATIC
    }

    /** Ring search bounds: close enough to still leave the zone, far enough not to search the world. */
    private static final int OUTSIDE_BUFFER = 3;
    private static final int OUTSIDE_MAX_EXTRA = 24;
    private static final int OUTSIDE_ANGLES = 16;
    private static final int OUTSIDE_VERTICAL = 6;

    /**
     * Decides a mob's survival rule from what it actually is, not from a breathing flag:
     * flyers get air, water creatures keep water, and everything else must stand on ground.
     *
     * <p>{@code canBreatheUnderwater} is deliberately not the aquatic test: the vanilla
     * {@code can_breathe_under_water} tag also contains the undead (zombies do not drown), and
     * sending a zombie to a pond instead of a lawn would be a bug, not a fix.</p>
     */
    public static Stance stanceOf(LivingEntity entity) {
        if (entity == null) return Stance.GROUND;
        // Same flight policy the encounter already uses for admitted units; not a second one.
        if (AerialMovement.supports(entity)) return Stance.FLYING;
        PathNavigation navigation = entity instanceof Mob mob ? mob.getNavigation() : null;
        if (navigation instanceof FlyingPathNavigation) return Stance.FLYING;
        if (entity instanceof WaterAnimal || entity instanceof Guardian) return Stance.AQUATIC;
        return Stance.GROUND;
    }

    /**
     * Nearest feet position whose <em>whole body box</em> clears every battlefield the caller
     * forbids, or empty when no such place exists inside the search budget.
     *
     * <p>The rings start {@code OUTSIDE_BUFFER} blocks beyond the zone radius, so an evicted unit
     * never lands on the boundary where a single push could bring it back. Each candidate is
     * checked against the mob's own survival rule ({@link Stance}) and against {@code forbidden},
     * which receives the moved bounding box - not a single cell. A wide mob whose feet cell sits
     * outside the zone can still have its shoulders inside it, so the body is the unit under test.
     * The caller supplies every live encounter's volume, so this class never has to know about
     * them. Nothing is mutated here: the caller applies the returned position.</p>
     */
    public static Optional<Vec3> findOutsideStandable(ServerLevel level, LivingEntity entity,
            GridPos center, int zoneRadius, Predicate<AABB> forbidden) {
        if (level == null || entity == null || center == null) return Optional.empty();
        Stance stance = stanceOf(entity);
        int sourceY = Mth.floor(entity.getY());
        int start = Math.max(1, zoneRadius) + OUTSIDE_BUFFER;
        int end = start + OUTSIDE_MAX_EXTRA;
        for (int radius = start; radius <= end; radius++) {
            for (int index = 0; index < OUTSIDE_ANGLES; index++) {
                double angle = (Math.PI * 2.0D) * index / OUTSIDE_ANGLES;
                int x = center.x() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.z() + (int) Math.round(Math.sin(angle) * radius);
                // Nearest first: the mob's own layer, then progressively larger height deltas.
                for (int delta = 0; delta <= OUTSIDE_VERTICAL; delta++) {
                    for (int sign : delta == 0 ? new int[]{0} : new int[]{1, -1}) {
                        int y = sourceY + delta * sign;
                        Vec3 feet = new Vec3(x + 0.5D, y, z + 0.5D);
                        if (forbidden != null && forbidden.test(boundsAt(entity, feet))) continue;
                        if (!standable(level, entity, feet, stance)) continue;
                        return Optional.of(feet);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The entity's own bounding box translated to a candidate feet position. */
    public static AABB boundsAt(LivingEntity entity, Vec3 feet) {
        return entity.getBoundingBox().move(feet.subtract(entity.position()));
    }

    /**
     * Conservative body-versus-battlefield test: touching a face already counts.
     *
     * <p>Uses the same inclusive rule as {@link com.tactical.core.zone.ZoneSeparation} so a body
     * that merely grazes a battlefield's box is already a conflict. A cell-only test would let a
     * wide mob stand next to the zone with its shoulders inside it.</p>
     */
    public static boolean overlapsZone(AABB box, com.tactical.core.zone.BattleZone.Bounds bounds) {
        if (box == null || bounds == null) return false;
        return box.minX <= bounds.maxX() && bounds.minX() <= box.maxX
                && box.minY <= bounds.maxY() && bounds.minY() <= box.maxY
                && box.minZ <= bounds.maxZ() && bounds.minZ() <= box.maxZ;
    }

    /** Stance-specific survival check; never recenters or moves the entity. */
    public static boolean standable(ServerLevel level, LivingEntity entity, Vec3 feet, Stance stance) {
        if (level == null || entity == null || feet == null) return false;
        if (stance == Stance.FLYING) return AerialMovement.isClearAir(level, entity, feet);
        AABB box = entity.getBoundingBox().move(feet.subtract(entity.position()));
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box)) return false;
        if (!level.getEntities(entity, box, other -> !other.isSpectator() && other.isAlive()
                && (other instanceof LivingEntity || other.canBeCollidedWith())).isEmpty()) return false;
        if (stance == Stance.AQUATIC) {
            // Water at the feet and at the head: an evicted swimmer must never dry out on land.
            if (!level.getFluidState(BlockPos.containing(feet)).is(FluidTags.WATER)
                    || !level.getFluidState(BlockPos.containing(feet.add(0, 1, 0))).is(FluidTags.WATER)) return false;
            return level.noCollision(entity, box);
        }
        return isStandable(level, entity, feet);
    }
}
