package com.tactical.core.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectileAimTest {
    @Test void lowArcHitsRequestedHeightAtBowAndCrossbowRanges() {
        for (double speed : new double[] {3.0, 3.15}) {
            for (double distance : new double[] {1, 4, 8, 16, 24, 28}) {
                for (double height : new double[] {-4, -0.5, 0, 1, 4}) {
                    float pitch = ProjectileAim.pitch(distance, height, speed);
                    double actual = ProjectileAim.heightAt(distance, speed, -Math.toRadians(pitch));
                    assertEquals(height, actual, 1.0E-4,
                            "speed=" + speed + " distance=" + distance + " height=" + height);
                }
            }
        }
    }

    @Test void compensatesGravityAndHandlesVerticalAim() {
        assertTrue(ProjectileAim.pitch(24, 0, 3.0) < 0);
        assertEquals(-90F, ProjectileAim.pitch(0, 1, 3));
        assertEquals(90F, ProjectileAim.pitch(0, -1, 3));
    }
}
