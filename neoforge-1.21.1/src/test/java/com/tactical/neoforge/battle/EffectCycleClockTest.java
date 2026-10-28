package com.tactical.neoforge.battle;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure clock contract; the opt-in dedicated fixture covers actual Minecraft effect behavior. */
class EffectCycleClockTest {
    @Test void completeOwnCycleContract() { runCases(); }

    public static void main(String[] args) {
        runCases();
        System.out.println("EffectCycleClockTest: 10 checks passed");
    }

    private static void runCases() {
        OwnTurnEffectClock<String> a = new OwnTurnEffectClock<>();
        OwnTurnEffectClock<String> b = new OwnTurnEffectClock<>();
        Object original = new Object();
        Map<String, OwnTurnEffectClock.Stamp> effects = new LinkedHashMap<>();
        effects.put("poison", new OwnTurnEffectClock.Stamp(original, List.of(600, 0, 1000)));
        check(a.begin(effects).isEmpty(), "first own turn has no full-cycle penalty");
        a.baseline(effects);
        check(a.begin(effects).equals(List.of("poison")), "unchanged next own start settles");
        check(b.begin(effects).isEmpty(), "another actor has an independent baseline");
        effects.put("regen", new OwnTurnEffectClock.Stamp(new Object(), List.of(-1, 1)));
        check(a.begin(effects).equals(List.of("poison")), "midcycle additions wait for baseline");
        a.baseline(effects);
        check(a.begin(effects).equals(List.of("poison", "regen")), "infinite effect eligible without finite sentinel conversion");
        effects.put("poison", new OwnTurnEffectClock.Stamp(original, List.of(900, 0, 1000)));
        check(a.begin(effects).equals(List.of("regen")), "in-place duration refresh waits one baseline");
        a.baseline(effects);
        effects.put("poison", new OwnTurnEffectClock.Stamp(new Object(), List.of(900, 0, 1000)));
        check(a.begin(effects).equals(List.of("regen")), "equal-valued replacement is a new lifecycle");
        a.baseline(effects);
        OwnTurnEffectClock.Stamp changed = effects.get("poison");
        effects.put("poison", new OwnTurnEffectClock.Stamp(changed.identity(), List.of(900, 0, 1200)));
        check(a.begin(effects).equals(List.of("regen")), "hidden-chain mutation counts as refresh");
        effects.remove("poison");
        check(a.begin(effects).equals(List.of("regen")), "removed effect never settles from stale baseline");
        a.baseline(Map.of());
        check(a.begin(effects).isEmpty(), "cleanup or empty baseline grants fresh first own turn");
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}
