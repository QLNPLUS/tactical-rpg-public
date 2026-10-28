package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Timing is supplementary; native cursor callbacks and screenshots accept the overlay. */
class LogHoverAnimationTest {
    @Test void onlyHoveredOverflowMovesAfterDelayAndReachesCompleteTail() {
        var clock = new LogHoverAnimation();
        assertEquals(0, clock.offset("1", 120, 1, 0));
        assertEquals(0, clock.offset("1", 120, 1, 849));
        assertEquals(0, clock.offset("1", 120, 1, 850));
        assertEquals(30, clock.offset("1", 120, 1, 1850));
        assertEquals(120, clock.offset("1", 120, 1, 4850));
        assertEquals(120, clock.offset("1", 120, 1, 6049));
        assertEquals(0, clock.offset("1", 120, 1, 6050));
    }

    @Test void leavingOrSwitchingEntryImmediatelyResetsAndRestartsDelay() {
        var clock = new LogHoverAnimation();
        clock.offset("old", 60, 1, 0);
        assertTrue(clock.offset("old", 60, 1, 2000) > 0);
        assertEquals(0, clock.offset(null, 60, 1, 2001));
        assertEquals(0, clock.offset("old", 60, 1, 2002));
        assertEquals(0, clock.offset("new", 60, 1, 4000));
        assertEquals(0, clock.offset("new", 60, 1, 4849));
    }

    @Test void shortAndInvalidWidthsNeverAnimate() {
        var clock = new LogHoverAnimation();
        for (double width : new double[]{-1, 0, .5, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(0, clock.offset("short", width, 1, 99999));
    }

    @Test void rebuildResizeHideAndClockRollbackRestartDelay() {
        var clock = new LogHoverAnimation();
        clock.offset("1", 120, 1, 0);
        assertEquals(30, clock.offset("1", 120, 1, 1850));
        assertEquals(0, clock.offset("1", 200, 1, 1851));
        clock.reset();
        assertEquals(0, clock.offset("1", 200, 1, 9000));
        assertEquals(0, clock.offset("1", 200, 1, 500));
    }

    @Test void visualSpeedScalesWithExplicitInterfacePreset() {
        var clock = new LogHoverAnimation();
        clock.offset("1", 200, 1.6, 0);
        assertEquals(48, clock.offset("1", 200, 1.6, 1850));
    }
}
