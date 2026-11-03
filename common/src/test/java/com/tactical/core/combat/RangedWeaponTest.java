package com.tactical.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangedWeaponTest {
    @Test
    void eachWeaponDeclaresItsOwnRealLaunchSpeed() {
        assertEquals(1.6F, RangedWeapon.BOW.speed());
        assertEquals(3.15F, RangedWeapon.CROSSBOW.speed());
        // A crossbow arrow must not inherit bow parameters: that was the old assumption.
        assertNotEquals(RangedWeapon.BOW.speed(), RangedWeapon.CROSSBOW.speed());
    }

    @Test
    void arrowPhysicsMatchVanillaArrowEveryTick() {
        for (RangedWeapon weapon : RangedWeapon.values()) {
            assertTrue(weapon.gravity(), weapon + " keeps vanilla gravity");
            assertEquals(0.99D, weapon.drag(), weapon + " keeps vanilla air drag");
            assertEquals(0.6D, weapon.waterDrag(), weapon + " keeps vanilla liquid drag");
        }
    }

    @Test
    void actionCostsComeFromTheSharedRuleset() {
        assertEquals(BattleRules.BOW_COST, RangedWeapon.BOW.apCost());
        assertEquals(BattleRules.CROSSBOW_COST, RangedWeapon.CROSSBOW.apCost());
        assertEquals(20, RangedWeapon.CROSSBOW.apCost());
    }

    @Test
    void flightBoundIsPositiveAndBounded() {
        assertTrue(RangedWeapon.MAX_FLIGHT_TICKS > 0);
    }
}
