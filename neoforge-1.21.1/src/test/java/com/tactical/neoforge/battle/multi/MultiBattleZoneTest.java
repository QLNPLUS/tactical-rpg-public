package com.tactical.neoforge.battle.multi;

import com.tactical.core.grid.GridPos;
import com.tactical.core.zone.BattleZone;
import com.tactical.core.zone.BoundedBattleZone;
import com.tactical.core.zone.ZoneSeparation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP01/MP02 contract: two encounters in one server stay spatially separate, and a unit's
 * ownership never moves between them.
 *
 * <p>The rules tested here are pure Java ({@link ZoneSeparation} and {@link SessionIndex}); the
 * Minecraft-facing wiring (tick order, cleanup, absorb) is exercised by the server-side
 * multiplayer self test, not by this class.</p>
 */
class MultiBattleZoneTest {

    private static BoundedBattleZone cylinder(int x, int y, int z, int radius, int halfHeight) {
        return new BoundedBattleZone(new GridPos(x, y, z), radius, halfHeight, false);
    }

    @Test
    void separateZonesInOneDimensionDoNotConflict() {
        BattleZone first = cylinder(0, 64, 0, 6, 3);
        BattleZone second = cylinder(40, 64, 0, 6, 3);
        assertFalse(ZoneSeparation.conflicts(true, first.bounds(), second.bounds()));
    }

    @Test
    void theSameZoneConflictsInItsOwnDimension() {
        BattleZone first = cylinder(0, 64, 0, 6, 3);
        BattleZone second = cylinder(0, 64, 0, 6, 3);
        assertTrue(ZoneSeparation.conflicts(true, first.bounds(), second.bounds()));
    }

    @Test
    void coordinatesInAnotherDimensionAreIndependent() {
        BattleZone first = cylinder(0, 64, 0, 6, 3);
        BattleZone second = cylinder(0, 64, 0, 6, 3);
        // Identical coordinates but different dimensions: no shared space, no shared units.
        assertFalse(ZoneSeparation.conflicts(false, first.bounds(), second.bounds()));
    }

    @Test
    void boundsAreClosedSoTouchingFacesConflict() {
        BattleZone first = cylinder(0, 64, 0, 5, 3);
        BattleZone second = cylinder(11, 64, 0, 5, 3);
        assertEquals(5, first.bounds().maxX());
        assertEquals(6, second.bounds().minX());
        assertFalse(ZoneSeparation.overlap(first.bounds(), second.bounds()),
                "one free column separates them");

        BattleZone touching = cylinder(10, 64, 0, 5, 3);
        assertEquals(5, touching.bounds().minX());
        assertTrue(ZoneSeparation.overlap(first.bounds(), touching.bounds()),
                "maxX == other.minX is one shared layer of cells, not a gap");
    }

    @Test
    void verticalSeparationAlsoKeepsEncountersApart() {
        BattleZone surface = cylinder(0, 64, 0, 5, 3);
        BattleZone deep = cylinder(0, 90, 0, 5, 3);
        assertFalse(ZoneSeparation.overlap(surface.bounds(), deep.bounds()));
        BattleZone overhang = cylinder(0, 70, 0, 5, 3);
        // 64+3 == 70-3: the closed boxes touch in the y=67 layer.
        assertTrue(ZoneSeparation.overlap(surface.bounds(), overhang.bounds()));
    }

    @Test
    void conservativeBoxTestAlsoRefusesRoundZoneCornerNeighbours() {
        // Two round zones whose bounding boxes overlap only in a corner: reported as conflicting
        // on purpose. A refused start is recoverable; two overlapping encounters are not.
        BattleZone first = cylinder(0, 64, 0, 6, 2);
        BattleZone second = cylinder(10, 64, 10, 6, 2);
        assertTrue(ZoneSeparation.overlap(first.bounds(), second.bounds()));
        // The round areas themselves do not share a cell, which is why the test is documented.
        assertFalse(first.contains(new GridPos(5, 64, 5)));
        assertFalse(second.contains(new GridPos(5, 64, 5)));
    }

    @Test
    void absorbExpansionIsVisibleBeforeItIsApplied() {
        BattleZone own = cylinder(0, 64, 0, 4, 2);
        BattleZone other = cylinder(12, 64, 0, 4, 2);
        assertFalse(ZoneSeparation.overlap(own.bounds(), other.bounds()));

        // tryAbsorb would grow this battlefield to cover the outside attacker.
        BattleZone expanded = own.including(new GridPos(9, 64, 0));
        assertEquals(9, expanded.radius());
        assertTrue(ZoneSeparation.overlap(expanded.bounds(), other.bounds()),
                "the grown battlefield would swallow the neighbour, so the absorb must be refused");
        assertEquals(4, own.radius(), "the original zone is unchanged by the read-only check");
    }

    @Test
    void anAbsorbThatStaysOutsideEveryNeighbourIsAllowed() {
        BattleZone own = cylinder(0, 64, 0, 4, 2);
        BattleZone other = cylinder(40, 64, 0, 4, 2);
        BattleZone expanded = own.including(new GridPos(8, 64, 0));
        assertFalse(ZoneSeparation.conflicts(true, expanded.bounds(), other.bounds()));
    }

    @Test
    void twoEncountersRouteTheirOwnPlayersAndUnitsIndependently() {
        SessionIndex<String> index = new SessionIndex<>();
        UUID firstSession = UUID.randomUUID();
        UUID secondSession = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID firstNpc = UUID.randomUUID();
        UUID secondNpc = UUID.randomUUID();
        index.register(firstSession, "first");
        index.register(secondSession, "second");
        index.bind(firstSession, firstPlayer);
        index.bind(secondSession, secondPlayer);
        index.bind(firstSession, firstNpc);
        index.bind(secondSession, secondNpc);

        assertSame("first", index.findFor(firstPlayer));
        assertSame("second", index.findFor(secondPlayer));
        assertEquals("first", index.findFor(firstNpc), "NPC ownership routes the same way as a player");
        assertEquals("second", index.findFor(secondNpc));

        index.remove(firstSession);
        assertNull(index.findFor(firstPlayer));
        assertNull(index.findFor(firstNpc));
        assertEquals("second", index.findFor(secondPlayer), "ending A never drops B's player");
        assertEquals("second", index.findFor(secondNpc), "ending A never drops B's units");
    }

    @Test
    void sessionSnapshotListsBothEncountersInRegistrationOrder() {
        SessionIndex<String> index = new SessionIndex<>();
        index.register(UUID.randomUUID(), "first");
        index.register(UUID.randomUUID(), "second");
        assertEquals(List.of("first", "second"), index.all());
        assertEquals(2, index.sessionCount());
    }
}
