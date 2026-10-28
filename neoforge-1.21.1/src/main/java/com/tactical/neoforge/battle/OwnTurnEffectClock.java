package com.tactical.neoforge.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minecraft-free clock: only effects unchanged for a complete own-turn cycle are charged. */
final class OwnTurnEffectClock<K> {
    private Map<K, Stamp> baseline = Map.of();

    record Stamp(Object identity, Object state) {
        boolean matches(Stamp other) {
            return other != null && identity == other.identity && Objects.equals(state, other.state);
        }
    }

    List<K> begin(Map<K, Stamp> current) {
        List<K> eligible = new ArrayList<>();
        current.forEach((key, stamp) -> {
            if (stamp.matches(baseline.get(key))) eligible.add(key);
        });
        return eligible;
    }

    void baseline(Map<K, Stamp> current) {
        baseline = new LinkedHashMap<>(current);
    }
}
