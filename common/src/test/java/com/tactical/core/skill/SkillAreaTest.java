package com.tactical.core.skill;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillAreaTest {
    @Test void circleUsesContinuousInclusiveEdgesAndRejectsNonFinitePoints() {
        SkillArea area = SkillArea.circle(5);
        assertTrue(area.contains(3, 4));
        assertFalse(area.contains(3.01, 4));
        assertFalse(area.contains(Double.NaN, 0));
        assertFalse(area.contains(0, Double.POSITIVE_INFINITY));
        assertEquals(new SkillArea.Bounds(-5, -5, 5, 5), area.bounds());
    }

    @Test void rectangleLineAndMinecraftYawShareTheSameLocalForward() {
        SkillArea line = SkillArea.line(2, 4);
        assertTrue(line.contains(-1, 4));
        assertFalse(line.contains(0, -0.01));
        SkillArea rotated = line.transformed(10, -3, 90);
        assertTrue(rotated.contains(6, -3));
        assertTrue(rotated.contains(8, -2));
        assertFalse(rotated.contains(12, -3));
        assertEquals(6, rotated.bounds().minX(), 1e-9);
        assertEquals(10, rotated.bounds().maxX(), 1e-9);
        assertEquals(-4, rotated.bounds().minZ(), 1e-9);
        assertEquals(-2, rotated.bounds().maxZ(), 1e-9);
    }

    @Test void transformRoundTripPreservesArbitraryTriangleMembership() {
        SkillArea source = SkillArea.triangle(-2, -1, 3, 0, 0, 4);
        for (int yaw = -720; yaw <= 720; yaw += 15) {
            SkillArea transformed = source.transformed(-7, 12, yaw);
            double c = Math.cos(Math.toRadians(yaw)), s = Math.sin(Math.toRadians(yaw));
            for (int x = -4; x <= 4; x++) {
                for (int z = -3; z <= 5; z++) {
                    assertEquals(source.contains(x, z),
                        transformed.contains(-7 + c * x - s * z, 12 + s * x + c * z));
                }
            }
        }
    }

    @Test void trianglesAcceptEitherWindingAndDegenerateSegments() {
        SkillArea triangle = SkillArea.rightTriangle(4);
        assertTrue(triangle.contains(2, 2));
        assertFalse(triangle.contains(3, 3));
        assertTrue(SkillArea.triangle(0, 4, 4, 0, 0, 0).contains(1, 1));
        SkillArea segment = SkillArea.triangle(0, 0, 2, 2, 4, 4);
        assertTrue(segment.contains(3, 3));
        assertFalse(segment.contains(3, 2));
        assertTrue(SkillArea.rightTriangle(0).contains(0, 0));
        assertFalse(SkillArea.rightTriangle(0).contains(1, 0));
    }

    @Test void booleanCompositionPreservesHolesAndEmptyIntersections() {
        SkillArea left = SkillArea.circle(2).transformed(-1, 0, 0);
        SkillArea right = SkillArea.circle(2).transformed(1, 0, 0);
        assertTrue(SkillArea.union(List.of(left, right)).contains(3, 0));
        assertTrue(SkillArea.intersection(List.of(left, right)).contains(0, 0));
        assertFalse(SkillArea.intersection(List.of(left, right)).contains(2, 0));
        SkillArea ring = SkillArea.difference(SkillArea.circle(3), List.of(SkillArea.circle(1)));
        assertFalse(ring.contains(0, 0));
        assertFalse(ring.contains(1, 0));
        assertTrue(ring.contains(2, 0));
        SkillArea empty = SkillArea.intersection(List.of(left, right.transformed(100, 0, 0)));
        assertTrue(empty.bounds().isEmpty());
        assertFalse(empty.contains(0, 0));
        assertTrue(empty.transformed(100, 0, 90).bounds().isEmpty());
        assertFalse(SkillArea.union(List.of()).contains(0, 0));
    }

    @Test void invalidDimensionsAndTransformsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkillArea.circle(-1));
        assertThrows(IllegalArgumentException.class, () -> SkillArea.rectangle(1, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> SkillArea.line(1, 2).transformed(0, Double.POSITIVE_INFINITY, 0));
    }
}
