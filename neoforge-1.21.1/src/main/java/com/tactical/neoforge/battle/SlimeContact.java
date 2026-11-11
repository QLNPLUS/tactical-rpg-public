package com.tactical.neoforge.battle;

import com.tactical.neoforge.mixin.SlimeDealDamageInvoker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * The one native contact an encounter may borrow for this family, and the receipt for it.
 *
 * <p>Vanilla's slime damage is not a melee: its only entry is {@code playerTouch} (and a push against an iron
 * golem) calling the protected {@code dealDamage}, which then proves the body is alive, that the target really
 * intersects the native attack box and that the line of sight is clear, and only then calls {@code hurt} with
 * its own sound and enchanting post-effects. The {@code NoAI} gate sits in {@code isDealsDamage}, which is what
 * the two callers ask - not inside {@code dealDamage}. A controlled body therefore has to have this entry
 * driven for it, and a body that has never damaged anything natively must not gain the ability to.</p>
 *
 * <p>Two things are deliberately not done here. The damage number is never written by this mod - it is whatever
 * vanilla's {@code getAttackDamage} (a magma cube's {@code +2} included), the target's armour, the difficulty
 * scaling function of the damage type and every listener decide. And the result is never guessed from a health
 * delta: the very {@code hurt} call inside {@code dealDamage} reports its own boolean back through the mixin,
 * so an absorbed hit that changes no health is still a hit and a cancelled one is still not.</p>
 */
public final class SlimeContact {

    /** The live native call whose result is being awaited, one per thread and never nested. */
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private static final class Attempt {
        final Slime actor;
        final LivingEntity target;
        boolean closed;
        boolean hurt;

        private Attempt(Slime actor, LivingEntity target) {
            this.actor = actor;
            this.target = target;
        }
    }

    private SlimeContact() { }

    /**
     * Whether this exact body may hurt a target at all, from its real type and size.
     *
     * <p>A size-1 slime really does not damage anything in vanilla ({@code isDealsDamage} requires
     * {@code !isTiny()}), while a size-1 magma cube really does - it overrides that answer with
     * {@code isEffectiveAi()} only. Reading it from the body keeps the difference instead of flattening the
     * family into one rule.</p>
     */
    public static boolean sizeCanHurt(LivingEntity actor) {
        if (actor instanceof MagmaCube) return true;
        if (actor instanceof Slime slime) return !slime.isTiny();
        return false;
    }

    /** Vanilla's own melee reach, applied to that body at those feet: the target's hitbox must intersect it. */
    public static boolean attackBoxReaches(LivingEntity actor, Vec3 feet, LivingEntity target) {
        if (actor == null || feet == null || target == null) return false;
        AABB attackBox = BodyGeometry.attackBoxAt(actor, feet);
        return attackBox != null && attackBox.intersects(target.getHitbox());
    }

    /**
     * Line of sight asked from the body's <em>real</em> eye at those feet.
     *
     * <p>Asking from a fixed height would flatter a short body and refuse a tall one; the eye is what vanilla's
     * own {@code hasLineOfSight} ends up using, and the real feet are where the body would really stand.</p>
     */
    public static boolean eyeLineOfSight(ServerLevel level, LivingEntity viewer, Vec3 feet, LivingEntity target) {
        if (level == null || viewer == null || feet == null || target == null) return false;
        Vec3 start = new Vec3(feet.x, feet.y + viewer.getEyeHeight(), feet.z);
        Vec3 end = target.getEyePosition();
        var hit = level.clip(new net.minecraft.world.level.ClipContext(start, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, viewer));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        return hit.getLocation().distanceToSqr(start) >= end.distanceToSqr(start) - 0.05D;
    }

    /**
     * Drive exactly one native contact and report the same boolean vanilla's own {@code hurt} returned.
     *
     * <p>The scope is opened for one call and cleared in a {@code finally}, so a refused, cancelled or throwing
     * call can never leave a receipt behind for a later, unrelated hit to fill in.</p>
     */
    public static boolean strike(Slime actor, LivingEntity target) {
        Objects.requireNonNull(actor, "contact actor");
        Objects.requireNonNull(target, "contact target");
        Attempt previous = CURRENT.get();
        Attempt attempt = new Attempt(actor, target);
        CURRENT.set(attempt);
        try {
            ((SlimeDealDamageInvoker) actor).tactical$dealDamage(target);
            return attempt.hurt;
        } finally {
            attempt.closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /**
     * Called by the mixin with the result of the native {@code hurt} call, inside {@link #strike}.
     *
     * <p>Anything outside an open scope - a wild slime's own contact, a push against an iron golem, another
     * encounter's body - is ignored here and keeps whatever vanilla decided.</p>
     */
    public static void report(Slime actor, LivingEntity receiver, boolean hurt) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || attempt.closed) return;
        if (attempt.actor != actor || attempt.target != receiver) return;
        if (hurt) attempt.hurt = true;
    }

    /** Whether a native contact is being driven right now, for diagnostics and fixture assertions only. */
    public static boolean inScope() {
        Attempt attempt = CURRENT.get();
        return attempt != null && !attempt.closed;
    }
}
