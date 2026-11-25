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
    /**
     * The encounter's own pre-impact decision, asked before any native hit runs.
     *
     * <p>Some of this encounter's projectiles must be refused a victim the world would otherwise hand them - a
     * same-side unit, a bystander, another encounter's member. Cancelling here is the only place that happens
     * before vanilla applies its damage, its enchantments and its effects, so it is the only honest guard.</p>
     */
    private java.util.function.Predicate<ProjectileImpactEvent> impactGuard;
    private boolean dirty;

    public BattleProjectiles(ServerLevel level, ServerPlayer shooter) {
        this.level = level;
        this.shooter = shooter;
    }

    /** Calls vanilla weapon code without opening the ordinary item-use lock. */
    private LivingEntity captureShooter;
    private BallisticShot.Plan capturePlan;
    private int captureFlightTicks = MAX_FLIGHT_TICKS;
    /** Projectiles this one capture's join event saw. The world has not registered them yet. */
    private final java.util.Set<Projectile> captureCandidates =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean centralShot;

    public int capture(Runnable fire) { return capture(shooter,null,fire); }

    /** Install (or replace) the encounter's pre-impact guard; {@code null} removes it. */
    public void impactGuard(java.util.function.Predicate<ProjectileImpactEvent> guard) {
        this.impactGuard = guard;
    }

    public int capture(LivingEntity actor, BallisticShot.Plan plan, Runnable fire) {
        return capture(actor, plan, fire, MAX_FLIGHT_TICKS);
    }

    /**
     * Fire one native shot and track the projectiles it really produced.
     *
     * @param flightTicks how long a projectile of this kind may keep the encounter busy; only a type whose native
     *                    flight is genuinely longer than an arrow's asks for more
     */
    public int capture(LivingEntity actor, BallisticShot.Plan plan, Runnable fire, int flightTicks) {
        return capture(actor, plan, fire, flightTicks, null);
    }

    /**
     * The same shot, reporting every projectile the world really registered to a caller.
     *
     * <p>The report exists because some kinds need their own per-projectile receipt: a native shulker bullet is
     * marked with its immutable ownership at the moment the level really took it, and a count cannot name an
     * entity. It is called once per joined projectile, after {@code fire} returned and the registration could
     * really be read - never inside the join event, which fires before the add and may still be cancelled.</p>
     */
    public int capture(LivingEntity actor, BallisticShot.Plan plan, Runnable fire, int flightTicks,
                       java.util.function.Consumer<Projectile> joined) {
        if (capturing != null) throw new IllegalStateException("Nested battle shot");
        int before = flights.size();
        captureShooter=actor;
        capturePlan=plan;
        captureFlightTicks=Math.max(1, flightTicks);
        captureCandidates.clear();
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
        // The receipt is delivered here, from the caller's own consumer: the capture window above is already
        // closed, so nothing it does can be mistaken for part of the native spawn.
        deliverJoined(joined);
        return flights.size() - before;
    }

    /**
     * Report this capture's projectiles that the world really registered, and release the marks of the ones that
     * never were.
     *
     * <p>A join event can be cancelled after this class has already tracked the candidate, so a receipt taken
     * inside the event could mark a projectile the level never took - a mark that outlives the shot it names.
     * Deferring until the capture can prove {@code level.getEntity(id) == that exact projectile} is what keeps a
     * refused native spawn free of marks, flights and charges. The candidate list is always cleared, so a receipt
     * that throws cannot leak into the next capture.</p>
     */
    private void deliverJoined(java.util.function.Consumer<Projectile> report) {
        if (captureCandidates.isEmpty()) return;
        try {
            for (Projectile candidate : captureCandidates) {
                boolean registered = !candidate.isRemoved() && level.getEntity(candidate.getId()) == candidate
                        && flights.containsKey(candidate);
                if (registered) {
                    if (report != null) report.accept(candidate);
                    continue;
                }
                OWNERS.remove(candidate);
                forgetMark(candidate);
            }
        } finally {
            captureCandidates.clear();
        }
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
            capturing.flights.put(projectile, new Flight(capturing.captureFlightTicks));
            OWNERS.put(projectile, capturing);
            // Not a receipt yet: this event fires before the level really took the entity.
            capturing.captureCandidates.add(projectile);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onImpact(ProjectileImpactEvent event) {
        BattleProjectiles owner = OWNERS.get(event.getProjectile());
        if (owner == null) return;
        if (owner.impactGuard != null && owner.impactGuard.test(event)) return;
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
                BattlePotionSplash.forget(projectile);
                forgetMark(projectile);
                OWNERS.remove(projectile);
                iterator.remove();
                dirty = true;
            } else if (!flight.settled) {
                if (flight.hitBlock && projectile instanceof AbstractArrow arrow && !arrow.isPickable()) {
                    flight.settled = true;
                    dirty = true;
                } else if (--flight.remaining <= 0) {
                    // Fireworks, misses, cancelled impacts and piercing arrows
                    // cannot hold the battle forever or hit after it has been unlocked.
                    forgetMark(projectile);
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

    /**
     * Whether that exact projectile is one live flight this encounter really registered.
     *
     * <p>Asked before any per-projectile authority is granted: a mark, a deflection record or a relation filter
     * may only ever describe a shot this encounter really fired and still tracks. Identity is the projectile object
     * and this tracker, never an entity id and never a type, so a wild or foreign shot of the same kind can never
     * borrow the authority of one of ours - and one of ours that already left the world cannot keep it.</p>
     */
    public boolean tracks(Projectile projectile) {
        if (projectile == null || projectile.isRemoved() || !projectile.isAlive()) return false;
        if (OWNERS.get(projectile) != this) return false;
        Flight flight = flights.get(projectile);
        return flight != null && !flight.settled;
    }

    public int pendingCount() {
        return (int) flights.values().stream().filter(flight -> !flight.settled).count();
    }

    public void clear() {
        for (var entry : flights.entrySet()) {
            if (!entry.getValue().settled) entry.getKey().discard();
            BattlePotionSplash.forget(entry.getKey());
            forgetMark(entry.getKey());
            OWNERS.remove(entry.getKey());
        }
        flights.clear();
    }

    /**
     * A marked shot's ownership ends with its own flight; nothing may answer for it afterwards.
     *
     * <p>Every mark this mod keeps for a projectile is dropped here, so a shot that really left the world cannot
     * leave a mark behind: the shell's bullet, the breeze's wind charge and the breeze-deflector record all live
     * exactly as long as the entity does.</p>
     */
    private static void forgetMark(Projectile projectile) {
        if (projectile instanceof net.minecraft.world.entity.projectile.ShulkerBullet bullet) {
            ShulkerBullets.forget(bullet);
        }
        if (projectile instanceof net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge charge) {
            BreezeWindCharges.forget(charge);
        }
        BreezeDeflections.forget(projectile);
    }

    private static final class Flight {
        int remaining;

        Flight(int flightTicks) {
            this.remaining = flightTicks;
        }

        boolean hitBlock;
        boolean settled;
    }
}
