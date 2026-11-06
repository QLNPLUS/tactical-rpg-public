package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.core.zone.BattleZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one landing gate every enderman relocation goes through: active teleport, hurt reaction and the
 * encounter's own water clock.
 *
 * <p>Two separate questions, deliberately in this order. {@link #resolve} answers "where would vanilla's own
 * {@code teleport(DDD)} really put this body" - the same support-column drop vanilla performs - without any
 * strategy behind it. {@link #legal} answers "may this encounter allow that point": loaded chunks before a
 * single block is read, a real solid dry support, three blocks of body room for this entity's own 2.9 high
 * box, no liquid, no hazard, nothing else standing there, and the whole body inside this encounter's zone and
 * the world border.</p>
 *
 * <p>The gate runs where vanilla finishes re-pointing the target and before it moves the body: the
 * {@code EnderEntityEvent.EnderEntity} listeners may cancel or move the destination, so a point blessed before
 * the event says nothing about the point that will really be used.</p>
 */
public final class EndermanLanding {

    private static final double EPSILON = 1.0E-7D;
    /** A vanilla support column never needs more than this many steps; the guard only bounds a broken world. */
    private static final int MAX_SUPPORT_DROP = 512;

    private EndermanLanding() { }

    /** One resolved landing: the cell the body occupies and the real feet point inside it. */
    public record Landing(GridPos cell, Vec3 feet) { }

    /** An enderman is the one vanilla type this ruleset relocates; never a superclass check. */
    public static boolean supports(Mob mob) {
        return mob != null && mob.getType() == EntityType.ENDERMAN;
    }

    /** The body box this entity would really have at that feet point, from its own dimensions. */
    public static AABB bodyAt(EnderMan enderman, Vec3 feet) {
        return AmphibiousMovement.bodyAt(enderman, feet);
    }

    /**
     * Where vanilla's {@code teleport(DDD)} would really land this body at that requested point.
     *
     * <p>Exactly vanilla's two steps, in vanilla's order: the requested column is scanned down to the first
     * block that blocks motion and refused when that block is water, then the feet drop to the first count that
     * really has a support underneath. Nothing is decided about legality here.</p>
     *
     * @return the resolved landing, or null when vanilla would refuse the point outright
     */
    public static Landing resolve(ServerLevel level, EnderMan enderman, double x, double y, double z) {
        if (level == null || enderman == null) return null;
        BlockPos probe = BlockPos.containing(x, y, z);
        // Chunk gate before any block read: the whole body column and the requested column itself.
        if (!AmphibiousMovement.loaded(level, bodyAt(enderman, new Vec3(x, y, z)))
                || !AmphibiousMovement.loaded(level, probe)) return null;
        // Vanilla scans the requested column down to the first block that blocks motion and refuses water there.
        BlockPos.MutableBlockPos cursor = probe.mutable();
        int guard = 0;
        while (cursor.getY() > level.getMinBuildHeight() && guard++ <= MAX_SUPPORT_DROP
                && !level.getBlockState(cursor).blocksMotion()) {
            cursor.move(Direction.DOWN);
        }
        BlockState motion = level.getBlockState(cursor);
        if (!motion.blocksMotion() || motion.getFluidState().is(FluidTags.WATER)) return null;
        // Vanilla's own randomTeleport then drops the feet to the first support below the requested height.
        double feetY = y;
        BlockPos column = BlockPos.containing(x, y, z);
        guard = 0;
        while (column.getY() > level.getMinBuildHeight() && guard++ <= MAX_SUPPORT_DROP) {
            if (level.getBlockState(column.below()).blocksMotion()) break;
            feetY -= 1.0D;
            column = column.below();
        }
        if (!level.getBlockState(column.below()).blocksMotion()) return null;
        Vec3 feet = new Vec3(x, feetY, z);
        return new Landing(new GridPos(column.getX(), Mth.floor(feetY + EPSILON), column.getZ()), feet);
    }

    /**
     * Whether this encounter may allow the resolved landing.
     *
     * <p>Every loaded-chunk question is answered before the first {@code getBlockState}/
     * {@code getFluidState}/collision query, over the whole body box rather than two opposite corners: an
     * enderman's box straddles chunk borders in both axes at once.</p>
     */
    public static boolean legal(ServerLevel level, EnderMan enderman, Landing landing,
                                Predicate<GridPos> occupied, BattleZone zone) {
        if (level == null || enderman == null || landing == null) return false;
        Vec3 feet = landing.feet();
        if (feet.y < level.getMinBuildHeight()) return false;
        AABB box = bodyAt(enderman, feet);
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!AmphibiousMovement.loaded(level, box)) return false;
        GridPos cell = landing.cell();
        BlockPos support = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        if (!AmphibiousMovement.loaded(level, support)) return false;
        // The three body blocks the 2.9 high box really needs, each proven loaded first.
        for (int step = 0; step < 3; step++) {
            if (!AmphibiousMovement.loaded(level, new BlockPos(cell.x(), cell.y() + step, cell.z()))) return false;
        }
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        BlockState below = level.getBlockState(support);
        if (!below.blocksMotion() || below.getFluidState().is(FluidTags.WATER)) return false;
        if (!realSupport(level, enderman, feet, support)) return false;
        // Three blocks of body room: no solid inside the whole box, no liquid and no damage block.
        if (!level.noCollision(enderman, box)) return false;
        if (level.containsAnyLiquid(box)) return false;
        if (AmphibiousMovement.hazardous(level, box)) return false;
        // Nothing else may already stand there, bystanders and other encounters included.
        if (occupied != null && occupied.test(cell)) return false;
        if (!BattleDeployment.isStandable(level, enderman, feet)) return false;
        return zone == null || insideZone(zone, box);
    }

    /**
     * Whether that feet point is really the top face of solid ground under the whole footprint.
     *
     * <p>{@code blocksMotion} is not enough: a bottom slab blocks motion but its real surface is half a block
     * lower, so a body "standing" at the block height would hang in the air until gravity moved it - and the
     * encounter's cell would then describe a place the body no longer is. Vanilla's teleport only scans for a
     * motion-blocking block, so this is the encounter's own stricter answer: integer feet, and a collision top
     * that is exactly that height across every column the body covers.</p>
     */
    private static boolean realSupport(ServerLevel level, EnderMan enderman, Vec3 feet, BlockPos support) {
        if (Math.abs(feet.y - Math.rint(feet.y)) > EPSILON) return false;
        AABB box = bodyAt(enderman, feet);
        double top = support.getY() + level.getBlockState(support)
                .getCollisionShape(level, support).max(Direction.Axis.Y);
        if (Math.abs(top - feet.y) > EPSILON) return false;
        int supportY = Mth.floor(feet.y - EPSILON);
        for (int x = Mth.floor(box.minX + EPSILON); x <= Mth.floor(box.maxX - EPSILON); x++) {
            for (int z = Mth.floor(box.minZ + EPSILON); z <= Mth.floor(box.maxZ - EPSILON); z++) {
                BlockPos column = new BlockPos(x, supportY, z);
                BlockState state = level.getBlockState(column);
                double columnTop = column.getY() + state.getCollisionShape(level, column).max(Direction.Axis.Y);
                if (!state.isFaceSturdy(level, column, Direction.UP) || Math.abs(columnTop - feet.y) > EPSILON) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Every column and body block the box really covers has to be inside this encounter's zone. */
    private static boolean insideZone(BattleZone zone, AABB box) {
        int minX = Mth.floor(box.minX + EPSILON);
        int maxX = Mth.floor(box.maxX - EPSILON);
        int minZ = Mth.floor(box.minZ + EPSILON);
        int maxZ = Mth.floor(box.maxZ - EPSILON);
        int minY = Mth.floor(box.minY + EPSILON);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int step = 0; step < 3; step++) {
                    if (!zone.contains(new GridPos(x, minY + step, z))) return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether that feet point is still exposed to what an enderman is sensitive to.
     *
     * <p>The same shape vanilla's own {@code isInWaterRainOrBubble} answers with: water or a bubble column at
     * the feet or in the block above them, and rain reaching either the feet block or the block the body's head
     * really ends in. A roof over the head therefore shelters the unit even when the feet block still sees the
     * sky, which is what "dry, sheltered" has to mean for this body.</p>
     */
    public static boolean exposed(ServerLevel level, EnderMan enderman, Vec3 feet) {
        if (level == null || feet == null) return false;
        BlockPos pos = BlockPos.containing(feet);
        if (!AmphibiousMovement.loaded(level, pos)) return true;
        if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
        if (level.getFluidState(pos.above()).is(FluidTags.WATER)) return true;
        if (level.isRainingAt(pos)) return true;
        if (enderman == null) return false;
        AABB head = bodyAt(enderman, feet);
        BlockPos headPos = BlockPos.containing(feet.x, head.maxY, feet.z);
        return level.isRainingAt(headPos);
    }

    /**
     * The candidate cells this ruleset is willing to examine, in its own preference order.
     *
     * <p>Ordered by the answer each relocation is looking for: a unit leaving water or rain tries the nearest
     * cells first because a short hop out of the puddle is what it wants, while a unit that is simply too far
     * from its opponent prefers the cells that really close that distance. At most {@code examined} cells are
     * ever validated, so one action can never turn into an unbounded scan.</p>
     */
    public static List<GridPos> candidates(GridPos actorCell, GridPos targetCell, boolean escape,
                                           double range, int examined) {
        if (actorCell == null || examined <= 0) return List.of();
        int radius = (int) Math.floor(range);
        List<GridPos> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance <= EPSILON || distance > range) continue;
                    cells.add(new GridPos(actorCell.x() + dx, actorCell.y() + dy, actorCell.z() + dz));
                }
            }
        }
        Comparator<GridPos> preference = escape || targetCell == null
                ? Comparator.comparingDouble((GridPos cell) -> distance(cell, actorCell))
                        .thenComparingInt(GridPos::y).thenComparingInt(GridPos::x).thenComparingInt(GridPos::z)
                : Comparator.comparingDouble((GridPos cell) -> distance(cell, targetCell))
                        .thenComparingInt(GridPos::y).thenComparingInt(GridPos::x).thenComparingInt(GridPos::z);
        cells.sort(preference);
        return cells.size() <= examined ? List.copyOf(cells) : List.copyOf(cells.subList(0, examined));
    }

    private static double distance(GridPos from, GridPos to) {
        double dx = (double) from.x() - to.x();
        double dy = (double) from.y() - to.y();
        double dz = (double) from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
