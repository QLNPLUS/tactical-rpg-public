package com.tactical.neoforge.battle;

import net.minecraft.world.entity.monster.Shulker;

/**
 * The per-entity seam a controlled shulker's native code answers to.
 *
 * <p>Kept apart from {@link ShulkerHandler} so the mixin can cast a shulker to this interface without knowing
 * anything about the encounter: the flag itself is mixin state, and a wild shulker simply never has a handler
 * installed.</p>
 */
public interface TacticalShulkerAccess {

    void tactical$setHandler(ShulkerHandler handler);

    ShulkerHandler tactical$handler();
}
