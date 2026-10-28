package com.tactical.neoforge.battle.ai;

/** Implemented by the dedicated shared creeper mixin; never persists tactical flags to NBT. */
public interface TacticalCreeperAccess {
    void tactical$setControlled(boolean controlled);
    boolean tactical$isControlled();
    void tactical$setPreparing(boolean preparing);
    boolean tactical$isPreparing();
    int tactical$getExplosionRadius();
    /** Invokes vanilla explodeCreeper, including NeoForge explosion/death hooks. */
    void tactical$explodeCreeper();
}
