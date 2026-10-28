package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraFocusTransitionTest {
    private static final double DURATION = 0.36;
    private static final CameraFocusTransition.Point ORIGIN = point(0, 0, 0);

    @Test
    void firstFocusHasNoUninitializedWorldOriginFlyIn() {
        var transition = new CameraFocusTransition(DURATION);
        var target = point(10000, 64, -2000);
        assertEquals(target, transition.update(new Object(), target, 0));
    }

    @Test
    void entrySeedAndFocusSwitchPreserveLastRenderedPosition() {
        var transition = new CameraFocusTransition(DURATION);
        transition.seed(point(2, 5, 9));
        assertEquals(point(2, 5, 9), transition.update(new Object(), point(20, 10, 30), 0.1));
    }

    @Test
    void transitionHasSameHalfwayAndEndpointAtDifferentFrameRates() {
        for (int fps : new int[]{30, 60, 144, 240}) {
            var transition = new CameraFocusTransition(DURATION);
            Object player = new Object();
            Object mob = new Object();
            transition.update(player, ORIGIN, 0);
            transition.update(mob, point(10, 20, -4), 0);
            double elapsed = 0;
            while (elapsed < DURATION / 2 - 1.0e-12) {
                double delta = Math.min(1.0 / fps, DURATION / 2 - elapsed);
                elapsed += delta;
                transition.update(mob, point(10, 20, -4), delta);
            }
            assertPoint(point(5, 10, -2), transition.update(mob, point(10, 20, -4), 0));
            assertPoint(point(10, 20, -4), transition.update(mob, point(10, 20, -4), DURATION / 2));
        }
    }

    @Test
    void irregularFrameIntervalsAndLargeFrameUseElapsedTime() {
        var regular = initializedSwitch();
        var irregular = initializedSwitch();
        Object key = SWITCH_KEY;
        regular.update(key, point(10, 0, 0), 0.12);
        for (double delta : new double[]{0.001, 0.024, 0.002, 0.08, 0.013}) {
            irregular.update(key, point(10, 0, 0), delta);
        }
        assertPoint(regular.update(key, point(10, 0, 0), 0),
                irregular.update(key, point(10, 0, 0), 0));
        assertEquals(point(10, 0, 0), irregular.update(key, point(10, 0, 0), 100));
    }

    @Test
    void interruptedFocusStartsFromCurrentOutputNotOldUnit() {
        var transition = initializedSwitch();
        var halfway = transition.update(SWITCH_KEY, point(10, 0, 0), DURATION / 2);
        Object next = new Object();
        assertEquals(halfway, transition.update(next, point(-50, 5, 2), 0.02));
        assertEquals(point(-50, 5, 2), transition.update(next, point(-50, 5, 2), DURATION));
    }

    @Test
    void movingFocusIsNotDoubleSmoothed() {
        var transition = new CameraFocusTransition(DURATION);
        Object target = new Object();
        transition.update(target, ORIGIN, 0);
        for (int i = 1; i <= 100; i++) {
            var interpolatedMovement = point(i * 0.05, i * 0.01, -i * 0.1);
            assertEquals(interpolatedMovement, transition.update(target, interpolatedMovement, 1.0 / 144));
        }
    }

    @Test
    void resetDropsPreviousLevelCoordinates() {
        var transition = initializedSwitch();
        transition.reset();
        var newDimension = point(-100000, 80, 200000);
        assertEquals(newDimension, transition.update(new Object(), newDimension, 0.1));
    }

    @Test
    void pausedOrInvalidElapsedTimeDoesNotAdvanceTransition() {
        var transition = initializedSwitch();
        for (double delta : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(ORIGIN, transition.update(SWITCH_KEY, point(10, 0, 0), delta));
        }
    }

    @Test
    void invalidDurationIsRejected() {
        for (double duration : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CameraFocusTransition(duration));
        }
    }

    private static final Object SWITCH_KEY = new Object();

    private static CameraFocusTransition initializedSwitch() {
        var transition = new CameraFocusTransition(DURATION);
        transition.update(new Object(), ORIGIN, 0);
        transition.update(SWITCH_KEY, point(10, 0, 0), 0);
        return transition;
    }

    private static CameraFocusTransition.Point point(double x, double y, double z) {
        return new CameraFocusTransition.Point(x, y, z);
    }

    private static void assertPoint(CameraFocusTransition.Point expected, CameraFocusTransition.Point actual) {
        assertEquals(expected.x(), actual.x(), 1.0e-10);
        assertEquals(expected.y(), actual.y(), 1.0e-10);
        assertEquals(expected.z(), actual.z(), 1.0e-10);
    }
}
