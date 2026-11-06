package com.tactical.neoforge.battle;

import com.tactical.neoforge.mixin.EnderManTeleportInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.monster.EnderMan;

/**
 * The single native teleport entry this ruleset is allowed to drive.
 *
 * <p>Kept deliberately tiny: it calls vanilla's own {@code teleport(DDD)} and nothing else. The caller owns every
 * gate - which point is worth trying, whether the resulting position is legal, what it costs - so this class can
 * never become a second, softer teleport implementation. Vanilla keeps the sound, the particles, the game event and
 * the {@code EntityTeleportEvent.EnderEntity} veto, exactly as in a mob that teleported on its own.</p>
 */
public final class EndermanNative {

    private EndermanNative() { }

    /**
     * Drive one native teleport attempt at that exact point.
     *
     * @return vanilla's own answer: true only when the entity really moved
     */
    public static boolean teleportTo(EnderMan enderman, double x, double y, double z) {
        if (enderman == null || enderman.isRemoved() || enderman.level().isClientSide) return false;
        return ((EnderManTeleportInvoker) enderman).tactical$teleport(x, y, z);
    }

    /** The block the unit is currently carrying, as this version really exposes it. */
    public static BlockState carriedBlock(EnderMan enderman) {
        return enderman == null ? null : enderman.getCarriedBlock();
    }

    /** Give back exactly the carried block an admission borrowed, never a new one. */
    public static void restoreCarriedBlock(EnderMan enderman, BlockState carried) {
        if (enderman == null) return;
        if (enderman.getCarriedBlock() != carried) enderman.setCarriedBlock(carried);
    }

    /** The cell this unit really stands in right now, from its own position and no cached value. */
    public static BlockPos feetBlock(EnderMan enderman) {
        return enderman == null ? BlockPos.ZERO : enderman.blockPosition();
    }
}
