package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.mixin.BattleLivingEntityEffectsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Opt-in headless integration fixture. Uses only -Dtactical.selftest=effects. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class EffectsSelfTest {
    private static boolean enabled;
    private static int ticks;
    private static int expired;
    private static boolean cancelExpiry;
    private static LivingEntity subject;

    private EffectsSelfTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "effects".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
    }

    @SubscribeEvent
    public static void onExpired(MobEffectEvent.Expired event) {
        if (enabled && event.getEntity() == subject) {
            expired++;
            if (cancelExpiry) event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks != 20) return;
        ServerLevel level = event.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        ServerPlayer actor = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes(
                "tactical-effects-test".getBytes(StandardCharsets.UTF_8)), "EffectsTest"));
        actor.setPos(spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5);
        Creeper enemy = EntityType.CREEPER.create(level);
        if (enemy == null) throw new AssertionError("effects fixture could not create enemy");
        enemy.setPos(actor.getX() + 3, actor.getY(), actor.getZ());
        level.addFreshEntity(enemy);
        try {
            check(BattleManager.start(actor, true), "fixture battle starts");
            check(BattleManager.isParticipant(enemy), "damageable mob fixture is a participant");
            // NeoForge FakePlayer.isInvulnerableTo always returns true. Keep it only
            // as the encounter owner and exercise actual effect damage on a participant.
            subject = enemy;
            run(enemy, actor);
            Tactical.LOGGER.info("[Tactical] EFFECTS SELFTEST PASSED: baseline, own clock, freeze, one pulse, rapid cycles, expiry, attributes, hidden chain, infinite, refresh/replacement, instant, custom subclasses, cancellation, cleanup");
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] EFFECTS SELFTEST FAILED", failure);
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        } finally {
            cancelExpiry = false;
            actor.removeAllEffects();
            BattleManager.stop(actor);
            enemy.discard();
            BattleEffects.clear();
            enabled = false;
            subject = null;
            event.getServer().halt(false);
        }
    }

    private static void run(LivingEntity actor, ServerPlayer owner) {
        reset(actor);
        actor.setHealth(10);
        actor.addEffect(new MobEffectInstance(MobEffects.POISON, 600));
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.POISON) == 600 && actor.getHealth() == 10, "first own turn is baseline");
        for (int i = 0; i < 300; i++) actor.getEffect(MobEffects.POISON).tick(actor, () -> {});
        check(duration(actor, MobEffects.POISON) == 600 && actor.getHealth() == 10, "wall ticks freeze duration and poison");
        actor.invulnerableTime = 19;
        ((BattleLivingEntityEffectsAccess) actor).tactical$lastHurt(7);
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.POISON) == 400 && actor.getHealth() == 9,
                "one poison pulse per full own cycle: duration=" + duration(actor, MobEffects.POISON) + ", health=" + actor.getHealth());
        check(actor.invulnerableTime == 19 && ((BattleLivingEntityEffectsAccess) actor).tactical$lastHurt() == 7, "hurt cooldown restored");
        BattleEffects.onTurnStart(actor);
        check(actor.getHealth() == 8 && duration(actor, MobEffects.POISON) == 200, "rapid next cycle also settles once");
        BattleEffects.onTurnStart(actor);
        check(!actor.hasEffect(MobEffects.POISON) && actor.getHealth() == 7 && expired == 1, "expiry callback exactly once");

        reset(actor);
        actor.setHealth(10);
        actor.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 3));
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        check(actor.getHealth() == 11 && duration(actor, MobEffects.REGENERATION) == 400, "amplified regen once, not repeated pulses");
        actor.addEffect(new MobEffectInstance(MobEffects.HEAL, 1));
        actor.getEffect(MobEffects.HEAL).tick(actor, () -> {});
        check(actor.getHealth() == 15, "active instantaneous heal not frozen");

        reset(actor);
        double baseSpeed = actor.getAttributeValue(Attributes.MOVEMENT_SPEED);
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1));
        check(actor.getAttributeValue(Attributes.MOVEMENT_SPEED) > baseSpeed, "attribute applies immediately");
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        check(!actor.hasEffect(MobEffects.MOVEMENT_SPEED) && actor.getAttributeValue(Attributes.MOVEMENT_SPEED) == baseSpeed, "expired attribute cleaned up");

        reset(actor);
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1000, 0));
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 1));
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 800 && actor.getEffect(MobEffects.MOVEMENT_SPEED).getAmplifier() == 0, "hidden effect ages then promotes with correct duration");
        check(Math.abs(actor.getAttributeValue(Attributes.MOVEMENT_SPEED) - baseSpeed * 1.2) < 0.00001, "promoted attributes refreshed");

        reset(actor);
        actor.setHealth(10);
        actor.addEffect(new MobEffectInstance(MobEffects.REGENERATION, -1));
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.REGENERATION) == -1 && actor.getHealth() == 12, "infinite persists and pulses once each cycle");

        reset(actor);
        BattleEffects.onTurnStart(actor);
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 600, "midcycle application gets next-own-start baseline");
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 400, "new effect charged on complete next cycle");
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 900));
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 900, "in-place duration refresh is rebaselined");
        actor.forceAddEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 1), null);
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 300, "forced replacement is rebaselined");

        reset(actor);
        CountingPeriodic custom = new CountingPeriodic();
        Holder<MobEffect> holder = Holder.direct(custom);
        actor.addEffect(new MobEffectInstance(holder, 700));
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        check(custom.pulses == 1 && duration(actor, holder) == 500, "slow modded periodic subclass pulses once");
        actor.removeEffect(holder);
        reset(actor);
        ReturningFalsePeriodic removing = new ReturningFalsePeriodic();
        Holder<MobEffect> removingHolder = Holder.direct(removing);
        actor.addEffect(new MobEffectInstance(removingHolder, 700));
        BattleEffects.onTurnStart(actor);
        BattleEffects.onTurnStart(actor);
        check(!actor.hasEffect(removingHolder) && removing.pulses == 1, "false periodic return retains vanilla removal");

        reset(actor);
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 10));
        BattleEffects.onTurnStart(actor);
        cancelExpiry = true;
        BattleEffects.onTurnStart(actor);
        check(actor.hasEffect(MobEffects.MOVEMENT_SPEED) && expired == 1, "NeoForge expiry cancellation respected");
        cancelExpiry = false;
        BattleEffects.onTurnStart(actor);
        check(!actor.hasEffect(MobEffects.MOVEMENT_SPEED) && expired == 2, "canceled expiry retries next own cycle");

        reset(actor);
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
        BattleEffects.onTurnStart(actor);
        BattleEffects.forget(actor);
        BattleEffects.onTurnStart(actor);
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 600, "cleanup removes clock baseline");
        BattleManager.stop(owner);
        actor.getEffect(MobEffects.MOVEMENT_SPEED).tick(actor, () -> {});
        check(duration(actor, MobEffects.MOVEMENT_SPEED) == 599, "vanilla time resumes outside battle");
    }

    private static void reset(LivingEntity actor) {
        actor.removeAllEffects();
        BattleEffects.forget(actor);
        actor.invulnerableTime = 0;
        expired = 0;
    }

    private static int duration(LivingEntity actor, Holder<MobEffect> effect) {
        MobEffectInstance instance = actor.getEffect(effect);
        return instance == null ? 0 : instance.getDuration();
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }

    private static class CountingPeriodic extends MobEffect {
        int pulses;
        CountingPeriodic() { super(MobEffectCategory.NEUTRAL, 0); }
        @Override public boolean shouldApplyEffectTickThisTick(int time, int amplifier) { return time % 1000 == 0; }
        @Override public boolean applyEffectTick(LivingEntity actor, int amplifier) { pulses++; return true; }
    }

    private static final class ReturningFalsePeriodic extends CountingPeriodic {
        @Override public boolean applyEffectTick(LivingEntity actor, int amplifier) { pulses++; return false; }
    }
}
