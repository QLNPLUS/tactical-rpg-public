package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttackRangeRefreshTest {
    @Test void regularRefreshKeepsThePreviousSnapshotVisibleUntilItsReplacementArrives() {
        var refresh = new AttackRangeRefresh();
        assertFalse(refresh.fresh(0));
        int id = refresh.request(0);
        assertTrue(refresh.accept(id, 1));
        for (long tick = 1; tick <= 100; tick++) {
            if (tick % AttackRangeRefresh.REFRESH_TICKS == 0) id = refresh.request(tick);
            // A one-tick round trip used to leave a gap at every four-tick expiry.
            assertTrue(refresh.fresh(tick), "preview gap at tick " + tick);
            if (tick % AttackRangeRefresh.REFRESH_TICKS == 1 && tick > 1)
                assertTrue(refresh.accept(id, tick));
        }
    }

    @Test void slowerThanRefreshResponsesAreNotSupersededByNewRequests() {
        var refresh = new AttackRangeRefresh();
        int id = refresh.request(100);
        for (int tick = 101; tick <= 108; tick++) assertEquals(0, refresh.request(tick));
        assertTrue(refresh.accept(id, 108));
        assertTrue(refresh.fresh(108));
        int next = refresh.request(109);
        assertNotEquals(0, next);
        assertNotEquals(id, next);
        assertTrue(refresh.fresh(109));
    }

    @Test void lostResponsesExpireAndRetryWithoutRevivingOldSnapshots() {
        var refresh = new AttackRangeRefresh();
        int first = refresh.request(0);
        assertTrue(refresh.accept(first, 1));
        int lost = refresh.request(4);
        assertTrue(refresh.fresh(19));
        assertFalse(refresh.fresh(20));
        assertEquals(0, refresh.request(23));
        int retry = refresh.request(24);
        assertNotEquals(0, retry);
        assertFalse(refresh.accept(lost, 25));
        assertTrue(refresh.accept(retry, 25));
        assertTrue(refresh.fresh(25));
    }

    @Test void invalidatedAimContextRejectsOldWeaponMovementAndSessionReplies() {
        var refresh = new AttackRangeRefresh();
        int old = refresh.request(100);
        assertTrue(refresh.accept(old, 101));
        int pending = refresh.request(104);
        refresh.invalidate();
        assertFalse(refresh.fresh(104));
        assertFalse(refresh.accept(pending, 105));
        int next = refresh.request(105);
        assertNotEquals(pending, next);
        assertFalse(refresh.accept(pending, 106));
        assertTrue(refresh.accept(next, 106));
    }

    @Test void lateAndDuplicateResponsesCannotExtendTheLifetime() {
        var refresh = new AttackRangeRefresh();
        int id = refresh.request(10);
        assertTrue(refresh.accept(id, 28));
        assertTrue(refresh.fresh(29));
        assertFalse(refresh.accept(id, 29));
        assertFalse(refresh.fresh(30));
        int late = refresh.request(30);
        assertFalse(refresh.accept(late, 50));
        assertFalse(refresh.fresh(50));
    }

    @Test void localClockRollbackExpiresTheSnapshotAndAllowsANewRequest() {
        var refresh = new AttackRangeRefresh();
        int id = refresh.request(100);
        assertTrue(refresh.accept(id, 101));
        assertFalse(refresh.fresh(99));
        int next = refresh.request(99);
        assertNotEquals(0, next);
        assertTrue(refresh.accept(next, 99));
        assertTrue(refresh.fresh(99));
    }

    @Test void refreshIntervalStillBoundsRequestsAfterImmediateResponses() {
        var refresh = new AttackRangeRefresh();
        int id = refresh.request(0);
        assertTrue(refresh.accept(id, 0));
        assertEquals(0, refresh.request(1));
        assertEquals(0, refresh.request(2));
        assertEquals(0, refresh.request(3));
        assertNotEquals(0, refresh.request(4));
    }
}
