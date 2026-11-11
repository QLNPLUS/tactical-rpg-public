package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Geometry of a real body, independent of which creature is using it.
 *
 * <p>Three different questions live here and they are never interchangeable: where a body
 * <em>is</em> when its feet are at a point, which boxes one animated displacement can touch, and how far the
 * native melee reach of that body really is. A grid cell answers none of them - a 2.08 wide body occupies
 * more than one cell and a 0.52 one less than one - so every caller asks the body itself.</p>
 *
 * <p>Nothing here mutates an entity or charges anything: every method answers a question about the live world
 * and the supplied body, and the caller decides what to do with the answer.</p>
 */
public final class BodyGeometry {

    /**
     * Vanilla's own horizontal melee widening for a mob.
     *
     * <p>It is computed here instead of being guessed per creature: {@code Mob.DEFAULT_ATTACK_REACH} is
     * {@code sqrt(2.04) - 0.6} in this target version and the vertical axis is deliberately not widened.
     * A second constant with a different value would silently give a body a reach vanilla never had.</p>
     */
    public static final double NATIVE_ATTACK_REACH = Math.sqrt(2.04F) - 0.6D;

    private static final double EPSILON = 1.0E-7D;

    private BodyGeometry() { }

    /** The box this exact body occupies when its feet are at {@code feet}. */
    public static AABB bodyAt(LivingEntity body, Vec3 feet) {
        if (body == null || feet == null) return null;
        return body.getBoundingBox().move(feet.subtract(body.position()));
    }

    /** Every box one animated displacement from {@code from} to {@code to} can touch. */
    public static AABB swept(LivingEntity body, Vec3 from, Vec3 to) {
        AABB start = bodyAt(body, from);
        AABB end = bodyAt(body, to);
        if (start == null || end == null) return null;
        return start.minmax(end);
    }

    /** The native attack box at those feet: the body box widened horizontally by vanilla's own reach. */
    public static AABB attackBoxAt(LivingEntity body, Vec3 feet) {
        AABB box = bodyAt(body, feet);
        return box == null ? null : box.inflate(NATIVE_ATTACK_REACH, 0.0D, NATIVE_ATTACK_REACH);
    }

    /** Whether every cell the real body touches at those feet belongs to the battlefield. */
    public static boolean bodyInsideZone(LivingEntity body, Vec3 feet, Predicate<GridPos> zone) {
        if (zone == null) return true;
        AABB box = bodyAt(body, feet);
        return box != null && boxInsideZone(box, zone);
    }

    /** Whether every cell the swept body can touch belongs to the battlefield. */
    public static boolean sweptInsideZone(LivingEntity body, Vec3 from, Vec3 to, Predicate<GridPos> zone) {
        if (zone == null) return true;
        AABB box = swept(body, from, to);
        return box != null && boxInsideZone(box, zone);
    }

    /** Every cube of that box inside the battlefield, inclusive on both faces. */
    public static boolean boxInsideZone(AABB box, Predicate<GridPos> zone) {
        if (box == null) return false;
        if (zone == null) return true;
        for (int x = floor(box.minX); x <= floor(box.maxX); x++) {
            for (int y = floor(box.minY); y <= floor(box.maxY); y++) {
                for (int z = floor(box.minZ); z <= floor(box.maxZ); z++) {
                    if (!zone.test(new GridPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    /**
     * One animated leg proved as a whole body sweep.
     *
     * <p>A leg is not its two endpoints: a body passing a fence, a ledge or a pool must not be allowed to be
     * on both sides of it between two samples. Every chunk the sweep touches has to be loaded first - a read
     * of an unloaded column would answer "nothing here" and turn into a free pass through the world - and
     * then the same sweep is asked for solid blocks, any liquid at all, every hazard, another real body and
     * the battlefield boundary.</p>
     */
    public static boolean sliceClear(ServerLevel level, LivingEntity body, Vec3 from, Vec3 to,
                                    Predicate<GridPos> zone) {
        if (level == null || body == null || from == null || to == null) return false;
        AABB box = swept(body, from, to);
        if (box == null) return false;
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        if (!loaded(level, box)) return false;
        if (!level.noCollision(body, box)) return false;
        if (AmphibiousMovement.overlapsBody(level, body, box)) return false;
        if (hazardous(level, box)) return false;
        if (wetted(level, box)) return false;
        return boxInsideZone(box, zone);
    }

    /** Every column the box touches is really loaded; the check is asked before any block is read. */
    public static boolean loaded(ServerLevel level, AABB box) {
        return AmphibiousMovement.loaded(level, box);
    }

    /** Every block cell of that box loaded, used for a support face that may cross a chunk border. */
    public static boolean loaded(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (level == null) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) return false;
            }
        }
        return true;
    }

    /** The same hazard set the deployment and fall rules already refuse. */
    public static boolean hazardous(ServerLevel level, AABB box) {
        return AmphibiousMovement.hazardous(level, box);
    }

    /** Any liquid at all inside the box, which a non-swimming body may never occupy. */
    public static boolean wetted(ServerLevel level, AABB box) {
        if (level == null || box == null) return true;
        for (BlockPos pos : BlockPos.betweenClosed(floor(box.minX), floor(box.minY), floor(box.minZ),
                floor(box.maxX), floor(box.maxY), floor(box.maxZ))) {
            if (!level.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    /** Any hazard block inside the box, listed here so a support face and a body are judged alike. */
    public static boolean hazardousBlock(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }

    /** Floor with the tolerance that keeps a point exactly on a boundary in the cell it started in. */
    public static int floor(double value) {
        return net.minecraft.util.Mth.floor(value + EPSILON);
    }

    /** Ceiling with the same tolerance. */
    public static int ceil(double value) {
        return net.minecraft.util.Mth.ceil(value - EPSILON);
    }
}
