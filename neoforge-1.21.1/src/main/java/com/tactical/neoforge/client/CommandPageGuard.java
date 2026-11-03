package com.tactical.neoforge.client;

import java.util.Objects;
import java.util.UUID;

/**
 * The actor/session binding of one command page.
 *
 * <p>A command page - the aiming page, the facing page, the companion skill page - is opened for exactly one
 * acting body in exactly one turn. The server already refuses an order that belongs to another turn, but a
 * page left open across an actor switch would send the click under the <em>new</em> turn's stamp, so the
 * refusal would never happen and the wrong body would be ordered. This binding is what closes that page
 * instead: it records the session, the turn generation and the acting entity at open time, and the page is
 * closed (sending nothing) the moment the live state no longer matches.</p>
 *
 * <p>The decision is a pure comparison so it can be pinned without a running game.</p>
 */
public final class CommandPageGuard {

    private static UUID sessionId;
    private static long turnEpoch;
    private static int actorEntityId = -1;
    private static boolean bound;

    private CommandPageGuard() {
    }

    /** Bind the page that is being opened to the actor acting right now. */
    public static void bind() {
        sessionId = BattleClientState.sessionId;
        turnEpoch = BattleClientState.turnEpoch;
        actorEntityId = BattleClientState.commandEntityId();
        bound = sessionId != null && actorEntityId >= 0;
    }

    /** Drop the binding; a page that closes for any reason leaves nothing behind. */
    public static void clear() {
        sessionId = null;
        turnEpoch = 0L;
        actorEntityId = -1;
        bound = false;
    }

    /** True while the live state is still the one this page was opened for. */
    public static boolean matches() {
        if (!bound) return false;
        return matches(sessionId, turnEpoch, actorEntityId, BattleClientState.sessionId,
                BattleClientState.turnEpoch, BattleClientState.commandEntityId());
    }

    /**
     * The pure comparison: same encounter, same turn generation, same acting body.
     *
     * @return false for a page that was never bound, so a forgotten bind can never authorize a click
     */
    public static boolean matches(UUID pageSession, long pageEpoch, int pageActor,
                                  UUID liveSession, long liveEpoch, int liveActor) {
        if (pageSession == null || pageActor < 0) return false;
        return liveActor >= 0 && pageActor == liveActor
                && Objects.equals(pageSession, liveSession)
                && pageEpoch == liveEpoch;
    }

    /** True when this page was bound at all. */
    public static boolean bound() {
        return bound;
    }
}
