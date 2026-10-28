package com.tactical.core.combat;

import org.junit.jupiter.api.Test;

import static com.tactical.core.combat.PositionalDamage.Sector.*;
import static org.junit.jupiter.api.Assertions.*;

class PositionalDamageTest {
    private static final PositionalDamage.Rules RULES = PositionalDamage.DEFAULTS;

    @Test void defaultInclusiveConesAndSides() {
        assertEquals(FRONT, PositionalDamage.sector(0, 0, 1));
        assertEquals(FRONT, PositionalDamage.sector(0, 1, 1));
        assertEquals(FRONT, PositionalDamage.sector(0, -1, 1));
        assertEquals(SIDE, PositionalDamage.sector(0, 1.000001, 1));
        assertEquals(SIDE, PositionalDamage.sector(0, 1, 0));
        assertEquals(SIDE, PositionalDamage.sector(0, 1.000001, -1));
        assertEquals(REAR, PositionalDamage.sector(0, 1, -1));
        assertEquals(REAR, PositionalDamage.sector(0, -1, -1));
        assertEquals(REAR, PositionalDamage.sector(0, 0, -1));
        assertEquals(1, PositionalDamage.direction(FRONT, RULES));
        assertEquals(1.2, PositionalDamage.direction(SIDE, RULES));
        assertEquals(1.5, PositionalDamage.direction(REAR, RULES));
    }

    @Test void physicalYawAndRotationAreConsistent() {
        assertEquals(FRONT, PositionalDamage.sector(90, -1, 0));
        assertEquals(REAR, PositionalDamage.sector(90, 1, 0));
        assertEquals(FRONT, PositionalDamage.sector(-90, 1, 0));
        for (int yaw = -720; yaw <= 720; yaw += 15) {
            double radians = Math.toRadians(yaw);
            assertEquals(FRONT, PositionalDamage.sector(yaw, -Math.sin(radians), Math.cos(radians)));
            assertEquals(REAR, PositionalDamage.sector(yaw, Math.sin(radians), -Math.cos(radians)));
        }
    }

    @Test void unknownOrDegenerateGeometryIsNeutral() {
        assertEquals(UNKNOWN, PositionalDamage.sector(0, 0, 0));
        assertEquals(UNKNOWN, PositionalDamage.sector(0, Double.NaN, 1));
        assertEquals(UNKNOWN, PositionalDamage.sector(0, 1, Double.POSITIVE_INFINITY));
        assertEquals(UNKNOWN, PositionalDamage.sector(Double.NaN, 1, 1));
        assertEquals(1, PositionalDamage.direction(UNKNOWN, RULES));
        assertEquals(FRONT, PositionalDamage.sector(0, Double.MAX_VALUE, Double.MAX_VALUE));
        assertEquals(REAR, PositionalDamage.sector(0, Double.MIN_VALUE, -Double.MIN_VALUE));
    }

    @Test void customConesHaveExplicitSharedBoundary() {
        assertEquals(SIDE, PositionalDamage.sector(0, 1, 1, 30, 30));
        assertEquals(SIDE, PositionalDamage.sector(0, 1, -1, 30, 30));
        assertEquals(FRONT, PositionalDamage.sector(0, 1, 0, 90, 90));
        assertEquals(REAR, PositionalDamage.sector(0, 0, -1, 0, 0));
    }

    @Test void heightFloorsEachFeetCoordinateBeforeSubtracting() {
        assertEquals(1.05, PositionalDamage.height(2.01, 1.99, RULES), 1e-12);
        assertEquals(1, PositionalDamage.height(1.99, 1.01, RULES), 1e-12);
        assertEquals(1.05, PositionalDamage.height(0.01, -0.01, RULES), 1e-12);
        assertEquals(0.95, PositionalDamage.height(-0.01, 0.01, RULES), 1e-12);
        assertEquals(1.15, PositionalDamage.height(100, 0, RULES), 1e-12);
        assertEquals(0.85, PositionalDamage.height(0, 100, RULES), 1e-12);
        assertEquals(1, PositionalDamage.height(Double.NaN, 0, RULES));
        assertEquals(1, PositionalDamage.height(0, Double.POSITIVE_INFINITY, RULES));
        assertEquals(1.15, PositionalDamage.height(Double.MAX_VALUE, -Double.MAX_VALUE, RULES));
    }

    @Test void configuredHeightAndDirectionRemainIndependent() {
        var custom = new PositionalDamage.Rules(0.9, 1.1, 1.8, 20, 30, 0.1, 0.7, 1.4);
        double direction = PositionalDamage.direction(REAR, custom);
        double height = PositionalDamage.height(2, 0, custom);
        double skill = PositionalDamage.multiply(1, 2);
        assertEquals(1.8, direction);
        assertEquals(1.2, height, 1e-12);
        assertEquals(4.32, direction * height * skill, 1e-12);
        assertEquals(3.45, 1.5 * 1.15 * skill, 1e-12);
    }

    @Test void extensionFactorsIgnoreInvalidInputAndCapOverflow() {
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(2, PositionalDamage.multiply(2, invalid));
        }
        assertEquals(0, PositionalDamage.multiply(2, 0));
        assertEquals(4, PositionalDamage.multiply(2, 2));
        assertEquals(1_000_000, PositionalDamage.multiply(1_000_000, Double.MAX_VALUE));
        assertTrue(Double.isFinite(Math.pow(PositionalDamage.MAX_FACTOR, 3)));
    }

    @Test void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalDamage.Rules(1, 1.2, 1.5, 91, 45, 0.05, 0.85, 1.15));
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalDamage.Rules(1, 1.2, Double.NaN, 45, 45, 0.05, 0.85, 1.15));
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalDamage.Rules(1, 1.2, 1.5, 45, 45, 0.05, 2, 1));
    }
}
