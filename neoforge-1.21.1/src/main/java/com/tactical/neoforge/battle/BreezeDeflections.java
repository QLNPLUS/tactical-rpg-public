package com.tactical.neoforge.battle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The real deflector of one projectile a controlled breeze turned around, and nothing else.
 *
 * <p>Vanilla's passive deflection is a fact about the projectile, not about its owner:
 * {@code Projectile.hitTargetOrDeflectSelf} calls {@code deflect(deflection, hitEntity, this.getOwner(), false)},
 * so the projectile keeps the owner it had and only its velocity and heading change. A breeze therefore never
 * becomes the owner of a deflected arrow, and this class never pretends it does.</p>
 *
 * <p>What the encounter does need is a separate, narrow authority for the one question vanilla cannot answer:
 * may this already-reversed shot affect that body? The original billing receipt, the firing session and the
 * projectile's bounded lifetime stay exactly as they were; only the relation filter consults the deflector. A
 * projectile no marked encounter fired has no mark to begin with, so it can never borrow this authority - the
 * per-kind impact guards only ask this class after they have already proved the projectile is theirs.</p>
 */
public final class BreezeDeflections {

    /** Which controlled breeze really turned that projectile around, inside which encounter. */
    public record Reflection(UUID sessionId, UUID deflectorId) {}

    private static final Map<Projectile, Reflection> REFLECTIONS = new IdentityHashMap<>();

    private BreezeDeflections() {}

    /**
     * Record one real native deflection: the deflector that really turned the shot around last owns it.
     *
     * <p>Deliberately <em>not</em> "first writer wins". A shot can be reversed more than once - a reversed arrow
     * that meets a second controlled breeze is reversed again - and after that second real deflection the bodies it
     * may reach are the second body's relations, not the first's. Freezing the first deflector would silently give
     * the shot authority it no longer earns, exactly like refusing to update it would deny the authority it does.
     * The original owner, the original price and the flight's own lifetime are untouched here: this record is a
     * relation authority only, so replacing it changes no billing and no time.</p>
     */
    public static void record(Projectile projectile, UUID sessionId, UUID deflectorId) {
        if (projectile == null || sessionId == null || deflectorId == null) return;
        Reflection existing = REFLECTIONS.get(projectile);
        if (existing != null && existing.sessionId().equals(sessionId)
                && existing.deflectorId().equals(deflectorId)) {
            return;
        }
        REFLECTIONS.put(projectile, new Reflection(sessionId, deflectorId));
    }

    /** The recorded reflection of that exact projectile, or {@code null} when it was never deflected. */
    @javax.annotation.Nullable
    public static Reflection of(Projectile projectile) {
        return projectile == null ? null : REFLECTIONS.get(projectile);
    }

    /** Drop one projectile's reflection: it really left the level or its flight ended. */
    public static void forget(Projectile projectile) {
        if (projectile != null) REFLECTIONS.remove(projectile);
    }

    /**
     * Whether the velocity a deflection really produced is the native reverse.
     *
     * <p>Deliberately asked of the <em>result</em> instead of the deflection object: a breeze answers vanilla's
     * deflection request with its own wrapper that plays the native sound and then applies
     * {@code ProjectileDeflection.REVERSE}, so the object any listener sees is not that constant. What is checked
     * here is what the game really did - the new velocity is the old one scaled by
     * {@link com.tactical.core.combat.BreezeRules#REVERSE_VELOCITY_SCALE}. A deflection of another kind (an
     * aim deflection, a momentum deflection, a mod's own) fails that comparison and is never recorded, so no
     * projectile gains a relation filter it did not earn, including a projectile that was already moving backwards
     * by chance.</p>
     */
    public static boolean isNativeReverse(net.minecraft.world.phys.Vec3 before, net.minecraft.world.phys.Vec3 after) {
        if (before == null || after == null) return false;
        double lengthSq = before.x * before.x + before.y * before.y + before.z * before.z;
        if (lengthSq <= com.tactical.core.combat.BreezeRules.NO_DISPLACEMENT) return false;
        double scale = com.tactical.core.combat.BreezeRules.REVERSE_VELOCITY_SCALE;
        double tolerance = 1.0E-6D * Math.sqrt(lengthSq) + 1.0E-9D;
        return Math.abs(after.x - before.x * scale) <= tolerance
                && Math.abs(after.y - before.y * scale) <= tolerance
                && Math.abs(after.z - before.z * scale) <= tolerance;
    }

    /** Live count, for fixtures and diagnostics only. */
    public static int size() {
        return REFLECTIONS.size();
    }

    /** Diagnostic form used by the fixture and by the trace. */
    public static String describe() {
        if (REFLECTIONS.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<Projectile, Reflection> entry : REFLECTIONS.entrySet()) {
            if (builder.length() > 0) builder.append(',');
            builder.append(entry.getKey().getId()).append('@').append(entry.getValue().deflectorId());
        }
        return builder.toString();
    }

    /** Drop everything of one session: encounter teardown. */
    public static int forgetSession(UUID sessionId) {
        if (sessionId == null) return 0;
        int dropped = 0;
        for (Map.Entry<Projectile, Reflection> entry : new IdentityHashMap<>(REFLECTIONS).entrySet()) {
            if (!sessionId.equals(entry.getValue().sessionId())) continue;
            REFLECTIONS.remove(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /**
     * Drop the authority of one departing deflector, and the shots that only it was guarding.
     *
     * <p>A partial exit - one body dying, leaving, or being replaced while the encounter and other members go on -
     * must not leave a reversed shot behind that nobody answers for. Forgetting the record alone would hand that
     * shot back to the old shooter's relation rule, which is a rule the reversal deliberately replaced; so the shot
     * really turns around by this body is removed with the authority, and nothing it might have hit later can
     * happen. Only records of <em>this</em> deflector are touched: another body's reversed shots and another
     * encounter's flights keep theirs.</p>
     *
     * @return how many projectile records this departure closed
     */
    public static int forgetDeflector(UUID deflectorId) {
        if (deflectorId == null) return 0;
        int dropped = 0;
        for (Map.Entry<Projectile, Reflection> entry : new IdentityHashMap<>(REFLECTIONS).entrySet()) {
            if (!deflectorId.equals(entry.getValue().deflectorId())) continue;
            REFLECTIONS.remove(entry.getKey());
            entry.getKey().discard();
            dropped++;
        }
        return dropped;
    }

    /** Whether that level still owns the projectile; a discarded shot answers for nothing. */
    public static boolean stillFlying(Projectile projectile, ServerLevel level) {
        if (projectile == null || level == null) return false;
        if (projectile.isRemoved() || !projectile.isAlive()) return false;
        return projectile.level() == level;
    }

    /** Convenience for the relation guard: the living body behind that id, or {@code null}. */
    @javax.annotation.Nullable
    public static LivingEntity body(LivingEntity candidate) {
        return candidate != null && candidate.isAlive() && !candidate.isRemoved() ? candidate : null;
    }
}
