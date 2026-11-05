package com.tactical.neoforge.battle;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure timeline of one salvo: vanilla charge, the two six tick gaps and the hard deadline. */
class BlazeVolleyTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test void vanillaChargeThenThreeShotsSixTicksApart() {
        BlazeVolley volley = new BlazeVolley(ACTOR, TARGET, 7L, 20, 100);
        assertTrue(volley.charging());
        assertFalse(volley.shotDue(159), "no fireball leaves before the vanilla charge finishes");
        assertTrue(volley.chargeDue(160), "charge is 60 vanilla ticks");
        volley.settleCharge();
        assertTrue(volley.shotDue(160));
        volley.onShotFired(160);
        assertFalse(volley.shotDue(161), "six ticks between fireballs");
        assertTrue(volley.shotDue(166));
        volley.onShotFired(166);
        volley.onShotFired(172);
        assertEquals(3, volley.shotsFired());
        assertEquals(0, volley.shotsRemaining());
        assertFalse(volley.shotDue(500), "no fourth fireball exists");
    }

    @Test void salvoCompletesOnlyAfterTheLastProjectileSettles() {
        BlazeVolley volley = new BlazeVolley(ACTOR, TARGET, 7L, 20, 0);
        volley.settleCharge();
        volley.onShotFired(60);
        volley.onShotFired(66);
        volley.onShotFired(72);
        assertFalse(volley.complete(73, true), "a fireball still in flight owns the turn");
        assertTrue(volley.complete(80, false));
    }

    @Test void hardDeadlineBoundsEveryPathIncludingEarlyStops() {
        BlazeVolley volley = new BlazeVolley(ACTOR, TARGET, 7L, 20, 1_000);
        assertEquals(1_000 + BlazeVolley.HARD_DEADLINE_TICKS, volley.deadlineTick());
        assertEquals(60 + 12 + 80 + 8, BlazeVolley.HARD_DEADLINE_TICKS);
        assertFalse(volley.expired(1_159));
        assertTrue(volley.expired(1_160));
        assertTrue(volley.complete(1_160, true), "the deadline wins over a projectile that never settles");
        volley.stopRemaining();
        assertEquals(0, volley.shotsRemaining());
        assertTrue(volley.complete(1_160, true));
    }

    @Test void freezePriceAndRejectMalformedArithmetic() {
        BlazeVolley volley = new BlazeVolley(ACTOR, TARGET, 7L, 20, 0);
        assertEquals(20, volley.price());
        assertEquals(ACTOR, volley.actorId());
        assertEquals(TARGET, volley.targetId());
        assertEquals(7L, volley.epoch());
        assertThrows(IllegalArgumentException.class, () -> new BlazeVolley(ACTOR, TARGET, 7L, 0, 0));
        volley.stopRemaining();
        assertThrows(IllegalStateException.class, () -> volley.onShotFired(10));
    }
}
