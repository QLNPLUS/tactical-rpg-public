package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Collision-checked flight: never asks for a floor and never invokes vanilla flight AI. */
public final class AerialMovement {
    public static final int MAX_SEARCH_NODES = 8192;
    public static final int MAX_PATH_STEPS = 128;
    private static final double EPSILON = 1.0E-7;

    private AerialMovement() { }

    /** Movement policy is broader than the exact-type tactical attack adapter registry. */
    public static boolean supports(LivingEntity actor) {
        return actor instanceof Phantom || actor instanceof FlyingMob || actor instanceof Vex;
    }

    /** Preserve the actual fractional altitude/position when clear; relocation is strictly bounded. */
    public static Optional<Vec3> findNearestClearAir(ServerLevel level, LivingEntity actor,
            Predicate<BlockPos> occupied, Predicate<BlockPos> zone) {
        Vec3 source = actor.position();
        if (!occupied.test(BlockPos.containing(source)) && zone.test(BlockPos.containing(source))
                && isClearAir(level, actor, source)) return Optional.of(source);
        List<Vec3> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                for (int dy = -8; dy <= 8; dy++) candidates.add(source.add(dx, dy, dz));
            }
        }
        candidates.sort(Comparator.comparingDouble((Vec3 point) -> point.distanceToSqr(source))
                .thenComparingDouble(point -> Math.abs(point.y - source.y))
                .thenComparingDouble(point -> point.y).thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.z));
        for (Vec3 candidate : candidates) {
            BlockPos cell = BlockPos.containing(candidate);
            if (!occupied.test(cell) && zone.test(cell) && isClearAir(level, actor, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Vec3 feet(GridPos cell) { return feet(cell, Vec3.ZERO); }

    /** Offset from the grid centre, captured once on deployment, keeps fractional altitude stable. */
    public static Vec3 feet(GridPos cell, Vec3 offset) {
        return new Vec3(cell.x() + .5 + offset.x, cell.y() + offset.y, cell.z() + .5 + offset.z);
    }

    public static boolean isClearAir(ServerLevel level, LivingEntity actor, Vec3 feet) {
        return clearBox(level, actor, actor.getBoundingBox().move(feet.subtract(actor.position())));
    }

    /**
     * Test the entire swept body, not just feet/head or segment endpoints. The bounding union is
     * deliberately conservative for diagonals: no corner cutting, thin-wall tunnelling or actors
     * intersecting between animation ticks, even for large phantoms and vanilla noPhysics vexes.
     */
    public static boolean isSegmentClear(ServerLevel level, LivingEntity actor, Vec3 from, Vec3 to) {
        AABB start = actor.getBoundingBox().move(from.subtract(actor.position()));
        AABB end = actor.getBoundingBox().move(to.subtract(actor.position()));
        return clearBox(level, actor, start.minmax(end));
    }

    private static boolean clearBox(ServerLevel level, LivingEntity actor, AABB box) {
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box) || !level.noCollision(actor, box)
                || level.containsAnyLiquid(box)) return false;
        if (!level.getEntities(actor, box, other -> !other.isSpectator() && other.isAlive()
                && (other instanceof LivingEntity || other.canBeCollidedWith())).isEmpty()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX + EPSILON), Mth.floor(box.minY + EPSILON),
                Mth.floor(box.minZ + EPSILON), Mth.floor(box.maxX - EPSILON),
                Mth.floor(box.maxY - EPSILON), Mth.floor(box.maxZ - EPSILON))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.WITHER_ROSE)) return false;
        }
        return true;
    }

    public static List<GridPos> neighbors(ServerLevel level, LivingEntity actor, GridPos from,
            Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        return neighbors(level, actor, from, Vec3.ZERO, occupied, zone);
    }

    /** Every 3-D edge has the same grid-step AP charge, including vertical and diagonal edges. */
    public static List<GridPos> neighbors(ServerLevel level, LivingEntity actor, GridPos from, Vec3 offset,
            Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        List<GridPos> result = new ArrayList<>(26);
        Vec3 source = feet(from, offset);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            GridPos next = from.offset(dx, dy, dz);
            if (!occupied.test(next) && zone.test(next)
                    && isSegmentClear(level, actor, source, feet(next, offset))) result.add(next);
        }
        return List.copyOf(result);
    }

    /** Pure bounded BFS. Paths exclude the source; an empty list means no movement/no route. */
    public static List<GridPos> findPath(GridPos from, GridPos to, int budget,
            Function<GridPos, List<GridPos>> neighbors) {
        return reachable(from, budget, neighbors).getOrDefault(to, List.of());
    }

    /** Pure search helper shared with the adapter and deterministic tests. */
    public static Map<GridPos, List<GridPos>> reachable(GridPos from, int budget,
            Function<GridPos, List<GridPos>> neighbors) {
        int limit = Math.min(MAX_PATH_STEPS, Math.max(0, budget));
        Map<GridPos, GridPos> previous = new HashMap<>();
        Map<GridPos, Integer> depth = new HashMap<>();
        ArrayDeque<GridPos> queue = new ArrayDeque<>();
        previous.put(from, from);
        depth.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty() && previous.size() < MAX_SEARCH_NODES) {
            GridPos current = queue.removeFirst();
            int steps = depth.get(current);
            if (steps >= limit) continue;
            for (GridPos next : neighbors.apply(current)) {
                if (!adjacent(current, next) || previous.containsKey(next)) continue;
                previous.put(next, current);
                depth.put(next, steps + 1);
                queue.addLast(next);
                if (previous.size() >= MAX_SEARCH_NODES) break;
            }
        }
        Map<GridPos, List<GridPos>> paths = new HashMap<>();
        for (GridPos target : previous.keySet()) {
            List<GridPos> path = new ArrayList<>();
            for (GridPos node = target; !node.equals(from); node = previous.get(node)) path.add(node);
            Collections.reverse(path);
            paths.put(target, List.copyOf(path));
        }
        return paths;
    }

    /**
     * BFS stops at the nearest reachable goal. If no goal fits the bounded budget, return the
     * closest reachable approach instead. Ordering neighbours can break equal-length goal ties.
     */
    public static List<GridPos> approach(GridPos from, int budget,
            Function<GridPos, List<GridPos>> neighbors, Predicate<GridPos> goal,
            java.util.function.ToIntFunction<GridPos> score) {
        int limit = Math.min(MAX_PATH_STEPS, Math.max(0, budget));
        Map<GridPos, GridPos> previous = new HashMap<>();
        Map<GridPos, Integer> depth = new HashMap<>();
        ArrayDeque<GridPos> queue = new ArrayDeque<>();
        previous.put(from, from);
        depth.put(from, 0);
        queue.add(from);
        GridPos best = from;
        while (!queue.isEmpty()) {
            GridPos current = queue.removeFirst();
            if (score.applyAsInt(current) < score.applyAsInt(best)) best = current;
            if (goal.test(current)) { best = current; break; }
            int steps = depth.get(current);
            if (steps >= limit || previous.size() >= MAX_SEARCH_NODES) continue;
            for (GridPos next : neighbors.apply(current)) {
                if (!adjacent(current, next) || previous.containsKey(next)) continue;
                previous.put(next, current);
                depth.put(next, steps + 1);
                queue.addLast(next);
                if (previous.size() >= MAX_SEARCH_NODES) break;
            }
        }
        List<GridPos> path = new ArrayList<>();
        for (GridPos node = best; !node.equals(from); node = previous.get(node)) path.add(node);
        Collections.reverse(path);
        return List.copyOf(path);
    }

    public static boolean adjacent(GridPos from, GridPos to) {
        int dx = Math.abs(from.x() - to.x()), dy = Math.abs(from.y() - to.y()), dz = Math.abs(from.z() - to.z());
        return dx <= 1 && dy <= 1 && dz <= 1 && (dx + dy + dz) > 0;
    }
}
