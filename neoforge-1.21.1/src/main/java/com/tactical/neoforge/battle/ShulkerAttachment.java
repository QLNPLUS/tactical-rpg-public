package com.tactical.neoforge.battle;

import com.tactical.core.combat.MotionRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Where a shulker really holds on, and whether it may hold there.
 *
 * <p>A shulker is not a body standing on a floor. It glues itself to one of the six faces of its own block, its
 * shell opens along the opposite direction, and the opening is a real box that has to be free. A plain
 * {@code GridPos} cannot say any of that, so this node carries the cell, the attach face and the real feet
 * together, and every gate below is the same question vanilla asks itself in {@code canStayAt} - asked before a
 * relocation instead of after it.</p>
 *
 * <p>Three of the gates exist because a body the encounter intends to move must never be judged by a partial
 * fact:</p>
 * <ul>
 *   <li>the chunk is proved loaded <em>before</em> any block is read - a read of an unloaded column answers
 *       "nothing here", which would turn a missing chunk into a free pass through terrain;</li>
 *   <li>the opening is measured at <b>full</b> open rather than at the body's current peek, because a closed
 *       shell is a smaller box than the one it will occupy when it fires;</li>
 *   <li>the world border is asked about the whole opening, not only about the base cell.</li>
 * </ul>
 *
 * <p>Nothing here moves the entity: legality is a fact about the live world, and the caller owns the commit.</p>
 */
public record ShulkerAttachment(GridPos cell, Direction face, Vec3 feet) {

    /** The direction the shell opens in: away from the glued face. */
    public Direction opening() {
        return face.getOpposite();
    }

    /** The block this body is glued inside. */
    public BlockPos pos() {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    /** The node this body really holds right now, read from the entity itself. */
    public static ShulkerAttachment of(Shulker shulker) {
        Vec3 position = shulker.position();
        GridPos cell = new GridPos(
                Mth.floor(position.x),
                Mth.floor(position.y + 1.0E-7D),
                Mth.floor(position.z));
        return new ShulkerAttachment(cell, ShulkerNative.attachFace(shulker), feetOf(cell));
    }

    /** The native quantised feet of a cell: vanilla snaps a shulker to the block centre of its own block. */
    public static Vec3 feetOf(GridPos cell) {
        return new Vec3(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
    }

    /** The node a body would hold if vanilla moved it to that cell, quantised the way vanilla really does. */
    public static ShulkerAttachment at(GridPos cell, Direction face) {
        return new ShulkerAttachment(cell, face, feetOf(cell));
    }

    /**
     * The box a shell occupies at full open, in world coordinates for that block.
     *
     * <p>This is vanilla's own {@code getProgressAabb} at progress {@code 1.0}, which is what
     * {@code canStayAt} asks about - deliberately not the entity's current bounding box, because a closed shell
     * is a smaller box than the one it opens into.</p>
     */
    public static AABB fullOpenBox(Shulker shulker, Direction opening, BlockPos pos) {
        return Shulker.getProgressAabb(shulker.getScale(), opening, 1.0F)
                .move(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The full-open box of this node. */
    public AABB fullOpenBox(Shulker shulker) {
        return fullOpenBox(shulker, opening(), pos());
    }

    /**
     * Whether vanilla itself would let this body stay on that node, plus the three facts a body about to be
     * moved needs and vanilla never asks.
     *
     * <p>In vanilla's own order:</p>
     * <ol>
     *   <li>the block the body occupies must be air - a moving piston underneath itself is the one exception
     *       vanilla keeps;</li>
     *   <li>the block behind the glued face must be a face this body may really stand on, in a loaded chunk;</li>
     *   <li>the whole opening box, at full open, must be collision free.</li>
     * </ol>
     *
     * <p>On top of those: the base cell must not already belong to another participant, the whole opening must
     * be inside the world border, and every column the opening touches must be loaded before it is read.</p>
     */
    public static boolean legal(ServerLevel level, Shulker shulker, ShulkerAttachment node,
                                Predicate<GridPos> occupied) {
        if (level == null || shulker == null || node == null || node.face == null || node.cell == null) return false;
        if (occupied != null && occupied.test(node.cell)) return false;
        BlockPos pos = node.pos();
        // Never read a column that is not really loaded: the answer would be "air" for a world that is not there.
        if (!loadedColumn(level, pos.getX(), pos.getZ())) return false;
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            boolean movingPistonHere = state.is(Blocks.MOVING_PISTON) && pos.equals(shulker.blockPosition());
            if (!movingPistonHere) return false;
        }
        Direction open = node.opening();
        if (!level.loadedAndEntityCanStandOnFace(pos.relative(node.face), shulker, open)) return false;
        AABB opening = fullOpenBox(shulker, open, pos).deflate(1.0E-6D);
        if (opening.minY < level.getMinBuildHeight() || opening.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(opening)) return false;
        if (!BodyGeometry.loaded(level, opening)) return false;
        if (level.noCollision(shulker, opening)) return true;
        // Blocks in the window stay fatal. Bodies do too - except a projectile: a shell fires from the very
        // window it just opened, so its own shot sits inside that box for the first ticks of its flight, and
        // vanilla never asked an entity whether the window was clear. Counting the shot evicted a healthy body
        // from its own cell, which is the shape the native replication then read as an illegal pose.
        if (level.getBlockCollisions(shulker, opening).iterator().hasNext()) return false;
        return level.getEntities(shulker, opening,
                other -> !(other instanceof net.minecraft.world.entity.projectile.Projectile)
                        && other.isPickable() && !other.isSpectator()).isEmpty();
    }

    /**
     * Whether that node keeps the whole body - at full open, not at the peek it happens to carry - inside this
     * encounter's battlefield.
     *
     * <p>The entity's own bounding box is not used here on purpose: it shrinks when the shell closes, so a
     * closed shell standing half across the zone boundary would look legal.</p>
     */
    public static boolean insideZone(Shulker shulker, ShulkerAttachment node, Predicate<GridPos> inZone) {
        if (shulker == null || node == null || inZone == null) return false;
        if (!inZone.test(node.cell)) return false;
        return BodyGeometry.boxInsideZone(node.fullOpenBox(shulker), inZone);
    }

    /** Whether that column is really present, asked before any block is read. */
    public static boolean loadedColumn(ServerLevel level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    /**
     * The best legal node at or near that point, or {@code null}.
     *
     * <p>Vanilla quantises a shulker onto a block centre, so a candidate point is only a proposal: what is really
     * validated is the block a body would end up inside. The six faces are tried in vanilla's own order, and a
     * non-finite point is refused before it is quantised into a real cell.</p>
     */
    public static ShulkerAttachment resolve(ServerLevel level, Shulker shulker, Vec3 feet,
                                            Predicate<GridPos> occupied, Predicate<GridPos> inZone) {
        if (level == null || shulker == null || feet == null) return null;
        if (!Double.isFinite(feet.x) || !Double.isFinite(feet.y) || !Double.isFinite(feet.z)) return null;
        GridPos cell = new GridPos(
                Mth.floor(feet.x),
                Mth.floor(feet.y + 1.0E-7D),
                Mth.floor(feet.z));
        return resolveCell(level, shulker, cell, occupied, inZone);
    }

    /** The same six-face answer for an already quantised cell. */
    public static ShulkerAttachment resolveCell(ServerLevel level, Shulker shulker, GridPos cell,
                                                Predicate<GridPos> occupied, Predicate<GridPos> inZone) {
        if (level == null || shulker == null || cell == null) return null;
        if (!loadedColumn(level, cell.x(), cell.z())) return null;
        for (Direction face : Direction.values()) {
            ShulkerAttachment node = at(cell, face);
            if (!legal(level, shulker, node, occupied)) continue;
            if (inZone != null && !insideZone(shulker, node, inZone)) continue;
            return node;
        }
        return null;
    }

    /**
     * Whether the pose this body already holds may be kept as it is.
     *
     * <p>Asked first by every admission path, so a shell that was already glued to a wall or a ceiling is never
     * silently re-placed onto a floor: an ordinary floor search would move it to a ground standable row and
     * delete the pose it was fighting from.</p>
     */
    public static boolean keepsCurrentPose(ServerLevel level, Shulker shulker,
                                           Predicate<GridPos> occupied, Predicate<GridPos> inZone) {
        if (level == null || shulker == null) return false;
        ShulkerAttachment node = of(shulker);
        if (!legal(level, shulker, node, occupied)) return false;
        return inZone == null || insideZone(shulker, node, inZone);
    }

    /**
     * A legal node the body could really relocate to, within the native reach of its own cell.
     *
     * <p>The native entry searches {@code ±8} on three axes, so a decision helper only needs to know whether
     * such a point exists. It is asked once per own turn and cached, never per tick.</p>
     *
     * <p>When {@code shootingFrom} is supplied the search is an <em>improvement</em> check: only a node from
     * which the shot band of that target is reached counts. A relocation that buys no shot is not worth its
     * price, and accepting any neighbouring legal pose would do exactly that.</p>
     */
    public static ShulkerAttachment resolveNeighbourhood(ServerLevel level, Shulker shulker, GridPos origin,
                                                         Predicate<GridPos> inZone,
                                                         Predicate<GridPos> occupied,
                                                         Vec3 shootingFrom) {
        if (level == null || shulker == null || origin == null) return null;
        int range = MotionRules.SHULKER_TELEPORT_RANGE;
        double bandSq = (double) MotionRules.SHULKER_SHOT_RANGE_SQ;
        for (int radius = 1; radius <= range; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                // One horizontal ring per radius: the vertical offset follows the ring, so the scan stays a
                // bounded sphere rather than the whole cube.
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != radius) continue;
                        GridPos cell = new GridPos(origin.x() + dx, origin.y() + dy, origin.z() + dz);
                        if (inZone != null && !inZone.test(cell)) continue;
                        if (occupied != null && occupied.test(cell)) continue;
                        if (!loadedColumn(level, cell.x(), cell.z())) continue;
                        ShulkerAttachment node = resolveCell(level, shulker, cell, occupied, inZone);
                        if (node == null || !node.cell().equals(cell)) continue;
                        if (shootingFrom != null
                                && node.feet().distanceToSqr(shootingFrom) >= bandSq) continue;
                        return node;
                    }
                }
            }
        }
        return null;
    }

    /** Whether this node still holds for a body that is already there, ignoring nobody. */
    public boolean holds(ServerLevel level, Shulker shulker) {
        return legal(level, shulker, this, null);
    }
}
