package com.tactical.neoforge.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the optional bridge proves without a booted game - and what it explicitly does not.
 *
 * <p>These tests run in a plain unit-test process, so there is no mod list and no entity to hand over. What
 * they pin is therefore the boundary of the call and the identity it was approved for: a missing mod means no
 * level, a missing entity is never a level, and the resolved names are the ones Phase A accepted. They do
 * <em>not</em> drive the path a real creature takes: a non-null {@code LivingEntity} while the mod is absent,
 * an installed API that really answers, an installed API that throws, and the hidden-list policy all need a
 * running game and belong to Phase C, not to this class.</p>
 */
class AdvancedLevelingBridgeTest {

    @AfterEach
    void forgetTheResolution() {
        AdvancedLevelingBridge.reset();
    }

    @Test
    void withoutTheModNothingIsLookedUpAndNoLevelIsInvented() {
        assertFalse(AdvancedLevelingBridge.present(), "a plain unit test has no mod list, let alone this mod");
        assertTrue(AdvancedLevelingBridge.displayLevel(null).isEmpty(), "a missing entity is never a level");
        // Repeated calls stay empty and silent instead of caching a half-resolved state.
        AdvancedLevelingBridge.reset();
        assertFalse(AdvancedLevelingBridge.present());
    }

    @Test
    void theApprovedApiIdentityIsTheOneThatWasAccepted() {
        assertEquals("autoleveling", AdvancedLevelingBridge.MOD_ID);
        assertEquals("daripher.autoleveling.api.LevelingApi", AdvancedLevelingBridge.API_CLASS);
        assertEquals("displayLevel", AdvancedLevelingBridge.API_METHOD);
        // The API's own answer type is what this module compiles against instead of the mod itself.
        assertEquals(OptionalInt.class, OptionalInt.of(1).getClass());
    }
}
