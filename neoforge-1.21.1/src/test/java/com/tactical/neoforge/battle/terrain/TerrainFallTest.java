package com.tactical.neoforge.battle.terrain;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bookkeeping behind a forced fall: who is falling, what each unit holds, and what makes the
 * encounter busy.
 *
 * <p>Two properties matter more than the rest and are checked first: a fall is not a paid move (nothing
 * here can charge or restore anything), and two units falling at the same time may not arrive in the
 * same cell.</p>
 */
class TerrainFallTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void anEncounterIsIdleUntilSomebodyFalls() {
        TerrainFall fall = new TerrainFall();
        assertFalse(fall.busy());
        assertEquals(0, fall.size());
        assertNull(fall.unit(A));
    }

    @Test
    void oneUnitKeepsItsOwnCellAndDistance() {
        TerrainFall fall = new TerrainFall();
        TerrainFall.Unit unit = fall.begin(A, true, new GridPos(3, 70, 4));
        assertTrue(fall.busy());
        assertEquals(new GridPos(3, 70, 4), unit.cell());
        assertEquals(new GridPos(3, 70, 4), unit.origin(), "the origin is what the journal reports");
        assertTrue(unit.player());
        unit.beginStep(new GridPos(3, 70, 4), new GridPos(3, 69, 4), 2);
        assertEquals(0.0D, unit.fraction(), 1.0E-9D);
        unit.progress(1);
        assertEquals(0.5D, unit.fraction(), 1.0E-9D);
        unit.progress(2);
        assertEquals(1.0D, unit.fraction(), 1.0E-9D, "the animation never overshoots its own step");
        unit.stepLanded(new GridPos(3, 69, 4));
        assertEquals(1, unit.cells(), "one completed cell is one block of fall");
        assertEquals(new GridPos(3, 69, 4), unit.cell());
        assertNull(unit.stepTo(), "the finished step is cleared");
    }

    @Test
    void startingTheSameUnitTwiceKeepsItsProgress() {
        TerrainFall fall = new TerrainFall();
        TerrainFall.Unit first = fall.begin(A, true, new GridPos(0, 70, 0));
        first.advance();
        TerrainFall.Unit again = fall.begin(A, true, new GridPos(0, 71, 0));
        assertEquals(first, again, "a second start of the same fall is not a second fall");
        assertEquals(1, again.ticks(), "and it does not reset the deadline");
        assertEquals(1, fall.size());
    }

    @Test
    void twoFallingUnitsMayNotClaimTheSameLanding() {
        TerrainFall fall = new TerrainFall();
        TerrainFall.Unit a = fall.begin(A, true, new GridPos(0, 70, 0));
        a.landing(new GridPos(0, 61, 0));
        TerrainFall.Unit b = fall.begin(B, false, new GridPos(0, 72, 0));
        assertTrue(fall.claimedByOther(new GridPos(0, 61, 0), B),
                "the first unit's landing is reserved against the second");
        assertFalse(fall.claimedByOther(new GridPos(0, 61, 0), A), "a unit never conflicts with itself");
        assertFalse(fall.claimedByOther(new GridPos(1, 61, 0), B), "another cell is free");
        b.landing(new GridPos(1, 61, 0));
        assertEquals(2, fall.size(), "both units stay tracked");
    }

    @Test
    void theDeadlineBelongsToTheUnit() {
        TerrainFall fall = new TerrainFall();
        TerrainFall.Unit unit = fall.begin(A, true, new GridPos(0, 70, 0));
        for (int tick = 0; tick < TerrainFall.TIMEOUT_TICKS - 1; tick++) unit.advance();
        assertFalse(unit.expired(), "one tick before the deadline the fall is still legitimate");
        unit.advance();
        assertTrue(unit.expired(), "at the deadline the encounter must give up on it");
        assertEquals(TerrainFall.TIMEOUT_TICKS, unit.ticks());
    }

    @Test
    void endingOneFallLeavesTheOthersAlone() {
        TerrainFall fall = new TerrainFall();
        fall.begin(A, true, new GridPos(0, 70, 0));
        fall.begin(B, false, new GridPos(1, 70, 0));
        fall.end(A);
        assertNotNull(fall.unit(B));
        assertNull(fall.unit(A));
        assertTrue(fall.busy(), "the encounter is still busy while one unit falls");
        fall.end(B);
        assertFalse(fall.busy());
        fall.clear();
        assertEquals(0, fall.size());
    }

    @Test
    void theReportNamesEveryTrackedUnit() {
        TerrainFall fall = new TerrainFall();
        fall.begin(A, true, new GridPos(0, 70, 0));
        String report = fall.describe();
        assertTrue(report.contains(A.toString()), "a timeout report has to name the unit");
        assertTrue(report.contains("cells=0"));
        assertEquals(1, fall.ids().size());
    }
}
