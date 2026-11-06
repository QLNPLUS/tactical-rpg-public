package com.tactical.neoforge.battle;

import com.tactical.core.combat.ThrowProfile;
import com.tactical.core.combat.TridentThrow;
import com.tactical.neoforge.Tactical;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tridents this encounter's drowned really threw: how they are created, which bodies they may touch,
 * and when the mark is taken away again.
 *
 * <p>Vanilla {@code Drowned#performRangedAttack} is the only creation path this class uses. It builds a fresh
 * {@code ThrownTrident}, aims it at the target's one-third height with the {@code horizontal * 0.2} lift, and
 * shoots it at 1.6 blocks per tick with the difficulty's own inaccuracy. None of that is reproduced here, and
 * the battle's capture deliberately hands it no plan: the native origin, direction, speed and spread stay
 * exactly what vanilla applies. What this class adds is only the encounter's rule - which bodies a marked
 * trident may affect - and the promise that the mark lives from the moment the entity is created until it
 * really leaves the level.</p>
 *
 * <p>Vanilla's own trident is unenchanted, unowned by the player and not pickable: {@code pickup} is
 * {@code DISALLOWED}, so no player can ever take it, it never returns to the thrower and {@code dealtDamage}
 * stops it hurting a second time. Nothing here changes that; it also refuses to accept a replacement whose
 * stack carries enchantments, so a foreign listener cannot smuggle a loyalty or channelling trident into this
 * encounter through the mark.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class DrownedTridents {

    /** Marked projectile -> the encounter that owes it a rule. Identity, never a name or a UUID string. */
    private static final Map<ThrownTrident, Mark> MARKED = new IdentityHashMap<>();

    /** One throw in progress: the shooter being fired for, so a really created trident can be recognised. */
    private static Arming arming;

    private DrownedTridents() { }

    private record Mark(ServerBattle battle, UUID actorId, UUID targetId) { }

    private static final class Arming {
        final ServerBattle battle;
        final UUID actorId;
        final UUID targetId;
        final List<ThrownTrident> created = new ArrayList<>();
        net.minecraft.server.level.ServerLevel shooterLevel;
        int marked;
        boolean refused;

        Arming(ServerBattle battle, UUID actorId, UUID targetId) {
            this.battle = battle;
            this.actorId = actorId;
            this.targetId = targetId;
        }
    }

    /**
     * Fire one drowned's native ranged attack and mark exactly the tridents it really created.
     *
     * <p>The capture is used without a plan on purpose: {@code BallisticShot.Plan} rewrites a projectile's
     * velocity, and a trident's own native launch is the thing this encounter promises to keep. Legality was
     * already decided before this call by {@link #nominalReaches}, which predicts that same native arc.</p>
     *
     * <p>The mark is established inside the create/join transaction, as the fireball path does, so a projectile
     * that joins while the native call is still unwinding already carries this encounter's rule. The result is then
     * settled against the live world - identity, not a return value - in a {@code finally}: an exception thrown
     * after the entity was really added leaves it marked and that throw is final, and one thrown before it leaves
     * nothing behind.</p>
     *
     * @return how many marked tridents really joined the world
     */
    public static int throwNative(ServerBattle battle, BattleProjectiles tracker, Drowned shooter,
            LivingEntity target, Runnable nativeFire) {
        if (battle == null || tracker == null || shooter == null) return 0;
        if (arming != null) return 0;
        Arming attempt = new Arming(battle, shooter.getUUID(), target == null ? null : target.getUUID());
        arming = attempt;
        try {
            tracker.capture(shooter, null, nativeFire);
        } catch (RuntimeException failure) {
            // A native call can insert its entity and still throw afterwards. What counts is therefore decided by
            // what really joined - never by the absence of an exception - so the failure is recorded and the
            // transaction is settled by identity below.
            Tactical.LOGGER.error("[Tactical] native drowned trident throw failed for {}", shooter.getUUID(), failure);
        } finally {
            arming = null;
            settleAttempt(attempt);
        }
        return attempt.refused ? 0 : attempt.marked;
    }

    /**
     * Settle one throw attempt against the live world: mark what really joined, drop what did not, and refuse the
     * whole throw when the final projectile is not the vanilla one.
     *
     * <p>This runs in a {@code finally}, so a native call that throws after inserting its entity still settles.
     * The last word is a fresh look at the entity's own stack: a later listener may have enchanted or replaced it
     * during the join chain, and an enchanted trident would carry loyalty, channelling or impaling effects into this
     * encounter - exactly the world effects this ruleset excludes. Such a projectile is discarded and the throw is
     * refused rather than paid for.</p>
     */
    private static void settleAttempt(Arming attempt) {
        for (ThrownTrident trident : attempt.created) {
            boolean joined = attempt.shooterLevel != null && attempt.shooterLevel.getEntity(trident.getId()) == trident;
            if (!joined) {
                MARKED.remove(trident);
                trident.discard();
                continue;
            }
            if (attempt.refused || !nativeStillClean(trident)) {
                attempt.refused = true;
                MARKED.remove(trident);
                trident.discard();
                continue;
            }
            MARKED.put(trident, new Mark(attempt.battle, attempt.actorId, attempt.targetId));
            attempt.marked++;
        }
    }

    /** The final check on the projectile this encounter would rule. */
    private static boolean nativeStillClean(ThrownTrident trident) {
        return trident.getClass() == ThrownTrident.class
                && trident.getPickupItemStackOrigin().isEnchanted() == false
                && trident.pickup == AbstractArrow.Pickup.DISALLOWED;
    }

    /** Mark one really created trident inside the join transaction, or refuse the whole throw. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        // An integrated client shares these statics with its server, but never owns a throw transaction.
        if (!(event.getLevel() instanceof ServerLevel)) return;
        Arming attempt = arming;
        if (attempt == null || attempt.refused) return;
        if (!(event.getEntity() instanceof ThrownTrident trident)) return;
        if (!(trident.getOwner() instanceof Drowned shooter) || !attempt.actorId.equals(shooter.getUUID())) return;
        attempt.created.add(trident);
        attempt.shooterLevel = event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel : null;
        // The mark exists before the native call returns, so anything that joins the level afterwards already sees
        // this encounter's rule; the transaction is then settled by identity in the finally above.
        MARKED.put(trident, new Mark(attempt.battle, attempt.actorId, attempt.targetId));
        // Only the exact vanilla trident this method creates is acceptable. A subclass, an enchanted stack or a
        // stack whose pickup changed is somebody else's projectile, and this encounter refuses to rule it.
        if (!nativeStillClean(trident)) {
            attempt.refused = true;
            Tactical.LOGGER.warn("[Tactical] refused a non-native drowned trident for {}", attempt.actorId);
            return;
        }
        // Vanilla already defaults to this; stating it keeps "no player may ever take it" independent of how the
        // entity was constructed.
        trident.pickup = AbstractArrow.Pickup.DISALLOWED;
    }

    /**
     * The encounter's collision rule, checked before vanilla ever runs the hit.
     *
     * <p>Refusing happens at the collision entry, so a same-side unit, the thrower itself, a bystander, an
     * uninvited pet, another encounter's unit and every non-living body are untouched instead of being
     * repaired afterwards. A refused body does <em>not</em> release the mark and does not remove the
     * projectile: vanilla keeps it flying, and this encounter still owes the same trident its rule at the next
     * collision. A block hit is allowed to really happen - the trident can stick in terrain, exactly as
     * vanilla's can.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownTrident trident)) return;
        if (!(trident.level() instanceof ServerLevel)) return;
        Mark mark = MARKED.get(trident);
        if (mark == null) return;
        HitResult hit = event.getRayTraceResult();
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        if (mark.battle().acceptsTrident(mark.actorId(), entityHit.getEntity())) return;
        event.setCanceled(true);
    }

    /** Drop the mark when its trident really leaves the level; this is the only removal-driven cleanup. */
    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (event.getEntity() instanceof ThrownTrident trident) MARKED.remove(trident);
    }

    /** Whether this exact projectile still carries an encounter's rule; fixtures and diagnostics only. */
    public static boolean marked(Entity projectile) {
        return projectile instanceof ThrownTrident trident && MARKED.containsKey(trident);
    }

    /** Live marked count for this encounter; fixtures and diagnostics only. */
    public static int markedFor(ServerBattle battle) {
        int count = 0;
        for (Mark mark : MARKED.values()) if (mark.battle() == battle) count++;
        return count;
    }

    /**
     * Settle one throw once its flight is over.
     *
     * <p>Called from the encounter's own resolution point, after the projectile tracker reports nothing
     * pending, so the real hit or the real block impact has already happened. The entity is then removed
     * instead of being left embedded: an unenchanted {@code DISALLOWED} trident that stayed in the ground for
     * its vanilla 60 seconds could only be an unexplained object in the world.</p>
     */
    public static void settle(ServerBattle battle, UUID actorId) {
        if (battle == null) return;
        Iterator<Map.Entry<ThrownTrident, Mark>> iterator = MARKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ThrownTrident, Mark> entry = iterator.next();
            Mark mark = entry.getValue();
            if (mark.battle() != battle) continue;
            if (actorId != null && !actorId.equals(mark.actorId())) continue;
            ThrownTrident trident = entry.getKey();
            iterator.remove();
            trident.discard();
        }
    }

    /** Discard and forget every marked trident this owner threw, before its control is released. */
    public static void clearOwner(ServerBattle battle, UUID actorId) {
        if (battle == null || actorId == null) return;
        settle(battle, actorId);
    }

    /** Discard and forget every trident this encounter still owns, when the encounter ends. */
    public static void clearBattle(ServerBattle battle) {
        if (battle == null) return;
        settle(battle, null);
    }

    /**
     * Whether the arc this drowned would really throw can reach that target from that spawn point.
     *
     * @param origin the real projectile spawn point: the shooter's X/Z and eye height minus 0.1
     */
    public static boolean nominalReaches(ServerLevel level, LivingEntity shooter, Vec3 origin,
            LivingEntity target) {
        if (level == null || shooter == null || origin == null || target == null) return false;
        double dx = target.getX() - origin.x;
        double dz = target.getZ() - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        TridentThrow.Velocity nominal = TridentThrow.nominal(dx,
                target.getY(TridentThrow.TARGET_AIM_FRACTION) - origin.y, dz, horizontal);
        if (nominal == null) return false;
        return BallisticShot.reaches(level, shooter, origin,
                new Vec3(nominal.x(), nominal.y(), nominal.z()), target, ThrowProfile.TRIDENT.gravity(), 0,
                ThrowProfile.TRIDENT.drag(), ThrowProfile.TRIDENT.waterDrag());
    }

    /** The native spawn point of the trident for one hypothetical feet position. */
    public static Vec3 spawnPoint(LivingEntity shooter, Vec3 feetAtCell) {
        return feetAtCell.add(0.0D, shooter.getEyeHeight() - 0.1D, 0.0D);
    }

    /** One native ranged attack through the interface the entity really implements. */
    public static void performNative(LivingEntity shooter, LivingEntity target) {
        if (shooter instanceof RangedAttackMob ranged && target != null) {
            ranged.performRangedAttack(target, 1.0F);
        }
    }
}
