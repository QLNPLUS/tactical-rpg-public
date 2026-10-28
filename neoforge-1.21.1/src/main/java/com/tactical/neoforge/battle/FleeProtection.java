package com.tactical.neoforge.battle;

import com.tactical.core.combat.BattleRules;
import com.tactical.neoforge.Tactical;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Real-server-tick flee grace; does not freeze AI or make the player invulnerable. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class FleeProtection {
    private static final Countdown protection = new Countdown();

    private FleeProtection() {}

    public static boolean has(ServerPlayer player) { return has(player.getUUID()); }
    public static boolean has(UUID player) { return protection.has(player); }

    /** Immediately forget this player, including mobs outside the old encounter/zone. */
    public static void give(ServerPlayer player) {
        protection.give(player.getUUID());
        clearAggro(player.server, player.getUUID());
    }

    public static void remove(ServerPlayer player) { remove(player.getUUID()); }
    public static void remove(UUID player) { protection.remove(player); }
    public static void clear() { protection.clear(); }

    /** Called exactly once by BattleManager's ordinary server-tick handler. */
    public static void tick(MinecraftServer server) {
        if (protection.empty()) return;
        // Brain behaviors can store attack/hurt/anger memories without Mob.setTarget.
        for (UUID player : protection.players()) clearAggro(server, player);
        protection.tick();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && has(player)) {
            event.setCanceled(true);
            if (event.getEntity() instanceof Mob mob) clearAggro(mob, player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        // Post is immutable, after armor, absorption, cancellation and other reductions.
        // Consumed absorption hearts are actual damage too. A swing, miss, shield
        // block, cancelled hit or hit with no health/absorption loss remains inert.
        if (!(event.getNewDamage() > 0 || event.getReduction(DamageContainer.Reduction.ABSORPTION) > 0)) return;
        ServerPlayer player = causingPlayer(event.getSource());
        if (player == null || !has(player)) return;
        remove(player);
        // Do not start a battle here: the normal auto-trigger setting and scan own that.
        // Retaliation can already have been suppressed earlier in the hurt pipeline.
        if (event.getEntity() instanceof Mob mob && mob instanceof Enemy && mob.isAlive()) {
            mob.setTarget(player);
        }
    }

    /** Mojmap DamageSource.getEntity is the causing entity (not the direct projectile). */
    public static ServerPlayer causingPlayer(DamageSource source) {
        ServerPlayer causing = ownerPlayer(source.getEntity());
        return causing != null ? causing : ownerPlayer(source.getDirectEntity());
    }

    private static ServerPlayer ownerPlayer(Entity entity) {
        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (entity != null && visited.add(entity)) {
            if (entity instanceof ServerPlayer player) return player;
            if (entity instanceof Projectile projectile) entity = projectile.getOwner();
            else if (entity instanceof PrimedTnt tnt) entity = tnt.getOwner();
            else if (entity instanceof AreaEffectCloud cloud) entity = cloud.getOwner();
            else if (entity instanceof OwnableEntity owned) entity = owned.getOwner();
            else return null;
        }
        return null;
    }

    private static void clearAggro(MinecraftServer server, UUID player) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob) clearAggro(mob, player);
            }
        }
    }

    private static boolean matches(Entity entity, UUID player) {
        return entity != null && entity.getUUID().equals(player);
    }

    private static void clearAggro(Mob mob, UUID player) {
        if (matches(mob.getTarget(), player)) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
        if (matches(mob.getKillCredit(), player)) mob.setLastHurtByPlayer(null);
        if (matches(mob.getLastHurtByMob(), player)) mob.setLastHurtByMob(null);
        if (matches(mob.getLastHurtMob(), player)) mob.setLastHurtMob(null);
        Brain<?> brain = mob.getBrain();
        if (memory(brain, MemoryModuleType.WALK_TARGET)
                .filter(walk -> walk.getTarget() instanceof EntityTracker tracker && matches(tracker.getEntity(), player)).isPresent()) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getNavigation().stop();
        }
        if (memory(brain, MemoryModuleType.LOOK_TARGET)
                .filter(look -> look instanceof EntityTracker tracker && matches(tracker.getEntity(), player)).isPresent()) {
            brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
        eraseMatchingEntity(brain, MemoryModuleType.ATTACK_TARGET, player);
        eraseMatchingEntity(brain, MemoryModuleType.HURT_BY_ENTITY, player);
        DamageSource hurt = memory(brain, MemoryModuleType.HURT_BY).orElse(null);
        ServerPlayer attacker = hurt == null ? null : causingPlayer(hurt);
        if (attacker != null && attacker.getUUID().equals(player)) brain.eraseMemory(MemoryModuleType.HURT_BY);
        if (memory(brain, MemoryModuleType.ANGRY_AT).filter(player::equals).isPresent()) {
            brain.eraseMemory(MemoryModuleType.ANGRY_AT);
        }
        if (mob instanceof NeutralMob neutral && player.equals(neutral.getPersistentAngerTarget())) {
            // stopBeingAngry also clears unrelated target/hurt references; do not use it.
            neutral.setPersistentAngerTarget(null);
            neutral.setRemainingPersistentAngerTime(0);
        }
        if (mob instanceof Warden warden) {
            ServerPlayer suspect = mob.getServer().getPlayerList().getPlayer(player);
            if (suspect == null && mob.level().getPlayerByUUID(player) instanceof ServerPlayer local) suspect = local;
            if (suspect != null) warden.clearAnger(suspect);
        }
    }

    private static <T extends Entity> void eraseMatchingEntity(Brain<?> brain, MemoryModuleType<T> memory, UUID player) {
        if (memory(brain, memory).filter(entity -> matches(entity, player)).isPresent()) brain.eraseMemory(memory);
    }

    private static <T> Optional<T> memory(Brain<?> brain, MemoryModuleType<T> type) {
        Optional<T> value = brain.getMemoryInternal(type);
        return value == null ? Optional.empty() : value;
    }

    /** Pure countdown: package-visible so ordinary JUnit tests need no Minecraft bootstrap. */
    static final class Countdown {
        private final Map<UUID, Integer> ticks = new HashMap<>();
        void give(UUID player) { ticks.put(player, BattleRules.FLEE_IMMUNITY_TICKS); }
        boolean has(UUID player) { return ticks.getOrDefault(player, 0) > 0; }
        void remove(UUID player) { ticks.remove(player); }
        void clear() { ticks.clear(); }
        boolean empty() { return ticks.isEmpty(); }
        Set<UUID> players() { return Set.copyOf(ticks.keySet()); }
        void tick() {
            ticks.replaceAll((player, remaining) -> remaining - 1);
            ticks.entrySet().removeIf(entry -> entry.getValue() <= 0);
        }
    }
}
