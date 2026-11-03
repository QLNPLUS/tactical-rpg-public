package com.tactical.neoforge.battle.terrain;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of a forced fall: what the descent may do next, and what the fall itself remembers.
 *
 * <p>These are the rules the encounter cannot re-derive per caller without going wrong somewhere. A
 * blocked column is the case that matters most: it is not a landing, it does not restart the deadline,
 * and it is what keeps a body with nothing under it from reporting a successful drop every tick.</p>
 */
class FallPlannerTest {

    private static final GridPos START = new GridPos(5, 20, 5);

    @Test
    void aSolidFloorUnderTheUnitIsTheOnlyLanding() {
        assertEquals(FallPlanner.Step.LAND, FallPlanner.next(START, cell -> true, cell -> true),
                "with a floor under it the unit has arrived");
        assertNotEquals(FallPlanner.Step.LAND, FallPlanner.next(START, cell -> false, cell -> true),
                "a floor is what makes a landing, not the passage of time");
    }

    @Test
    void anOpenColumnDescendsAndABlockedOneSticksWithoutLanding() {
        assertEquals(FallPlanner.Step.DESCEND, FallPlanner.next(START, cell -> false, cell -> true),
                "nothing below and room to fall: keep descending");
        assertEquals(FallPlanner.Step.STUCK, FallPlanner.next(START, cell -> false, cell -> false),
                "another combatant or a block fills the cell below: wait, do not land");
        assertEquals(FallPlanner.Step.STUCK, FallPlanner.next(null, cell -> false, cell -> true),
                "a fall without a cell cannot pretend to have landed");
    }

    /**
     * The deadline belongs to the fall, not to one step of it.
     *
     * <p>A waiting tick still advances the clock, and completing a step does not reset it. That is what
     * turns a permanently blocked column into the explicit exception path instead of an endless hover.</p>
     */
    @Test
    void aWaitingFallKeepsOneDeadlineAndAStepDoesNotResetIt() {
        TerrainFall falls = new TerrainFall();
        UUID id = UUID.randomUUID();
        TerrainFall.Unit unit = falls.begin(id, true, START);
        assertNotNull(unit);

        for (int tick = 1; tick < TerrainFall.TIMEOUT_TICKS; tick++) {
            assertEquals(tick, unit.advance());
            assertFalse(unit.expired(), "one tick short of the deadline the fall is still running");
        }
        assertEquals(TerrainFall.TIMEOUT_TICKS, unit.advance());
        assertTrue(unit.expired(), "a column that stays blocked ends in the explicit exception path");
        assertTrue(falls.busy(), "expiry is decided by the encounter, which still owns the fall");

        TerrainFall.Unit stepping = falls.begin(UUID.randomUUID(), true, new GridPos(0, 30, 0));
        for (int tick = 0; tick < 50; tick++) stepping.advance();
        stepping.beginStep(stepping.cell(), new GridPos(0, 29, 0), 4);
        stepping.stepLanded(new GridPos(0, 29, 0));
        assertEquals(51, stepping.advance(), "landing one cell does not restart the deadline");
    }

    @Test
    void aLandingSomebodyElseClaimedIsRefusedAndAUnitsOwnClaimIsNot() {
        TerrainFall falls = new TerrainFall();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GridPos floor = new GridPos(5, 14, 5);
        falls.begin(first, true, START).landing(floor);

        assertTrue(falls.claimedByOther(floor, second), "another unit's reservation is taken");
        assertFalse(falls.claimedByOther(floor, first), "a unit does not conflict with itself");
        assertFalse(falls.claimedByOther(new GridPos(5, 13, 5), second), "an unclaimed cell is free");
        assertFalse(falls.claimedByOther(null, second), "no cell claims nothing");

        assertTrue(FallPlanner.resolve(START, cell -> cell.y() == floor.y(), cell -> true,
                        cell -> falls.claimedByOther(cell, second)).isEmpty(),
                "two units must not arrive in the same cell");
        assertEquals(floor, FallPlanner.resolve(START, cell -> cell.y() == floor.y(), cell -> true,
                cell -> falls.claimedByOther(cell, first)).orElseThrow().cell(),
                "the same column is still the first unit's landing");
    }

    @Test
    void aColumnThatLeavesTheZoneHasNoLandingAtAll() {
        assertTrue(FallPlanner.resolve(new GridPos(5, 3, 5), cell -> cell.y() == 0, cell -> cell.y() > 1,
                        cell -> false).isEmpty(),
                "a floor outside the zone is not a landing this encounter may use");
        assertEquals(2, FallPlanner.resolve(START, cell -> cell.y() == 18, cell -> true, cell -> false)
                .orElseThrow().cells(), "two cells down is a two-cell drop, not a threshold");
    }

    @Test
    void anEndedFallLeavesNothingBehind() {
        TerrainFall falls = new TerrainFall();
        UUID id = UUID.randomUUID();
        falls.begin(id, false, START).landing(new GridPos(5, 19, 5));
        assertTrue(falls.busy());

        falls.end(id);
        assertFalse(falls.busy(), "a settled unit stops making the encounter busy");
        assertNull(falls.unit(id));
        assertFalse(falls.claimedByOther(new GridPos(5, 19, 5), UUID.randomUUID()),
                "its reservation is released with it");

        falls.begin(id, false, START);
        falls.clear();
        assertFalse(falls.busy(), "an encounter that finishes holds no fall state at all");
        assertTrue(falls.ids().isEmpty());
        assertTrue(falls.describe().isEmpty());
    }
}
