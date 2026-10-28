package com.tactical.neoforge.battle.multi;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The server's authoritative membership generation: one monotonic counter per player.
 *
 * <p>It is deliberately independent of the encounter's own {@code turnEpoch}. A turn epoch says
 * <em>which turn of this encounter</em> a packet describes; the membership generation says
 * <em>which time this connection became part of an encounter</em>. The same encounter can be entered
 * twice by the same player (leave and re-join), and only the generation distinguishes the second entry
 * from a delayed packet of the first, so every display payload is stamped with the pair
 * {@code (generation, sessionId)} and the client refuses anything that is not exactly its current
 * active membership.</p>
 *
 * <p>Counters are monotonic for the life of this server and survive a player's logout, because the
 * client keeps the highest generation it saw for one connection and refuses older callbacks after a
 * reconnect. A restarted server publishes a fresh {@link #epoch()}, which tells clients that the
 * counter really did restart instead of a stale packet arriving.</p>
 */
public final class MembershipGenerations {

    /** One connection's authoritative membership. {@code generation == 0} means "never stamped". */
    public record Membership(long generation, UUID sessionId, boolean active) {
        public static final Membership NONE = new Membership(0L, null, false);

        public boolean isActive() {
            return active && sessionId != null;
        }
    }

    private static final Map<UUID, Membership> CURRENT = new HashMap<>();
    private static final Map<UUID, Long> TOP = new HashMap<>();
    /**
     * Identity of one server instance. It is rotated on server stop, because the counters restart with
     * the next instance even though this JVM (and therefore a {@code static final} value) survives:
     * leaning on a constant would make a second world in the same client JVM publish generation 1, 2, …
     * under an unchanged epoch, and the client's remembered closed generation would refuse its own new
     * encounter.
     */
    private static volatile long EPOCH = freshEpoch();

    private MembershipGenerations() { }

    private static long freshEpoch() {
        return Math.max(1L, ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE));
    }

    public static long epoch() {
        return EPOCH;
    }

    /** Start a membership: the caller has already committed the member and its resources. */
    public static Membership activate(ServerPlayer player, UUID sessionId) {
        if (player == null) return Membership.NONE;
        if (sessionId == null) throw new IllegalArgumentException("An active membership must name its encounter");
        return activate(player.getUUID(), sessionId);
    }

    /** The same transition without a live player handle; used by the lifecycle tests. */
    static Membership activate(UUID playerId, UUID sessionId) {
        if (playerId == null) return Membership.NONE;
        if (sessionId == null) throw new IllegalArgumentException("An active membership must name its encounter");
        return next(playerId, sessionId, true);
    }

    /**
     * End a membership, including a death or a disconnect.
     *
     * @param sessionId the encounter left; null publishes a bare lifecycle reset.
     */
    public static Membership deactivate(ServerPlayer player, UUID sessionId) {
        if (player == null) return Membership.NONE;
        return deactivate(player.getUUID(), sessionId);
    }

    /** The same transition without a live player handle; used by the lifecycle tests. */
    static Membership deactivate(UUID playerId, UUID sessionId) {
        if (playerId == null) return Membership.NONE;
        return next(playerId, sessionId, false);
    }

    /** This player's current membership; {@link Membership#NONE} when it has none. */
    public static Membership current(ServerPlayer player) {
        return player == null ? Membership.NONE : current(player.getUUID());
    }

    public static Membership current(UUID playerId) {
        if (playerId == null) return Membership.NONE;
        return CURRENT.getOrDefault(playerId, Membership.NONE);
    }

    /** Generation used to stamp a payload for this recipient right now. */
    public static long generationOf(ServerPlayer player) {
        return current(player).generation();
    }

    /**
     * Drop the published membership of a connection that is gone.
     *
     * <p>The counter itself is kept, so the next connection's transitions stay above anything the
     * client already saw.</p>
     */
    public static void forget(UUID playerId) {
        if (playerId != null) CURRENT.remove(playerId);
    }

    /**
     * End every published membership while this server process keeps running.
     *
     * <p>A battle reset is not the end of the server: the epoch stays the same, so the counters must
     * keep increasing. Clearing them would make the next entry's generation lower than the exit the
     * client already applied, and that client would refuse its own new encounter.</p>
     */
    public static void endMemberships() {
        CURRENT.clear();
    }

    /**
     * Server stop: this instance' epoch and counters are meaningless afterwards.
     *
     * <p>This is the seam the server-stopped event calls. It clears both maps <em>and</em> rotates the
     * epoch, so the next server in the same JVM starts at generation 1 under a new identity. Without the
     * rotation a client that had already closed generation 2 in the previous world would refuse the new
     * world's generation 1; with it the client treats the counter as fresh, and a packet still queued
     * from the stopped server carries an epoch that no longer matches the live instance, so a client
     * that has already latched the new epoch refuses it. The reverse arrival order (a stale higher
     * generation landing before the new instance's first packet) is not addressed here.</p>
     */
    public static void onServerStopped() {
        CURRENT.clear();
        TOP.clear();
        long previous = EPOCH;
        long rotated = freshEpoch();
        while (rotated == previous) rotated = freshEpoch();
        EPOCH = rotated;
    }

    private static Membership next(UUID playerId, UUID sessionId, boolean active) {
        long generation = TOP.merge(playerId, 1L, Long::sum);
        Membership membership = new Membership(generation, sessionId, active);
        CURRENT.put(playerId, membership);
        return membership;
    }
}
