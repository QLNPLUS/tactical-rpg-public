package com.tactical.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Initiative-ordered turn queue. Holds combatant ids only, so it stays pure
 * logic; the loader resolves ids back to entities.
 */
public final class TurnQueue {

    private final List<String> order = new ArrayList<>();
    private int cursor;
    private int round = 1;

    /** Rebuild the queue from scratch, sorting by initiative (desc) then id. */
    public void rebuild(List<CombatantSnapshot> combatants) {
        order.clear();
        combatants.stream()
                .sorted(Comparator.comparingInt(CombatantSnapshot::initiative).reversed()
                        .thenComparing(CombatantSnapshot::id))
                .forEach(snapshot -> order.add(snapshot.id()));
        cursor = 0;
        round = 1;
    }

    public String current() {
        if (order.isEmpty()) {
            return null;
        }
        return order.get(Math.floorMod(cursor, order.size()));
    }

    /** Advance to the next living combatant, wrapping into a new round. */
    public String advance() {
        if (order.isEmpty()) {
            return null;
        }
        cursor++;
        if (cursor >= order.size()) {
            cursor = 0;
            round++;
        }
        return current();
    }

    /** Append a late joiner to the end of the current round. */
    public void add(String id) {
        if (!order.contains(id)) {
            order.add(id);
        }
    }

    public void remove(String id) {        int index = order.indexOf(id);
        if (index < 0) {
            return;
        }
        order.remove(index);
        if (index < cursor) {
            cursor--;
        }
        if (cursor < 0) {
            cursor = 0;
        }
        if (cursor >= order.size() && !order.isEmpty()) {
            cursor = 0;
        }
    }

    public boolean isEmpty() {
        return order.isEmpty();
    }

    public List<String> order() {
        return List.copyOf(order);
    }

    public int cursor() {
        return cursor;
    }

    public int round() {
        return round;
    }
}
