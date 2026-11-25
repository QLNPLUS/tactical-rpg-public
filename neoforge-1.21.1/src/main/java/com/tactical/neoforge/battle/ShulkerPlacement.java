package com.tactical.neoforge.battle;

import com.tactical.core.combat.MotionRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where a shulker may be admitted, at all.
 *
 * <p>Every ordinary admission path of this encounter asks "the nearest standable ground cell". That question is
 * wrong for this one type: a shell glued to a wall or to the ceiling of a pit has no floor to stand on, and
 * placing it on an ordinary ground row would silently rewrite the pose it was fighting from - and, worse, would
 * refuse a body that is already in a perfectly legal place.</p>
 *
 * <p>So the search below is the same bounded search the relocation uses, expressed in six-face nodes instead of
 * standable rows:</p>
 * <ol>
 *   <li>if the pose the body really holds is already legal and inside the battlefield that would be committed,
 *       that exact point is the answer;</li>
 *   <li>otherwise the nearest legal node inside the reach of its own cell wins, nearest ring first.</li>
 * </ol>
 *
 * <p>Every candidate is proved before it is read: the column must be loaded, the node must pass the full native
 * attachment gate (block, support face, full-open collision, world border) and the whole full-open box must be
 * inside the battlefield. Nothing is force-loaded and nothing is moved.</p>
 */
public final class ShulkerPlacement {

    private ShulkerPlacement() {}

    /** The admitted feet for this shell, or empty when it has no legal pose near where it stands. */
    public static Optional<Vec3> find(ServerLevel level, Shulker shulker,
                                      Predicate<GridPos> occupied,
                                      Predicate<GridPos> allowed,
                                      Predicate<GridPos> wouldBeZone) {
        if (level == null || shulker == null || !shulker.isAlive() || shulker.isRemoved()) return Optional.empty();
        Predicate<GridPos> inZone = wouldBeZone;
        // 1. Keep the pose it really holds: a wall or ceiling body is not re-placed onto a floor.
        if (ShulkerAttachment.keepsCurrentPose(level, shulker, occupied, inZone)) {
            return Optional.of(shulker.position());
        }
        // 2. Otherwise the nearest six-face node inside the native reach of its own cell.
        GridPos origin = ShulkerAttachment.of(shulker).cell();
        ShulkerAttachment node = near(level, shulker, origin, occupied, allowed, inZone);
        return node == null ? Optional.empty() : Optional.of(node.feet());
    }

    /**
     * The nearest legal node around that cell.
     *
     * <p>The candidate cell must be inside the deployment allowance (the caller's own battlefield rule) and the
     * whole opening must be inside the battlefield that would be committed. The two predicates are deliberately
     * separate: a cell may be reachable by the search while the body's opening would still stick out of it.</p>
     */
    private static ShulkerAttachment near(ServerLevel level, Shulker shulker, GridPos origin,
                                          Predicate<GridPos> occupied,
                                          Predicate<GridPos> allowed,
                                          Predicate<GridPos> inZone) {
        int range = MotionRules.SHULKER_TELEPORT_RANGE;
        GridPos start = origin;
        // The current cell first: a body whose support survived a deployment elsewhere lands here.
        ShulkerAttachment here = ShulkerAttachment.resolveCell(level, shulker, start, occupied, inZone);
        if (here != null && (allowed == null || allowed.test(here.cell()))) return here;
        for (int radius = 1; radius <= range; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != radius) continue;
                        GridPos cell = new GridPos(origin.x() + dx, origin.y() + dy, origin.z() + dz);
                        if (allowed != null && !allowed.test(cell)) continue;
                        if (occupied != null && occupied.test(cell)) continue;
                        if (!ShulkerAttachment.loadedColumn(level, cell.x(), cell.z())) continue;
                        ShulkerAttachment node = ShulkerAttachment.resolveCell(level, shulker, cell, occupied, inZone);
                        if (node != null) return node;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether the whole body could stand at that point as a real shell, which is what the deployment gate of a
     * wide-body family asks before anything is admitted.
     *
     * <p>Used by the shared deployment gate: only this exact type answers it, and it answers with the same node
     * rules a relocation uses - the full-open box in loaded columns, inside the would-be battlefield.</p>
     */
    public static boolean candidateFits(ServerLevel level, Shulker shulker, Vec3 feet,
                                        Predicate<GridPos> occupied,
                                        Predicate<GridPos> wouldBeZone) {
        if (level == null || shulker == null || feet == null) return false;
        ShulkerAttachment node = ShulkerAttachment.resolve(level, shulker, feet, occupied, wouldBeZone);
        if (node == null) return false;
        // The point that would really be used is the quantised one, so a candidate is only accepted when
        // quantising it does not walk the body into a different, illegal cell.
        BlockPos quantised = node.pos();
        return quantised.equals(BlockPos.containing(feet.x, feet.y, feet.z));
    }
}
