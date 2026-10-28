package com.tactical.neoforge.battle.multi;

import java.util.Objects;
import java.util.UUID;

/**
 * Freezes which encounter and which authoritative turn a client intent belongs to.
 *
 * <p>The server never trusts an actor identity claimed by a payload: it resolves the
 * real {@code ServerPlayer} from the connection, then requires this stamp to match the
 * live session UUID and the current turn epoch. A stale session or a stale turn is
 * rejected before any state is touched.</p>
 */
public record ActionStamp(UUID sessionId, long turnEpoch) {

    public ActionStamp {
        Objects.requireNonNull(sessionId, "sessionId");
        if (turnEpoch < 0L) throw new IllegalArgumentException("turnEpoch must be non-negative");
    }

    public static ActionStamp of(UUID sessionId, long turnEpoch) {
        return new ActionStamp(sessionId, turnEpoch);
    }

    /** True only for the exact live session and the exact live turn generation. */
    public boolean matches(UUID expectedSession, long expectedEpoch) {
        return expectedSession != null && turnEpoch == expectedEpoch && sessionId.equals(expectedSession);
    }

    public boolean sameSession(ActionStamp other) {
        return other != null && sessionId.equals(other.sessionId);
    }
}
