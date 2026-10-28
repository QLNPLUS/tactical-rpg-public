package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.core.grid.GridWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Terrain adapter: turns block collision shapes into standable grid cells. */
public final class LevelGridWorld implements GridWorld {

    private final Level level;

    public LevelGridWorld(Level level) {
        this.level = level;
    }

    public static GridPos standCellAt(Level level, int x, int z, int refY) {
        for (int dy = 0; dy <= 1; dy++) {
            if (isStandable(level, x, refY + dy, z)) {
                return new GridPos(x, refY + dy, z);
            }
        }
        if (isStandable(level, x, refY - 1, z)) {
            return new GridPos(x, refY - 1, z);
        }
        return null;
    }

    @Override
    public GridPos standCell(int x, int z, int refY) {
        return standCellAt(level, x, z, refY);
    }

    /** Resolve the actor's actual floor, not an unrelated roof above their head. */
    public GridPos surfaceCell(int x, int z, int fromY) {
        // Starting on a ledge must preserve the actor's height. Picking the
        // highest floor first can snap an actor up onto a nearby roof and make
        // the actual one-block downhill exit look like an illegal two-block drop.
        for (int offset : new int[] {0, -1, 1, -2, 2, -3, -4}) {
            int y = fromY + offset;
            if (isStandable(level, x, y, z)) {
                return new GridPos(x, y, z);
            }
        }
        return null;
    }

    public static boolean isStandable(Level level, int x, int y, int z) {
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();
        BlockPos ground = feet.below();

        BlockState groundState = level.getBlockState(ground);
        if (groundState.getCollisionShape(level, ground).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
            return false;
        }
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()) {
            return false;
        }
        return true;
    }
}
