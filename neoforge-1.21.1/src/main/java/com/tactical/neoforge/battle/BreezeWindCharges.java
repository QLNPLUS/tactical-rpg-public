package com.tactical.neoforge.battle;

import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The immutable ownership of one native breeze wind charge this encounter really fired.
 *
 * <p>A wind charge flies on its own, its burst happens wherever it lands, and it can be destroyed or outlive the
 * encounter that fired it. What the encounter must never do is keep answering for a charge it no longer owns, or
 * answer for a charge somebody else fired - a player's own wind charge, a wild breeze's charge, another
 * encounter's charge. So each one is marked once, at the moment the world really registered it, with the four
 * facts that make it ours: the session it was fired in, the actor, the locked target and the turn epoch.</p>
 *
 * <p>The mark is never rewritten, and identity is the projectile object itself, never an entity id: two charges
 * sharing an id in different ticks are not the same charge.</p>
 */
public final class BreezeWindCharges {

    /** One charge's immutable ownership. */
    public record Owner(UUID sessionId, UUID actorId, UUID targetId, int turnEpoch) {}

    private static final Map<BreezeWindCharge, Owner> MARKS = new IdentityHashMap<>();
    private static final Map<UUID, Integer> ACTOR_CHARGES = new HashMap<>();
    private static final Map<UUID, Integer> TARGET_CHARGES = new HashMap<>();

    private BreezeWindCharges() {}

    /** Mark one charge at the moment the world really registered it. */
    public static void mark(BreezeWindCharge charge, Owner owner) {
        if (charge == null || owner == null) return;
        if (MARKS.putIfAbsent(charge, owner) != null) return;
        bump(ACTOR_CHARGES, owner.actorId(), 1);
        bump(TARGET_CHARGES, owner.targetId(), 1);
    }

    /** The mark of that exact charge, or {@code null} when this encounter never fired it. */
    @javax.annotation.Nullable
    public static Owner markOf(BreezeWindCharge charge) {
        return charge == null ? null : MARKS.get(charge);
    }

    /** Whether this exact charge is one of this encounter's marked charges. */
    public static boolean owns(BreezeWindCharge charge) {
        return charge != null && MARKS.containsKey(charge);
    }

    /** Drop one charge: it burst, was cancelled, timed out or its body is gone. */
    public static void forget(BreezeWindCharge charge) {
        Owner owner = charge == null ? null : MARKS.remove(charge);
        if (owner == null) return;
        drop(ACTOR_CHARGES, owner.actorId());
        drop(TARGET_CHARGES, owner.targetId());
    }

    /** Drop every charge fired by that actor: a departed or dead actor answers for nothing. */
    public static int forgetActor(UUID actorId) {
        if (actorId == null || count(ACTOR_CHARGES, actorId) <= 0) return 0;
        int dropped = 0;
        for (Map.Entry<BreezeWindCharge, Owner> entry : new IdentityHashMap<>(MARKS).entrySet()) {
            if (!actorId.equals(entry.getValue().actorId())) continue;
            entry.getKey().discard();
            forget(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /** Drop every charge of that session: encounter teardown. */
    public static int forgetSession(UUID sessionId) {
        if (sessionId == null) return 0;
        int dropped = 0;
        for (Map.Entry<BreezeWindCharge, Owner> entry : new IdentityHashMap<>(MARKS).entrySet()) {
            if (!sessionId.equals(entry.getValue().sessionId())) continue;
            entry.getKey().discard();
            forget(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /**
     * Drop every charge fired at that target: a target that left is owed nothing.
     *
     * <p>A partial exit of the locked target - death, departure, replacement - while the shot is still in the air
     * is not the same thing as the whole encounter ending. Forgetting the mark alone would leave a live projectile
     * that no longer answers to any guard, so the charges of exactly that target are removed with their marks; the
     * other charges of the same breeze, and every other encounter's, are untouched.</p>
     *
     * @return how many charges this departure closed
     */
    public static int forgetTarget(UUID targetId) {
        if (targetId == null || count(TARGET_CHARGES, targetId) <= 0) return 0;
        int dropped = 0;
        for (Map.Entry<BreezeWindCharge, Owner> entry : new IdentityHashMap<>(MARKS).entrySet()) {
            if (!targetId.equals(entry.getValue().targetId())) continue;
            entry.getKey().discard();
            forget(entry.getKey());
            dropped++;
        }
        return dropped;
    }

    /** How many marked charges this encounter still answers for. */
    public static int size() {
        return MARKS.size();
    }

    /** Diagnostic form used by the fixture and by the trace. */
    public static String describe() {
        if (MARKS.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<BreezeWindCharge, Owner> entry : MARKS.entrySet()) {
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
