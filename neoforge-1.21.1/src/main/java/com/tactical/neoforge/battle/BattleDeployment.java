package com.tactical.neoforge.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
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
}
