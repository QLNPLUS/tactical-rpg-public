package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command page's actor binding, as a pure decision.
 *
 * <p>A page is only allowed to act under the exact session, turn generation and acting body it was opened
 * for. That is what stops a page left open across an actor switch from being confirmed under the next
 * actor's stamp; the live screen is driven by the same comparison.</p>
 */
class CommandPageGuardTest {

    private static final UUID SESSION = new UUID(7L, 8L);
    private static final UUID OTHER = new UUID(9L, 10L);

    @Test
    void theSameSessionTurnAndActorMatches() {
        assertTrue(CommandPageGuard.matches(SESSION, 4L, 72, SESSION, 4L, 72));
    }

    @Test
    void anUnboundPageNeverMatches() {
        assertFalse(CommandPageGuard.matches(null, 4L, 72, SESSION, 4L, 72),
                "a page that was never bound cannot authorize a click");
        assertFalse(CommandPageGuard.matches(SESSION, 4L, -1, SESSION, 4L, -1),
                "and neither can one bound to no body");
    }

    @Test
    void aSwitchedActorIsRefusedEvenInsideTheSameTurn() {
        assertFalse(CommandPageGuard.matches(SESSION, 4L, 72, SESSION, 4L, 73),
                "another body, another order");
    }

    @Test
    void aNewTurnOrAnotherEncounterIsRefused() {
        assertFalse(CommandPageGuard.matches(SESSION, 4L, 72, SESSION, 5L, 72), "a new turn invalidates the page");
        assertFalse(CommandPageGuard.matches(SESSION, 4L, 72, OTHER, 4L, 72), "another encounter invalidates it");
    }

    @Test
    void aVanishedActorIsRefused() {
        assertFalse(CommandPageGuard.matches(SESSION, 4L, 72, SESSION, 4L, -1),
                "an acting body the client can no longer resolve is not a target for an order");
    }
}
