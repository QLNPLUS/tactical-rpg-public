package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.mixin.BattleLivingEntityEffectsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Opt-in real-server-tick fixture: -Dtactical.selftest=safety, in a disposable world. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class SafetySelfTest {
    private static boolean enabled;
    private static int ticks;
    private static ServerPlayer owner;
    private static Creeper subject;
    private static Creeper late;
    private static ServerLevel level;
    private static BlockPos origin;
    private static float holdHealth;

    private SafetySelfTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "safety".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            ticks++;
            if (ticks == 20) setup(event.getServer().overworld());
            if (ticks == 100) {
                check(subject.tickCount >= 70, "real entity ticks elapsed during hold");
                check(subject.getRemainingFireTicks() == 600 && subject.getHealth() == holdHealth,
                        "80 real server ticks freeze burning duration and periodic damage");
                exerciseBurning();
                // Late absorption must use the same safe deployment path as initial enemies.
                late = mob(origin.getX() + 6.87, origin.getY() + .13, origin.getZ() + .51);
                level.setBlock(BlockPos.containing(late.position()), Blocks.STONE.defaultBlockState(), 3);
                check(!level.noCollision(late), "late mob physically intersects a block before absorption");
                // Passive hostiles are deliberately not auto-absorbed: the production
                // outside-attacker damage hook invokes this API when an attack occurs.
                check(BattleManager.current() != null && BattleManager.current().tryAbsorb(late),
                        "late moving/intersecting mob accepted by attacker absorption API");
                check(BattleDeployment.isStandable(level, late, late.position()),
                        "late absorption immediately places actual full body safely");
            }
            if (ticks == 120) {
                check(BattleManager.isParticipant(late), "late moving/intersecting mob absorbed");
                check(BattleDeployment.isStandable(level, late, late.position()), "late mob deployed physically clear");
                BattleManager.stop(owner);
                subject.setNoAi(true);
                subject.clearFire();
                subject.setRemainingFireTicks(400);
                holdHealth = subject.getHealth();
            }
            if (ticks == 160) {
                check(subject.getRemainingFireTicks() < 400 && subject.getHealth() < holdHealth,
                        "outside battle native fire countdown and pulses resume");
                Tactical.LOGGER.info("[Tactical] SAFETY SELFTEST PASSED: nearest fractional/above deployment, full body/entity collision, bounded no-candidate purity, initial+late absorption, real-tick fire freeze, fresh baseline, refresh, one native pulse/200 ticks, cooldown, resistance, extinguish, cleanup");
                finish(event);
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] SAFETY SELFTEST FAILED at tick {}", ticks, failure);
            finish(event);
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(ServerLevel serverLevel) {
        level = serverLevel;
        BlockPos spawn = level.getSharedSpawnPos();
        int floor = Math.min(level.getMaxBuildHeight() - 12, spawn.getY() + 20);
        origin = new BlockPos(spawn.getX(), floor + 1, spawn.getZ());
        level.setWeatherParameters(10000, 0, false, false);
        for (int cx = (origin.getX() - 24) >> 4; cx <= (origin.getX() + 24) >> 4; cx++) {
            for (int cz = (origin.getZ() - 24) >> 4; cz <= (origin.getZ() + 24) >> 4; cz++) level.setChunkForced(cx, cz, true);
        }
        for (int dx = -22; dx <= 22; dx++) {
            for (int dz = -22; dz <= 22; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy <= 9; dy++) level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        deploymentChecks();
        owner = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes(
                "tactical-safety-test".getBytes(StandardCharsets.UTF_8)), "SafetyTest"));
        owner.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        subject = mob(origin.getX() + 3.86, origin.getY() + .12, origin.getZ() + .51);
        level.setBlock(BlockPos.containing(subject.position()), Blocks.STONE.defaultBlockState(), 3);
        check(!level.noCollision(subject), "initial fractional moving mob physically intersects terrain");
        check(BattleManager.start(owner, true), "fixture battle starts");
        check(BattleManager.isParticipant(subject), "initial intersecting mob accepted at a safe alternative");
        check(BattleDeployment.isStandable(level, subject, subject.position()), "initial mob deployed physically clear");
        BattleEffects.forget(subject);
        subject.setRemainingFireTicks(600);
        holdHealth = subject.getHealth();
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 600 && subject.getHealth() == holdHealth, "first ignition baseline has no charge");
    }

    private static void deploymentChecks() {
        Creeper probe = EntityType.CREEPER.create(level);
        check(probe != null, "deployment probe created");
        probe.setPos(origin.getX() - 7.43, origin.getY() + .25, origin.getZ() + .53);
        probe.setDeltaMovement(.12, -.07, .03);
        BlockPos column = probe.blockPosition();
        level.setBlock(column, Blocks.STONE.defaultBlockState(), 3);
        Vec3 above = BattleDeployment.findNearestStandable(level, probe, pos -> false,
                pos -> pos.getX() == column.getX() && pos.getZ() == column.getZ()).orElseThrow();
        check(above.x == probe.getX() && above.z == probe.getZ() && above.y == origin.getY() + 1,
                "blocked fractional source searches immediately above without recentering");
        check(BattleDeployment.isStandable(level, probe, above), "full body clear at returned above position");
        Vec3 nearest = BattleDeployment.findNearestStandable(level, probe, pos -> false, pos -> true).orElseThrow();
        Vec3 again = BattleDeployment.findNearestStandable(level, probe, pos -> false, pos -> true).orElseThrow();
        check(nearest.equals(again) && nearest.distanceToSqr(probe.position()) <= above.distanceToSqr(probe.position()),
                "nearest feet-distance candidate and deterministic tie breaking");
        level.setBlock(column.above(2), Blocks.STONE.defaultBlockState(), 3);
        check(!BattleDeployment.isStandable(level, probe, above), "head collision rejects otherwise supported feet");
        level.setBlock(column.above(2), Blocks.AIR.defaultBlockState(), 3);
        Creeper blocker = mob(above.x, above.y, above.z);
        check(!BattleDeployment.isStandable(level, probe, above), "entity collision rejects supported cell");
        blocker.discard();
        Vec3 oldPosition = probe.position(), oldMotion = probe.getDeltaMovement();
        boolean oldNoAi = probe.isNoAi();
        check(BattleDeployment.findNearestStandable(level, probe, pos -> true, pos -> true).isEmpty(), "occupied search has no unsafe fallback");
        check(BattleDeployment.findNearestStandable(level, probe, pos -> false, pos -> false).isEmpty(), "outside zone has no unsafe fallback");
        // A literal solid volume proves physical rejection rather than just a predicate shortcut.
        for (BlockPos pos : BlockPos.betweenClosed(column.offset(-5, -8, -5), column.offset(5, 9, 5)))
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        check(BattleDeployment.findNearestStandable(level, probe, pos -> false, pos -> true).isEmpty(), "bounded solid volume has no candidate");
        check(probe.position().equals(oldPosition) && probe.getDeltaMovement().equals(oldMotion) && probe.isNoAi() == oldNoAi,
                "failed query preserves position, movement and noAI");
        for (BlockPos pos : BlockPos.betweenClosed(column.offset(-5, 0, -5), column.offset(5, 9, 5)))
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        probe.discard();
    }

    private static void exerciseBurning() {
        BattleLivingEntityEffectsAccess access = (BattleLivingEntityEffectsAccess) subject;
        subject.invulnerableTime = 19;
        access.tactical$lastHurt(7);
        float health = subject.getHealth();
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 400 && subject.getHealth() == health - 1, "one native onFire damage and 200 fire ticks per own cycle");
        check(subject.invulnerableTime == 19 && access.tactical$lastHurt() == 7, "burn pulse preserves unrelated hurt cooldown");
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 200 && subject.getHealth() == health - 2, "rapid consecutive own cycles each pulse once");
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 0 && subject.getHealth() == health - 3, "final short duration still pulses once and expires");
        subject.setRemainingFireTicks(600);
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 600 && subject.getHealth() == health - 3, "midcycle fresh ignition first baselines without charge");
        subject.setRemainingFireTicks(800);
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 600 && subject.getHealth() == health - 4, "continuous refresh cannot prevent complete-cycle charge");
        subject.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1000));
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 400 && subject.getHealth() == health - 4, "native fire resistance prevents damage but duration advances");
        subject.removeEffect(MobEffects.FIRE_RESISTANCE);
        subject.clearFire();
        subject.setRemainingFireTicks(600);
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() == 600, "extinguish/reignite between starts resets baseline");
        // Test extinguishing against the actual block/fluid state, not an overridden predicate.
        BlockPos water = subject.blockPosition();
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
        subject.baseTick();
        BattleEffects.onTurnStart(subject);
        check(subject.getRemainingFireTicks() <= 0, "water extinguishes instead of preserving frozen fire");
        level.setBlock(water, Blocks.LAVA.defaultBlockState(), 3);
        subject.clearFire();
        subject.invulnerableTime = 0;
        float beforeLava = subject.getHealth();
        subject.baseTick();
        check(subject.isInLava() && subject.getHealth() == beforeLava - 4,
                "real baseTick lava damage remains separate and unchanged");
        int lavaDuration = subject.getRemainingFireTicks();
        float lavaHealth = subject.getHealth();
        BattleEffects.onTurnStart(subject);
        BattleEffects.onTurnStart(subject);
        check(subject.getHealth() == lavaHealth && subject.getRemainingFireTicks() == lavaDuration - 200,
                "own-cycle status pulse obeys native no-onFire-in-lava rule");
        level.setBlock(water, Blocks.AIR.defaultBlockState(), 3);
        subject.baseTick();
        subject.clearFire();
    }

    private static Creeper mob(double x, double y, double z) {
        Creeper mob = EntityType.CREEPER.create(level);
        check(mob != null, "mob fixture created");
        mob.setPos(x, y, z);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        mob.setHealth(100);
        mob.setDeltaMovement(.11, -.03, .02);
        check(level.addFreshEntity(mob), "real mob fixture added to entity-ticking level");
        return mob;
    }

    private static void finish(ServerTickEvent.Post event) {
        enabled = false;
        if (owner != null) BattleManager.stop(owner);
        if (subject != null) subject.discard();
        if (late != null) late.discard();
        BattleEffects.clear();
        event.getServer().halt(false);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
