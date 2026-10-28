package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/** Tracks actual vanilla shots, including multishot/piercing, until all flight resolves. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleProjectiles {
    private static final int MAX_FLIGHT_TICKS = 80;
    private static final Map<Projectile, BattleProjectiles> OWNERS = new IdentityHashMap<>();
    private static BattleProjectiles capturing;

    private final ServerLevel level;
    private final ServerPlayer shooter;
    private final Map<Projectile, Flight> flights = new IdentityHashMap<>();
    private boolean dirty;

    public BattleProjectiles(ServerLevel level, ServerPlayer shooter) {
        this.level = level;
        this.shooter = shooter;
    }

    /** Calls vanilla weapon code without opening the ordinary item-use lock. */
    private LivingEntity captureShooter;
    private BallisticShot.Plan capturePlan;
    private boolean centralShot;

    public int capture(Runnable fire) { return capture(shooter,null,fire); }

    public int capture(LivingEntity actor, BallisticShot.Plan plan, Runnable fire) {
        if (capturing != null) throw new IllegalStateException("Nested battle shot");
        int before = flights.size();
        captureShooter=actor;
        capturePlan=plan;
        centralShot=true;
        capturing = this;
        try {
            fire.run();
        } finally {
            capturing = null;
            captureShooter=null;
            capturePlan=null;
        }
        flights.entrySet().removeIf(entry -> {
            if (level.getEntity(entry.getKey().getId()) == entry.getKey()) return false;
            OWNERS.remove(entry.getKey());
            return true;
        });
        return flights.size() - before;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (capturing != null && event.getLevel() == capturing.level
                && event.getEntity() instanceof Projectile projectile && projectile.getOwner() == capturing.captureShooter) {
            if(capturing.centralShot && capturing.capturePlan!=null) {
                var plan=capturing.capturePlan;
                projectile.shoot(plan.velocity().x,plan.velocity().y,plan.velocity().z,plan.speed(),0);
            }
            capturing.centralShot=false;
            capturing.flights.put(projectile, new Flight());
            OWNERS.put(projectile, capturing);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onImpact(ProjectileImpactEvent event) {
        BattleProjectiles owner = OWNERS.get(event.getProjectile());
        if (owner == null) return;
        Flight flight = owner.flights.get(event.getProjectile());
        if (flight == null) return;
        // A missed arrow may stay embedded for vanilla pickup. If its support
        // later disappears it must not deal an extra attack during another turn.
        if (flight.settled) {
            event.setCanceled(true);
            event.getProjectile().discard();
            return;
        }
        owner.dirty = true;
        if (event.getRayTraceResult().getType() == HitResult.Type.BLOCK) flight.hitBlock = true;
    }

    /** Called after entity ticks, so impacts, health and death are already authoritative. */
    public boolean tick() {
        boolean wasPending = pending();
        var iterator = flights.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Projectile projectile = entry.getKey();
            Flight flight = entry.getValue();
            if (projectile.isRemoved()) {
                OWNERS.remove(projectile);
                iterator.remove();
                dirty = true;
            } else if (!flight.settled) {
                if (flight.hitBlock && projectile instanceof AbstractArrow arrow && !arrow.isPickable()) {
                    flight.settled = true;
                    dirty = true;
                } else if (--flight.remaining <= 0) {
                    // Fireworks, misses, cancelled impacts and piercing arrows
                    // cannot hold the battle forever or hit after it has unlocked.
                    projectile.discard();
                    OWNERS.remove(projectile);
                    iterator.remove();
                    dirty = true;
                }
            }
        }
        boolean changed = dirty || wasPending != pending();
        dirty = false;
        return changed;
    }

    public boolean pending() {
        return flights.values().stream().anyMatch(flight -> !flight.settled);
    }

    public int pendingCount() {
        return (int) flights.values().stream().filter(flight -> !flight.settled).count();
    }

    public void clear() {
        for (var entry : flights.entrySet()) {
            if (!entry.getValue().settled) entry.getKey().discard();
            OWNERS.remove(entry.getKey());
        }
        flights.clear();
    }

    private static final class Flight {
        int remaining = MAX_FLIGHT_TICKS;
        boolean hitBlock;
        boolean settled;
    }
}
