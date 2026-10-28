package com.tactical.neoforge.client;

/** Pure render-focus easing. Movement remains the responsibility of entity interpolation. */
public final class CameraFocusTransition {
    public record Point(double x, double y, double z) {
        Point subtract(Point other) {
            return new Point(x - other.x, y - other.y, z - other.z);
        }

        Point addScaled(Point other, double scale) {
            return new Point(x + other.x * scale, y + other.y * scale, z + other.z * scale);
        }
    }

    private final double durationSeconds;
    private Object targetKey;
    private Point position;
    private Point offset = new Point(0, 0, 0);
    private double elapsed;

    public CameraFocusTransition(double durationSeconds) {
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("Transition duration must be positive and finite");
        }
        this.durationSeconds = durationSeconds;
    }

    public void reset() {
        targetKey = null;
        position = null;
        offset = new Point(0, 0, 0);
        elapsed = 0;
    }

    /** Seed with the previous camera focus to avoid snapping on combat entry. */
    public void seed(Point point) {
        reset();
        position = point;
    }

    public Point update(Object key, Point target, double deltaSeconds) {
        if (position == null) {
            targetKey = key;
            position = target;
            elapsed = durationSeconds;
            return position;
        }
        if (targetKey != key) {
            targetKey = key;
            offset = position.subtract(target);
            elapsed = 0;
        } else if (Double.isFinite(deltaSeconds) && deltaSeconds > 0) {
            elapsed = Math.min(durationSeconds, elapsed + deltaSeconds);
        }
        double t = elapsed / durationSeconds;
        // Quintic smoothstep: zero endpoint velocity and acceleration; no fixed-per-frame lerp.
        double eased = t * t * t * (t * (t * 6 - 15) + 10);
        position = target.addScaled(offset, 1 - eased);
        return position;
    }
}
