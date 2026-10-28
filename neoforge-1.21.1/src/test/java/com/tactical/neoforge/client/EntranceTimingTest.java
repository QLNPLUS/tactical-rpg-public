package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntranceTimingTest {
    @Test
    void reportsActualRenderIntervalsRatherThanAssumingTwentyTicks() {
        EntranceTiming timing = new EntranceTiming();
        long start = 1_000_000_000L;
        timing.frame(start);
        timing.frame(start + 8_000_000L);
        timing.frame(start + 24_000_000L);
        timing.frame(start + 64_000_000L);
        assertEquals(3, timing.samples());
        assertEquals(64.0 / 3, timing.meanMs(), 0.0001);
        assertEquals(40, timing.worstMs());
        assertEquals(1, timing.over25ms());
        assertEquals(200, EntranceTiming.DURATION_MS);
    }

    @Test
    void initialOrDuplicateFrameCannotInventLatency() {
        EntranceTiming timing = new EntranceTiming();
        timing.frame(100);
        timing.frame(100);
        assertEquals(0, timing.samples());
        assertEquals(0, timing.meanMs());
        assertEquals(0, timing.worstMs());
    }
}
