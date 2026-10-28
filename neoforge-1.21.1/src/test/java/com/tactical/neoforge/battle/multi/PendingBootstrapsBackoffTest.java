package com.tactical.neoforge.battle.multi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry backoff of an owed entry bootstrap.
 *
 * <p>The registry itself needs a live server (a member, an encounter and ticks), so its end-to-end
 * behaviour is covered by the server fixture. What is checked here is the part that decides "not on
 * every tick": the delay grows, and it is bounded, so a permanently failing publication cannot spin.</p>
 */
class PendingBootstrapsBackoffTest {

    @Test
    void theRetryDelayGrowsAndStaysBounded() {
        int first = PendingBootstraps.delayTicks(0);
        assertEquals(PendingBootstraps.FIRST_DELAY_TICKS, first);
        int previous = 0;
        for (int attempt = 0; attempt < 16; attempt++) {
            int delay = PendingBootstraps.delayTicks(attempt);
            assertTrue(delay <= 200, "attempt " + attempt + " must stay bounded, got " + delay);
            assertTrue(delay >= previous, "attempt " + attempt + " must never shrink, got " + delay);
            if (attempt < 6) {
                assertTrue(delay > previous, "attempt " + attempt + " must wait longer than " + previous);
            }
            previous = delay;
        }
        assertEquals(PendingBootstraps.FIRST_DELAY_TICKS << Math.min(6, 3),
                PendingBootstraps.delayTicks(3), "the doubling is the documented one");
    }

    @Test
    void anEmptyRegistryOwesNothing() {
        PendingBootstraps.clear();
        assertFalse(PendingBootstraps.owed(null));
        assertEquals(0, PendingBootstraps.debtCount());
        assertEquals(0L, PendingBootstraps.attemptsMade());
    }
}
