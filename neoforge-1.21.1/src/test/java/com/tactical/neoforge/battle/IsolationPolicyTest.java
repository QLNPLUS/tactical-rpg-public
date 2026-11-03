package com.tactical.neoforge.battle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolationPolicyTest {

    @Test void exclusionOnlyWhileTheRosterIsFull() {
        assertTrue(IsolationPolicy.holdsWhileFull(2, 2), "exactly at cap");
        assertTrue(IsolationPolicy.holdsWhileFull(17, 2), "over cap cannot happen but must not release");
        assertFalse(IsolationPolicy.holdsWhileFull(1, 2), "a free slot releases immediately");
        assertFalse(IsolationPolicy.holdsWhileFull(0, 2), "empty roster holds nothing");
        assertFalse(IsolationPolicy.holdsWhileFull(0, 0), "a zero capacity is never a full roster");
    }

    @Test void failedPlacementRetriesAreBounded() {
        assertTrue(IsolationPolicy.retryDue(0, 10, 10, 5), "first retry at its due tick");
        assertFalse(IsolationPolicy.retryDue(0, 10, 9, 5), "not before the due tick");
        assertTrue(IsolationPolicy.retryDue(4, 10, 10, 5), "last allowed attempt");
        assertFalse(IsolationPolicy.retryDue(5, 10, 10, 5), "attempts stop at the budget");
        assertFalse(IsolationPolicy.retryDue(5, 0, 100, 5), "an exhausted budget never retries again");
    }

    @Test void intrusionCoversZoneTargetAndPhysicalContact() {
        double contact = 4.0D;
        assertTrue(IsolationPolicy.intrudes(true, false, 900.0D, contact), "inside the battlefield");
        assertTrue(IsolationPolicy.intrudes(false, true, 900.0D, contact), "hunting a member from outside");
        assertTrue(IsolationPolicy.intrudes(false, false, contact, contact), "touching a member without aggro");
        assertTrue(IsolationPolicy.intrudes(false, false, 1.0D, contact), "closer than contact range");
        assertFalse(IsolationPolicy.intrudes(false, false, contact + 0.01D, contact),
                "a mob outside the zone with no member target is not disturbed");
        assertFalse(IsolationPolicy.intrudes(false, false, 900.0D, contact), "far away and unrelated");
    }
}
