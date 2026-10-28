package com.tactical.core.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnSchedulerTest {
    @Test void rateUpdatesPreserveGaugeAndCurrentActor() {
        TurnScheduler scheduler = new TurnScheduler();
        scheduler.add("fast", 10);
        scheduler.add("slow", 2);
        assertEquals("fast", scheduler.advance());
        double gauge = scheduler.gaugeOf("slow");
        assertEquals(.2, gauge, 1e-9);
        scheduler.updateRate("slow", 20);
        assertEquals(gauge, scheduler.gaugeOf("slow"));
        assertEquals("fast", scheduler.current());
        assertEquals("slow", scheduler.advance());
    }

    @Test void fractionalRatesAndRepeatedAddDoNotResetGauge() {
        TurnScheduler scheduler = new TurnScheduler();
        scheduler.add("a", .5);
        scheduler.add("b", .1);
        assertEquals("a", scheduler.advance());
        double gauge = scheduler.gaugeOf("b");
        scheduler.add("b", .25);
        assertEquals(gauge, scheduler.gaugeOf("b"));
        assertEquals(.25, scheduler.rateOf("b"));
        scheduler.updateRate("missing", 100);
        assertFalse(scheduler.order().contains("missing"));
        scheduler.updateRate("b", Double.NaN);
        assertTrue(Double.isFinite(scheduler.rateOf("b")));
    }
}
