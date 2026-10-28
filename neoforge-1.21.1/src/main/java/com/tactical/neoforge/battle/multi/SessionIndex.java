package com.tactical.neoforge.battle.multi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of every live encounter: session id to encounter, and actor id to the one
 * encounter that currently owns that actor.
 *
 * <p>Ownership is exclusive. A player or NPC UUID belongs to at most one encounter, and no
 * call in this class ever moves an existing owner: {@link #bind} and {@link #bindAll} report
 * the conflict instead of overwriting it. Every mutator runs on the server thread, so a
 * check-then-bind sequence inside one call is atomic with respect to other callers.</p>
 *
 * <p>Entries are only dropped by the owning encounter (explicit {@link #unbind} with the
 * expected session, or {@link #remove} of that session). Ending one encounter therefore never
 * edits another encounter's bindings.</p>
 */
public final class SessionIndex<T> {

    /** Outcome of a claim, so a caller can tell its own new claim from a pre-existing one. */
    public enum Claim {
        /** The actor is now owned by the requested session because this call claimed it. */
        NEW,
        /** The requested session already owned this actor; nothing changed. */
        OWNED,
        /** Another session owns the actor. */
        CONFLICT,
        /** The session id is unknown here, so nothing could be claimed. */
        UNKNOWN_SESSION
    }

    private final Map<UUID, T> bySession = new LinkedHashMap<>();
    /** Claim order is preserved so a roster snapshot is reproducible for tests and logs. */
    private final Map<UUID, UUID> byActor = new LinkedHashMap<>();

    /**
     * Register a session. Re-registering the same session id with the same encounter is
     * idempotent; a different encounter under an existing id is refused so a second encounter
     * can never shadow a live one. Actor bindings are never touched here - use {@link #bind}.
     */
    public boolean register(UUID sessionId, T session) {
        if (sessionId == null || session == null) return false;
        T existing = bySession.get(sessionId);
        if (existing != null) return existing == session;
        bySession.put(sessionId, session);
        return true;
    }

    /** Drop a session and every binding that points at it. Identical bodies for an unknown id. */
    public void remove(UUID sessionId) {
        if (sessionId == null) return;
        if (bySession.remove(sessionId) == null) return;
        byActor.values().removeIf(sessionId::equals);
    }

    /**
     * Claim one actor for an already registered session, reporting whether this call is what
     * created the ownership.
     *
     * <p>A caller that must roll back has to know the difference: releasing an actor this call
     * did not claim would drop a legitimate member of the same session.</p>
     */
    public Claim claim(UUID sessionId, UUID actorId) {
        if (sessionId == null || actorId == null) return Claim.UNKNOWN_SESSION;
        if (!bySession.containsKey(sessionId)) return Claim.UNKNOWN_SESSION;
        UUID owner = byActor.get(actorId);
        if (owner != null) return owner.equals(sessionId) ? Claim.OWNED : Claim.CONFLICT;
        byActor.put(actorId, sessionId);
        return Claim.NEW;
    }

    /**
     * Claim one actor for an already registered session.
     *
     * @return false when the session is unknown or the actor belongs to a different session; in
     *         every failure case no binding changes. True covers both a fresh claim and an actor
     *         this session already owned.
     */
    public boolean bind(UUID sessionId, UUID actorId) {
        Claim claim = claim(sessionId, actorId);
        return claim == Claim.NEW || claim == Claim.OWNED;
    }

    /**
     * Claim several actors for one session atomically: either every free actor is bound, or
     * nothing changes. Actors already owned by this same session are kept, not re-bound.
     *
     * @return false when the session is unknown or at least one actor belongs to another
     *         session; no partial claim is left behind.
     */
    public boolean bindAll(UUID sessionId, Collection<UUID> actorIds) {
        if (sessionId == null || !bySession.containsKey(sessionId)) return false;
        if (actorIds == null || actorIds.isEmpty()) return true;
        for (UUID actorId : actorIds) {
            if (actorId == null) return false;
            UUID owner = byActor.get(actorId);
            if (owner != null && !owner.equals(sessionId)) return false;
        }
        for (UUID actorId : actorIds) byActor.putIfAbsent(actorId, sessionId);
        return true;
    }

    /**
     * Release one actor, but only from the session that is expected to own it.
     *
     * @return false when nothing was released, so an exit handler for a stale encounter can
     *         never unbind the actor from its current encounter.
     */
    public boolean unbind(UUID sessionId, UUID actorId) {
        if (sessionId == null || actorId == null) return false;
        UUID owner = byActor.get(actorId);
        if (owner == null || !owner.equals(sessionId)) return false;
        byActor.remove(actorId);
        return true;
    }

    public UUID ownerOf(UUID actorId) {
        return actorId == null ? null : byActor.get(actorId);
    }

    /** Snapshot of the actors currently owned by one session; empty for an unknown session. */
    public List<UUID> actorsOf(UUID sessionId) {
        if (sessionId == null) return List.of();
        List<UUID> result = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : byActor.entrySet()) {
            if (sessionId.equals(entry.getValue())) result.add(entry.getKey());
        }
        return List.copyOf(result);
    }

    public T findById(UUID sessionId) {
        return sessionId == null ? null : bySession.get(sessionId);
    }

    public T findFor(UUID actorId) {
        if (actorId == null) return null;
        UUID sessionId = byActor.get(actorId);
        return sessionId == null ? null : bySession.get(sessionId);
    }

    public boolean contains(UUID sessionId) { return sessionId != null && bySession.containsKey(sessionId); }

    public int sessionCount() { return bySession.size(); }

    /** Kept as a readable alias for {@link #sessionCount()}. */
    public int size() { return bySession.size(); }

    /**
     * Immutable snapshot of every live encounter, in registration order: a caller can never
     * mutate the index through this view, and a session removed elsewhere cannot leave a stale
     * actor binding behind.
     */
    public List<T> all() { return List.copyOf(bySession.values()); }

    /**
     * The single live encounter, or null.
     *
     * <p>This is the compatibility view for code that legitimately works on one encounter at a
     * time (fixtures, commands, single-member self tests). With two or more encounters running
     * it returns null on purpose: production routes must resolve their target from the acting
     * entity instead of picking an arbitrary first encounter.</p>
     */
    public T primary() { return bySession.size() == 1 ? bySession.values().iterator().next() : null; }

    public void clear() {
        bySession.clear();
        byActor.clear();
    }
}
