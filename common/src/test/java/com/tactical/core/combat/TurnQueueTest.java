package com.tactical.core.combat;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurnQueueTest {

    private static CombatantSnapshot combatant(String id, int initiative) {
        return new CombatantSnapshot(id, id, Team.ENEMY, new GridPos(0, 64, 0), 20, 20, initiative, 100, 100);
    }

    @Test
    void sortsByInitiativeDescending() {
        TurnQueue queue = new TurnQueue();
        queue.rebuild(List.of(combatant("slow", 5), combatant("fast", 20), combatant("mid", 12)));

        assertEquals(List.of("fast", "mid", "slow"), queue.order());
        assertEquals("fast", queue.current());
    }

    @Test
    void advancesAndWrapsIntoNextRound() {
        TurnQueue queue = new TurnQueue();
        queue.rebuild(List.of(combatant("a", 20), combatant("b", 10)));

        assertEquals(1, queue.round());
        assertEquals("b", queue.advance());
        assertEquals(1, queue.round());
        assertEquals("a", queue.advance());
        assertEquals(2, queue.round());
    }

    @Test
    void removeKeepsCursorSane() {
        TurnQueue queue = new TurnQueue();
        queue.rebuild(List.of(combatant("a", 30), combatant("b", 20), combatant("c", 10)));

        queue.advance(); // b
        queue.remove("c");
        assertEquals(List.of("a", "b"), queue.order());

        queue.remove("b");
        assertEquals("a", queue.current());
    }
}
