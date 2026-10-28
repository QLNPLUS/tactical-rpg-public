package com.tactical.neoforge.battle;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FleeProtectionTest {
    @Test void lastsExactlyTwoHundredRealTicks() {
        var timer = new FleeProtection.Countdown();
        UUID player = UUID.randomUUID();
        timer.give(player);
        for (int tick = 0; tick < 200; tick++) {
            assertTrue(timer.has(player), "protected before real tick " + (tick + 1));
            timer.tick();
        }
        assertFalse(timer.has(player));
        assertTrue(timer.empty());
    }

    @Test void grantsRefreshAndRemovalAreUuidScoped() {
        var timer = new FleeProtection.Countdown();
        UUID owner = UUID.randomUUID(), outsider = UUID.randomUUID();
        timer.give(owner);
        for (int tick = 0; tick < 199; tick++) timer.tick();
        timer.give(outsider);
        timer.give(UUID.fromString(owner.toString()));
        timer.tick();
        assertTrue(timer.has(owner));
        timer.remove(UUID.fromString(owner.toString()));
        assertFalse(timer.has(owner));
        assertTrue(timer.has(outsider));
        timer.clear();
        assertTrue(timer.empty());
    }
}
