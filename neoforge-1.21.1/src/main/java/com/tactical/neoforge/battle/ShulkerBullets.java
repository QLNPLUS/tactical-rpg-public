package com.tactical.neoforge.battle;

import net.minecraft.world.entity.projectile.ShulkerBullet;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The immutable ownership of one native shulker bullet this encounter really fired.
 *
 * <p>A native bullet curves. Its owner can die, its target can leave the field, and the encounter it was fired
 * into can end - and the bullet keeps flying, because vanilla's own tick knows nothing about any of that. What
 * the encounter must never do is keep answering for a shot it no longer owns, or answer for a shot somebody else
 * fired.</p>
 *
 * <p>So each shot is marked once, at the moment the world really took it, with the four facts that make it ours:
 * the session it was fired in, the actor, the locked target and the turn epoch. The mark is never rewritten.
 * Every later question ("may this bullet hurt this body?") resolves the mark and then proves both identities are
 * still the same live participants of the same session - a respawned player with the same UUID, a replacement
 * mob or another encounter's copy all fail that test, and a failed test fails <em>closed</em>: the shot is
 * cancelled rather than left persuasive.</p>
 *
 * <p>Identity of the projectile is an identity comparison, never an id: two bullets with the same entity id in
 * different ticks are not the same bullet.</p>
 */
public final class ShulkerBullets {

    /** One shot's immutable ownership. */
    public record Owner(UUID sessionId, UUID actorId, UUID targetId, int turnEpoch) {}

    private static final Map<ShulkerBullet, Owner> MARKS = new IdentityHashMap<>();
    /** Reverse index so an actor or a target leaving can drop its own shots in bounded time. */
    private static final Map<UUID, Integer> ACTOR_SHOTS = new HashMap<>();
    private static final Map<UUID, Integer> TARGET_SHOTS = new HashMap<>();

    private ShulkerBullets() {}

    /** Mark one shot at the moment the world really registered it. */
    public static void mark(ShulkerBullet bullet, Owner owner) {
        if (bullet == null || owner == null) return;
        if (MARKS.putIfAbsent(bullet, owner) != null) return;
        bump(ACTOR_SHOTS, owner.actorId(), 1);
        bump(TARGET_SHOTS, owner.targetId(), 1);
    }

    /** The mark of that exact projectile, or {@code null} when this encounter never fired it. */
    @javax.annotation.Nullable
    public static Owner markOf(ShulkerBullet bullet) {
        return bullet == null ? null : MARKS.get(bullet);
    }

    /** Whether this exact projectile is one of this encounter's marked shots. */
    public static boolean owns(ShulkerBullet bullet) {
        return bullet != null && MARKS.containsKey(bullet);
    }

    /** Drop one shot: it resolved, was cancelled, timed out or its body is gone. */
    public static void forget(ShulkerBullet bullet) {
        Owner owner = bullet == null ? null : MARKS.remove(bullet);
        if (owner == null) return;
        drop(ACTOR_SHOTS, owner.actorId());
        drop(TARGET_SHOTS, owner.targetId());
    }

    /** Drop every shot fired by that actor: a departed or dead actor answers for nothing. */
    public static int forgetActor(UUID actorId) {
        if (actorId == null || count(ACTOR_SHOTS, actorId) <= 0) return 0;
        int dropped = 0;
        for (Map.Entry<ShulkerBullet, Owner> entry : new IdentityHashMap<>(MARKS).entrySet()) {
            if (!actorId.equals(entry.getValue().actorId())) continue;
            entry.getKey().discard();
            forget(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /** Drop every shot aimed at that target: a target that left cannot be hunted for free. */
    public static int forgetTarget(UUID targetId) {
        if (targetId == null || count(TARGET_SHOTS, targetId) <= 0) return 0;
        int dropped = 0;
        for (Map.Entry<ShulkerBullet, Owner> entry : new IdentityHashMap<>(MARKS).entrySet()) {
            if (!targetId.equals(entry.getValue().targetId())) continue;
            entry.getKey().discard();
            forget(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /** Drop every shot of that session: encounter teardown. */
    public static int forgetSession(UUID sessionId) {
        if (sessionId == null) return 0;
        int dropped = 0;
        for (Map.Entry<ShulkerBullet, Owner> entry : new IdentityHashMap<>(MARKS).entrySet()) {
            if (!sessionId.equals(entry.getValue().sessionId())) continue;
            entry.getKey().discard();
            forget(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /** How many marked shots this encounter still answers for. */
    public static int size() {
        return MARKS.size();
    }

    /** Diagnostic form used by the fixture and by the trace. */
    public static String describe() {
        if (MARKS.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<ShulkerBullet, Owner> entry : MARKS.entrySet()) {
            if (builder.length() > 0) builder.append(',');
            builder.append(entry.getKey().getId()).append(':').append(entry.getValue().targetId());
        }
        return builder.toString();
    }

    private static void bump(Map<UUID, Integer> counts, UUID id, int delta) {
        if (id == null) return;
        counts.merge(id, delta, Integer::sum);
    }

    private static void drop(Map<UUID, Integer> counts, UUID id) {
        if (id == null) return;
        Integer count = counts.get(id);
        if (count == null) return;
        if (count <= 1) counts.remove(id);
        else counts.put(id, count - 1);
    }

    private static int count(Map<UUID, Integer> counts, UUID id) {
        Integer count = id == null ? null : counts.get(id);
        return count == null ? 0 : count;
    }
}
