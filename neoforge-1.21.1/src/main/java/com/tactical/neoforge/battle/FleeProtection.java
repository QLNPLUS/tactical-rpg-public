package com.tactical.neoforge.battle;

import com.tactical.core.combat.BattleRules;
import com.tactical.neoforge.Tactical;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.HashMap;
import java.util.Map;
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
            if (event.getEntity() instanceof Mob mob) AggroCleanup.clear(mob, player.getUUID());
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

    /** Kept as the public compatibility entry; the shared implementation lives in {@link AggroCleanup}. */
    public static ServerPlayer causingPlayer(DamageSource source) {
        return AggroCleanup.causingPlayer(source);
    }

    private static void clearAggro(MinecraftServer server, UUID player) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob) AggroCleanup.clear(mob, player);
            }
        }
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
