package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The fireballs this encounter really fired: how they are built, and the two rules that keep them inside it.
 *
 * <p>Vanilla's own blaze fires its small fireballs from a goal the battle's NoAI lock already stops, and it has
 * no callable ranged primitive at all ({@code Blaze} is not a {@code RangedAttackMob}). A native equivalent
 * therefore means the same construction the goal performs - real {@link SmallFireball}, real owner, vanilla
 * spawn point, vanilla spread, vanilla acceleration/drag, vanilla direct damage and post-attack effects and
 * vanilla ignition - created here and nowhere else.</p>
 *
 * <p>Only these marked projectiles are subject to the battle's collision rule. A blaze in the wild, another
 * encounter's fireball and every other mod's projectile keep their untouched vanilla behaviour.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BlazeFireballs {

    /** Marked projectile -> the encounter that owes it a rule. Identity, never a name or a UUID string. */
    private static final Map<SmallFireball, Mark> MARKED = new IdentityHashMap<>();

    private BlazeFireballs() { }

    private record Mark(ServerBattle battle, UUID actorId, UUID targetId) { }

    /**
     * Build and spawn one fireball exactly as {@code Blaze.BlazeAttackGoal} does in this Minecraft version.
     *
     * <p>The spread is vanilla's: {@code Mth.triangle(offset, 2.297 * sqrt(sqrt(distanceToSqr)) / 2)} on the two
     * horizontal axes, an unspread vertical offset, then {@code normalize()} so the spread changes the angle
     * and never the launch speed. The spawn point is the blaze's own X/Z at half height plus half a block.</p>
     *
     * @return true when the entity really joined the world and is therefore marked
     */
    public static boolean launch(ServerLevel level, Blaze blaze, LivingEntity target,
                                 ServerBattle battle, UUID targetId) {
        if (level == null || blaze == null || target == null || battle == null) return false;
        double distanceSqr = blaze.distanceToSqr(target);
        double spread = Math.sqrt(Math.sqrt(distanceSqr)) * 0.5D;
        double dx = target.getX() - blaze.getX();
        double dy = target.getY(0.5D) - blaze.getY(0.5D);
        double dz = target.getZ() - blaze.getZ();
        Vec3 aim = new Vec3(blaze.getRandom().triangle(dx, 2.297D * spread), dy,
                blaze.getRandom().triangle(dz, 2.297D * spread));
        // A zero-length aim has no direction to normalize; refuse instead of spawning a fireball that never moves.
        if (aim.lengthSqr() < 1.0E-8D) return false;
        SmallFireball fireball = new SmallFireball(level, blaze, aim.normalize());
        fireball.setPos(fireball.getX(), blaze.getY(0.5D) + 0.5D, fireball.getZ());
        if (!blaze.isSilent()) level.levelEvent(null, 1018, blaze.blockPosition(), 0);
        // The mark exists for the whole create-to-join transaction, not from a successful return value: a native
        // join path can really add the entity and still throw afterwards, and that projectile must carry its
        // encounter's rule. If the world really refused it, the mark is dropped again in the same step.
        MARKED.put(fireball, new Mark(battle, blaze.getUUID(), targetId));
        boolean joined;
        try {
            joined = level.addFreshEntity(fireball);
        } finally {
            if (level.getEntity(fireball.getId()) != fireball) MARKED.remove(fireball);
        }
        return joined;
    }

    /** Forget one projectile's rule, without touching the entity itself. */
    public static void forget(Entity projectile) {
        if (projectile instanceof SmallFireball fireball) MARKED.remove(fireball);
    }

    /** Whether this exact projectile still carries this encounter's rule. */
    public static boolean marked(Entity projectile) {
        return projectile instanceof SmallFireball fireball && MARKED.containsKey(fireball);
    }

    /** Discard every marked fireball this owner fired, then forget the marks. Used before control is released. */
    public static void clearOwner(ServerBattle battle, UUID actorId) {
        if (battle == null || actorId == null) return;
        Iterator<Map.Entry<SmallFireball, Mark>> iterator = MARKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SmallFireball, Mark> entry = iterator.next();
            Mark mark = entry.getValue();
            if (mark.battle() != battle || !actorId.equals(mark.actorId())) continue;
            // Capture the key before removing: IdentityHashMap invalidates an iterator's entry on remove.
            SmallFireball fireball = entry.getKey();
            iterator.remove();
            fireball.discard();
        }
    }

    /** Discard and forget every fireball this encounter still owns. Used when the encounter ends. */
    public static void clearBattle(ServerBattle battle) {
        if (battle == null) return;
        Iterator<Map.Entry<SmallFireball, Mark>> iterator = MARKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SmallFireball, Mark> entry = iterator.next();
            if (entry.getValue().battle() != battle) continue;
            SmallFireball fireball = entry.getKey();
            iterator.remove();
            fireball.discard();
        }
    }

    /** Live marked count for this encounter; diagnostics and fixtures only. */
    public static int markedFor(ServerBattle battle) {
        int count = 0;
        for (Mark mark : MARKED.values()) if (mark.battle() == battle) count++;
        return count;
    }

    /**
     * The encounter's collision rule, checked before vanilla ever runs the hit.
     *
     * <p>Cancelling here is earlier than any damage event: the target is never ignited, never hurt, never given
     * post-attack enchantment effects, and a same-side, bystander, other-encounter, self or non-living body is
     * untouched instead of being repaired afterwards. A block hit is refused for the same reason - this
     * encounter does not set the world on fire - and the projectile is removed with its rule.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof SmallFireball fireball)) return;
        Mark mark = MARKED.get(fireball);
        if (mark == null) return;
        HitResult hit = event.getRayTraceResult();
        boolean legal = hit != null && hit.getType() == HitResult.Type.ENTITY
                && hit instanceof EntityHitResult entityHit
                && mark.battle().acceptsFireball(mark.actorId(), entityHit.getEntity());
        if (legal) {
            // Deliberately still marked. A later listener may cancel this impact - vanilla then keeps the
            // projectile flying - and the same fireball must stay under this encounter's rule for its next
            // collision. The mark is dropped when the entity really leaves the level, never before.
            return;
        }
        event.setCanceled(true);
        fireball.discard();
        MARKED.remove(fireball);
    }

    /**
     * Drop one mark when its projectile really leaves the level.
     *
     * <p>This is the single removal-driven cleanup, so a fireball that missed everything and hit the tracker's
     * flight cap, one that a cancelled impact left flying and then a later rule discarded, and a cleared or
     * timed-out volley all release their entity and their encounter here. Nothing is unmarked while the entity
     * can still collide.</p>
     */
    @SubscribeEvent
    public static void onLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof SmallFireball fireball) MARKED.remove(fireball);
    }

    /**
     * A controlled blaze never starts terrain fire, for as long as this encounter controls it.
     *
     * <p>{@code SmallFireball.onHitBlock} asks {@code EventHooks.canEntityGrief}, which fires this event on the
     * NeoForge bus with the gamerule as its starting answer. Denying it here narrows exactly one thing: the blaze
     * this encounter holds. The gamerule keeps its own meaning, other mobs keep theirs, and fire that was already
     * in the world is not touched.</p>
     */
    @SubscribeEvent
    public static void onGrief(EntityMobGriefingEvent event) {
        if (!(event.getEntity() instanceof Blaze blaze)) return;
        if (BattleManager.containing(blaze) == null) return;
        event.setCanGrief(false);
    }
}
