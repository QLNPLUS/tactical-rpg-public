package com.tactical.neoforge.client;

import java.util.Objects;
import java.util.UUID;

/**
 * The client's membership state machine: the only thing that may change which encounter is shown.
 *
 * <p>The server owns membership. While a connection lives it assigns every entry and exit of every
 * player a monotonically increasing <em>generation</em>, and publishes that generation together with
 * the encounter id on every payload that can change what this client displays. Therefore:</p>
 * <ul>
 *   <li><b>Higher generation</b> - the one legal transition; it defines the new membership
 *       (an exit keeps the encounter id it left, an entry must name one).</li>
 *   <li><b>Equal generation</b> - only a replay of the same membership: same encounter and same
 *       active flag is idempotent, anything else is a contradiction and is refused.</li>
 *   <li><b>Lower generation</b> - a delayed payload of a membership already replaced; refused, so the
 *       client never goes backwards and the same encounter entered twice is distinguished by
 *       generation alone rather than by a client-side guess.</li>
 *   <li><b>Nothing to compare</b> - a payload with generation {@code 0} was never stamped; it cannot
 *       authorize anything.</li>
 * </ul>
 *
 * <p>Nothing on the client may arm a transition: clicking a row, receiving "joined" or a page
 * refreshing are UI facts, not authority. Only a stamped transition does that, which is why a client
 * that has just left an encounter cannot be tricked back into it by any replay.</p>
 *
 * <p>A new connection starts from scratch ({@link #beginConnection()}) but remembers the highest
 * generation it had, so a payload still queued from the previous connection is refused. A restarted
 * server publishes a fresh epoch; its generation counter is unrelated, so the remembered one is not
 * applied to it.</p>
 */
public final class BattleMembership {

    /** Why a payload was applied or refused; kept explicit so every path is testable. */
    public enum Decision {
        /** A newer generation: this defines the new membership. */
        ACCEPT_TRANSITION,
        /** The same membership again: same encounter, same state. */
        ACCEPT_IDEMPOTENT,
        /** Never stamped by the server; nothing can be authorized by it. */
        REJECT_UNBOUND,
        /** Belongs to a membership this client already replaced. */
        REJECT_OLD_GENERATION,
        /** The same generation claims a different encounter or state: a contradiction. */
        REJECT_CONFLICT,
        /** Another server instance's stamp while this connection already accepted one. */
        REJECT_OTHER_SERVER,
        /** An entry that does not name its encounter. */
        REJECT_MALFORMED
    }

    /** One accepted membership: which server, which generation, which encounter, entered or left. */
    public record Scope(long serverEpoch, long generation, UUID sessionId, boolean active) {
        public static final Scope NONE = new Scope(0L, 0L, null, false);

        public boolean isActive() {
            return active && sessionId != null;
        }
    }

    private static Scope current = Scope.NONE;
    /** Highest generation of the connection this client last had, so its late payloads are refused. */
    private static long closedGeneration;
    private static long closedEpoch;
    private static long revision;

    private BattleMembership() { }

    public static Scope current() {
        return current;
    }

    public static long revision() {
        return revision;
    }

    /** True when this client currently owns an entered encounter. */
    public static boolean active() {
        return current.isActive();
    }

    public static UUID sessionId() {
        return current.sessionId();
    }

    public static long generation() {
        return current.generation();
    }

    public static long closedGeneration() {
        return closedGeneration;
    }

    /**
     * Offer one server-stamped membership transition.
     *
     * @return {@link Decision#ACCEPT_TRANSITION} for the only case that changes membership,
     *         {@link Decision#ACCEPT_IDEMPOTENT} for a repeated identical one, otherwise a refusal.
     */
    public static Decision transition(long serverEpoch, long generation, UUID sessionId, boolean active) {
        if (generation <= 0) return Decision.REJECT_UNBOUND;
        if (active && sessionId == null) return Decision.REJECT_MALFORMED;
        // Traffic of the connection this client already closed cannot come back.
        if (closedGeneration > 0 && serverEpoch == closedEpoch && generation <= closedGeneration) {
            return Decision.REJECT_OLD_GENERATION;
        }
        if (current.generation() > 0 && serverEpoch != current.serverEpoch()) {
            // A membership is latched for one server instance. Another instance's counter is unrelated,
            // so its payload is refused instead of resetting a state that a live connection owns.
            return Decision.REJECT_OTHER_SERVER;
        }
        if (generation < current.generation()) return Decision.REJECT_OLD_GENERATION;
        if (generation == current.generation()) {
            boolean sameEncounter = Objects.equals(sessionId, current.sessionId());
            if (sameEncounter && active == current.active()) return Decision.ACCEPT_IDEMPOTENT;
            return Decision.REJECT_CONFLICT;
        }
        current = new Scope(serverEpoch, generation, sessionId, active);
        revision++;
        return Decision.ACCEPT_TRANSITION;
    }

    /** True only for the two accepting decisions. */
    public static boolean decided(Decision decision) {
        return decision == Decision.ACCEPT_TRANSITION || decision == Decision.ACCEPT_IDEMPOTENT;
    }

    /**
     * True when an auxiliary payload belongs to the membership currently shown.
     *
     * <p>Journal, skills, warnings and attack range are display-only: they may be dropped, so they are
     * accepted only for the exact current membership. A packet that arrives before the transition that
     * would authorize it is dropped, and the server resends the full snapshot after the transition.</p>
     */
    public static boolean matches(long serverEpoch, long generation, UUID sessionId) {
        return active()
                && serverEpoch == current.serverEpoch()
                && generation == current.generation()
                && Objects.equals(sessionId, current.sessionId());
    }

    /**
     * A new connection: forget which encounter is shown, remember the generation it last saw.
     *
     * <p>Remembering the top generation is what refuses payloads still queued from the previous
     * connection; the server's own counter continues above it, so a legitimate re-entry is still a
     * newer generation.</p>
     */
    public static void beginConnection() {
        if (current.generation() > 0) {
            if (closedEpoch == current.serverEpoch()) {
                closedGeneration = Math.max(closedGeneration, current.generation());
            } else {
                // A different server instance never compares against the previous one's counter.
                closedEpoch = current.serverEpoch();
                closedGeneration = current.generation();
            }
        }
        current = Scope.NONE;
        revision++;
    }

    /** Fixture-only: forget everything, including the connection boundary. */
    public static void reset() {
        beginConnection();
        closedEpoch = 0L;
        closedGeneration = 0L;
        current = Scope.NONE;
        revision++;
    }
}
