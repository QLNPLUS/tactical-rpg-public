package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.mixin.BattleLivingEntityEffectsAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side effect time belongs to an actor's own turn, not wall-clock ticks.
 * Call onTurnStart exactly once per actual actor turn, before calculating its AP.
 * The first call establishes a baseline; each later complete own cycle is 200 ticks.
 * A newly applied, replaced, or materially refreshed effect first baselines at the
 * next own start, so an incomplete initial cycle is never charged as a full cycle.
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleEffects {
    public static final int TICKS_PER_OWN_CYCLE = 200;

    private static final Map<UUID, OwnTurnEffectClock<Holder<MobEffect>>> CLOCKS = new HashMap<>();
    private static final ThreadLocal<Settlement> SETTLEMENT = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> APPLYING = new ThreadLocal<>();
    private static final Set<UUID> FOOD_BASELINES = new HashSet<>();
    private static final ThreadLocal<Player> FOOD_SETTLEMENT = new ThreadLocal<>();

    private BattleEffects() {}

    /** Also accepts the first baseline while BattleManager is still constructing the battle. */
    public static void onTurnStart(LivingEntity entity) {
        if (entity.level().isClientSide || !entity.isAlive() || SETTLEMENT.get() != null || FOOD_SETTLEMENT.get() != null) return;
        BattleBurning.onTurnStart(entity);
        if (!entity.isAlive()) return;
        // The first own start establishes the food clock. Each later complete
        // cycle executes one native food tick, including exhaustion and starvation.
        if (entity instanceof Player player && !FOOD_BASELINES.add(entity.getUUID())
                && BattleManager.isParticipant(player)) {
            settleFood(player);
            if (!entity.isAlive()) return;
        }
        OwnTurnEffectClock<Holder<MobEffect>> clock = CLOCKS.computeIfAbsent(entity.getUUID(), key -> new OwnTurnEffectClock<>());
        Map<Holder<MobEffect>, OwnTurnEffectClock.Stamp> before = snapshot(entity);
        for (Holder<MobEffect> holder : clock.begin(before)) {
            if (!entity.isAlive()) break;
            MobEffectInstance instance = entity.getEffect(holder);
            if (instance == null || !before.get(holder).matches(stamp(instance))) continue;
            settle(entity, instance);
        }
        // Snapshot after callbacks: replacements/new effects wait for the next complete cycle.
        clock.baseline(snapshot(entity));
    }

    /** Call whenever an actor leaves the battle, including death and absorption rollback. */
    public static void forget(LivingEntity entity) {
        BattleBurning.forget(entity);
        CLOCKS.remove(entity.getUUID());
        FOOD_BASELINES.remove(entity.getUUID());
    }

    /** Call when the battle finishes, including manual stop. No effect instances are removed. */
    public static void clear() {
        BattleBurning.clear();
        CLOCKS.clear();
        FOOD_BASELINES.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    /** Damage isolation and positional/defend modifiers must permit this target's own pulse. */
    public static boolean isApplyingEffect(LivingEntity target) {
        return APPLYING.get() == target;
    }

    /** Freeze food consumption, natural healing and starvation between own starts. */
    public static boolean freezeFoodTick(Player player) {
        return !player.level().isClientSide && FOOD_SETTLEMENT.get() != player && BattleManager.isParticipant(player);
    }

    /** Identifies only this player's one vanilla food settlement invocation. */
    public static boolean isSettlingFood(FoodData food) {
        Player player = FOOD_SETTLEMENT.get();
        return player != null && player.getFoodData() == food;
    }

    /** One native onFire pulse, sharing effect isolation and restoring unrelated hurt cooldowns. */
    static void applyBurningPulse(LivingEntity entity) {
        BattleLivingEntityEffectsAccess access = (BattleLivingEntityEffectsAccess) entity;
        LivingEntity previous = APPLYING.get();
        int oldInvulnerableTime = entity.invulnerableTime;
        float oldLastHurt = access.tactical$lastHurt();
        APPLYING.set(entity);
        entity.invulnerableTime = 0;
        try {
            entity.hurt(entity.damageSources().onFire(), 1.0F);
        } finally {
            entity.invulnerableTime = oldInvulnerableTime;
            access.tactical$lastHurt(oldLastHurt);
            if (previous == null) APPLYING.remove(); else APPLYING.set(previous);
        }
    }

    private static void settleFood(Player player) {
        BattleLivingEntityEffectsAccess access = (BattleLivingEntityEffectsAccess) player;
        LivingEntity previous = APPLYING.get();
        int oldInvulnerableTime = player.invulnerableTime;
        float oldLastHurt = access.tactical$lastHurt();
        FOOD_SETTLEMENT.set(player);
        APPLYING.set(player);
        player.invulnerableTime = 0;
        try {
            player.getFoodData().tick(player);
        } finally {
            player.invulnerableTime = oldInvulnerableTime;
            access.tactical$lastHurt(oldLastHurt);
            if (previous == null) APPLYING.remove(); else APPLYING.set(previous);
            FOOD_SETTLEMENT.remove();
        }
    }

    /** Mixin entry point; instant effects retain their ordinary immediate/next-tick behavior. */
    public static boolean freezeVanillaTick(LivingEntity entity, MobEffectInstance instance) {
        if (entity.level().isClientSide || instance.getEffect().value().isInstantenous()) return false;
        Settlement settlement = SETTLEMENT.get();
        if (settlement != null && settlement.entity == entity && settlement.instance == instance) return false;
        return BattleManager.isParticipant(entity);
    }

    /** Mixin entry point: a conventional modulo periodic subclass gets one callback per cycle. */
    public static boolean shouldApplyPeriodic(MobEffect effect, int time, int amplifier, MobEffectInstance instance) {
        Settlement settlement = SETTLEMENT.get();
        if (settlement == null || settlement.instance != instance) {
            return effect.shouldApplyEffectTickThisTick(time, amplifier);
        }
        if (settlement.pulsed) return false;
        int virtualTime = instance.isInfiniteDuration() ? settlement.elapsed : time;
        return effect.shouldApplyEffectTickThisTick(virtualTime, amplifier)
                || effect.shouldApplyEffectTickThisTick(0, amplifier);
    }

    /** Mixin entry point; scope always unwinds, including mod callback exceptions. */
    public static boolean applyPeriodic(MobEffect effect, LivingEntity entity, int amplifier, MobEffectInstance instance) {
        Settlement settlement = SETTLEMENT.get();
        if (settlement != null && (settlement.instance != instance || settlement.entity != entity)) settlement = null;
        if (settlement != null) settlement.pulsed = true;
        LivingEntity previous = APPLYING.get();
        OwnTurnEffectClock.Stamp before = settlement == null ? null : stamp(instance);
        BattleLivingEntityEffectsAccess access = (BattleLivingEntityEffectsAccess) entity;
        int oldInvulnerableTime = entity.invulnerableTime;
        float oldLastHurt = access.tactical$lastHurt();
        // Tactical cycles may advance faster than vanilla's twenty-tick hurt cooldown.
        // Keep nonbattle and immediate effects on vanilla cooldown semantics.
        if (settlement != null) entity.invulnerableTime = 0;
        APPLYING.set(entity);
        try {
            return effect.applyEffectTick(entity, amplifier);
        } finally {
            if (settlement != null) {
                entity.invulnerableTime = oldInvulnerableTime;
                access.tactical$lastHurt(oldLastHurt);
                if (!before.matches(stamp(instance))) settlement.changedByCallback = true;
            }
            if (previous == null) APPLYING.remove(); else APPLYING.set(previous);
        }
    }

    private static void settle(LivingEntity entity, MobEffectInstance instance) {
        BattleLivingEntityEffectsAccess access = (BattleLivingEntityEffectsAccess) entity;
        Settlement settlement = new Settlement(entity, instance);
        SETTLEMENT.set(settlement);
        try {
            for (int tick = 0; tick < TICKS_PER_OWN_CYCLE; tick++) {
                settlement.elapsed = tick;
                if (entity.getEffect(instance.getEffect()) != instance || !entity.isAlive()) break;
                boolean remaining = instance.tick(entity, () -> access.tactical$effectUpdated(instance, true, null));
                // A callback can remove/replace this effect; never delete its replacement.
                if (entity.getEffect(instance.getEffect()) != instance) break;
                if (!remaining) {
                    boolean canceled = NeoForge.EVENT_BUS.post(new MobEffectEvent.Expired(entity, instance)).isCanceled();
                    if (!canceled && entity.getEffect(instance.getEffect()) == instance) {
                        entity.removeEffectNoUpdate(instance.getEffect());
                        access.tactical$effectRemoved(instance);
                    }
                    break;
                }
                // A mod refreshed/reconfigured this same object from its periodic callback.
                if (settlement.changedByCallback) break;
            }
            if (entity.getEffect(instance.getEffect()) == instance) {
                access.tactical$effectUpdated(instance, false, null);
            }
        } finally {
            SETTLEMENT.remove();
        }
    }

    private static Map<Holder<MobEffect>, OwnTurnEffectClock.Stamp> snapshot(LivingEntity entity) {
        Map<Holder<MobEffect>, OwnTurnEffectClock.Stamp> snapshot = new LinkedHashMap<>();
        for (MobEffectInstance instance : new ArrayList<>(entity.getActiveEffects())) {
            if (!instance.getEffect().value().isInstantenous()) snapshot.put(instance.getEffect(), stamp(instance));
        }
        return snapshot;
    }

    private static OwnTurnEffectClock.Stamp stamp(MobEffectInstance instance) {
        List<Stage> chain = new ArrayList<>();
        for (MobEffectInstance stage = instance; stage != null; stage = ((BattleEffectInstanceView) stage).tactical$hiddenEffect()) {
            chain.add(new Stage(stage.getDuration(), stage.getAmplifier(), stage.isAmbient(), stage.isVisible(), stage.showIcon()));
        }
        return new OwnTurnEffectClock.Stamp(instance, List.copyOf(chain));
    }

    private record Stage(int duration, int amplifier, boolean ambient, boolean visible, boolean icon) {}

    private static final class Settlement {
        final LivingEntity entity;
        final MobEffectInstance instance;
        int elapsed;
        boolean pulsed;
        boolean changedByCallback;
        Settlement(LivingEntity entity, MobEffectInstance instance) {
            this.entity = entity;
            this.instance = instance;
        }
    }
}
