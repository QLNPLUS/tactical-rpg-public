package com.tactical.neoforge.battle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Burning is a status clock, not the separate damage from standing in lava/fire. */
public final class BattleBurning {
    private static final Set<UUID> BASELINES = new HashSet<>();

    private BattleBurning() {}

    /** Fresh ignition waits until its first own start; continuous refresh does not erase elapsed cycles. */
    public static void onTurnStart(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        if (entity.isInWaterRainOrBubble()) entity.clearFire();
        int remaining = entity.getRemainingFireTicks();
        if (remaining <= 0) {
            forget(entity);
            return;
        }
        // Construction can baseline before BattleManager publishes the battle.
        boolean first = BASELINES.add(entity.getUUID());
        if (first || !BattleManager.isParticipant(entity)) return;
        entity.setRemainingFireTicks(Math.max(0, remaining - BattleEffects.TICKS_PER_OWN_CYCLE));
        // Vanilla suppresses this status pulse in lava; lavaHurt remains completely untouched.
        if (!entity.fireImmune() && !entity.isInLava()) BattleEffects.applyBurningPulse(entity);
    }

    /** Entity setter hook catches extinguishing/reignition between two own starts. */
    public static void onFireTicksChanged(Entity entity, int ticks) {
        if (!entity.level().isClientSide && ticks <= 0) BASELINES.remove(entity.getUUID());
    }

    /** Only the positive, nonimmune baseTick countdown and onFire pulse are suspended. */
    public static boolean freezeStatusTick(Entity entity) {
        return entity instanceof LivingEntity living && !entity.level().isClientSide
                && BattleManager.isParticipant(living);
    }

    public static void forget(LivingEntity entity) { BASELINES.remove(entity.getUUID()); }
    public static void clear() { BASELINES.clear(); }
}
