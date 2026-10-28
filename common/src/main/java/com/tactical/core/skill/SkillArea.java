package com.tactical.core.skill;

import java.util.List;
import java.util.Objects;

/** Continuous local XZ geometry. Forward is +Z; positive Minecraft yaw turns toward -X. */
public interface SkillArea {
    double EPSILON = 1.0e-9;

    Bounds bounds();
    boolean contains(double x, double z);

    /** Rigid transform; coordinates and bounds remain continuous, with inclusive edges. */
    default SkillArea transformed(double offsetX, double offsetZ, double yawDegrees) {
        finite(offsetX, offsetZ, yawDegrees);
        SkillArea source = this;
        double radians = Math.toRadians(yawDegrees % 360.0);
        double c = Math.cos(radians), s = Math.sin(radians);
        Bounds old = bounds();
        if (old.isEmpty()) return empty();
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (double x : new double[] {old.minX(), old.maxX()}) {
            for (double z : new double[] {old.minZ(), old.maxZ()}) {
                double tx = offsetX + c * x - s * z;
                double tz = offsetZ + s * x + c * z;
                minX = Math.min(minX, tx); maxX = Math.max(maxX, tx);
                minZ = Math.min(minZ, tz); maxZ = Math.max(maxZ, tz);
            }
        }
        Bounds transformed = new Bounds(minX, minZ, maxX, maxZ);
        return new SkillArea() {
            public Bounds bounds() { return transformed; }
            public boolean contains(double x, double z) {
                if (!transformed.contains(x, z)) return false;
                double dx = x - offsetX, dz = z - offsetZ;
                return source.contains(c * dx + s * dz, -s * dx + c * dz);
            }
        };
    }

    record Bounds(double minX, double minZ, double maxX, double maxZ) {
        public Bounds { finite(minX, minZ, maxX, maxZ); }
        public boolean isEmpty() { return maxX < minX || maxZ < minZ; }
        public double width() { return isEmpty() ? 0 : maxX - minX; }
        public double length() { return isEmpty() ? 0 : maxZ - minZ; }
        public boolean contains(double x, double z) {
            return Double.isFinite(x) && Double.isFinite(z) && !isEmpty()
                && x >= minX - EPSILON && x <= maxX + EPSILON
                && z >= minZ - EPSILON && z <= maxZ + EPSILON;
        }
        public Bounds union(Bounds other) {
            if (isEmpty()) return other;
            if (other.isEmpty()) return this;
            return new Bounds(Math.min(minX, other.minX), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxZ, other.maxZ));
        }
        public Bounds intersection(Bounds other) {
            if (isEmpty() || other.isEmpty()) return emptyBounds();
            return new Bounds(Math.max(minX, other.minX), Math.max(minZ, other.minZ),
                Math.min(maxX, other.maxX), Math.min(maxZ, other.maxZ));
        }
    }

    static SkillArea empty() {
        return new SkillArea() {
            public Bounds bounds() { return emptyBounds(); }
            public boolean contains(double x, double z) { return false; }
        };
    }

    static SkillArea circle(double radius) {
        dimension(radius);
        Bounds bounds = new Bounds(-radius, -radius, radius, radius);
        return new SkillArea() {
            public Bounds bounds() { return bounds; }
            public boolean contains(double x, double z) {
                return bounds.contains(x, z) && Math.hypot(x, z) <= radius + EPSILON;
            }
        };
    }

    /** Rectangle centered across X, extending forward from Z=0 to length. */
    static SkillArea rectangle(double width, double length) {
        dimension(width); dimension(length);
        Bounds bounds = new Bounds(-width / 2, 0, width / 2, length);
        return new SkillArea() {
            public Bounds bounds() { return bounds; }
            public boolean contains(double x, double z) { return bounds.contains(x, z); }
        };
    }

    static SkillArea line(double width, double length) { return rectangle(width, length); }

    /** Right-isosceles triangle with its right angle at the local origin. */
    static SkillArea rightTriangle(double size) {
        dimension(size);
        return triangle(0, 0, size, 0, 0, size);
    }

    static SkillArea triangle(double ax, double az, double bx, double bz, double cx, double cz) {
        finite(ax, az, bx, bz, cx, cz);
        Bounds bounds = new Bounds(Math.min(ax, Math.min(bx, cx)), Math.min(az, Math.min(bz, cz)),
            Math.max(ax, Math.max(bx, cx)), Math.max(az, Math.max(bz, cz)));
        double area = cross(bx - ax, bz - az, cx - ax, cz - az);
        return new SkillArea() {
            public Bounds bounds() { return bounds; }
            public boolean contains(double x, double z) {
                if (!bounds.contains(x, z)) return false;
                if (Math.abs(area) <= EPSILON) {
                    return onSegment(x, z, ax, az, bx, bz) || onSegment(x, z, bx, bz, cx, cz)
                        || onSegment(x, z, cx, cz, ax, az);
                }
                double ab = cross(bx - ax, bz - az, x - ax, z - az);
                double bc = cross(cx - bx, cz - bz, x - bx, z - bz);
                double ca = cross(ax - cx, az - cz, x - cx, z - cz);
                return (ab >= -EPSILON && bc >= -EPSILON && ca >= -EPSILON)
                    || (ab <= EPSILON && bc <= EPSILON && ca <= EPSILON);
            }
        };
    }

    static SkillArea union(List<SkillArea> areas) {
        List<SkillArea> children = List.copyOf(areas);
        Bounds bounds = emptyBounds();
        for (SkillArea area : children) bounds = bounds.union(area.bounds());
        Bounds result = bounds;
        return new SkillArea() {
            public Bounds bounds() { return result; }
            public boolean contains(double x, double z) {
                return result.contains(x, z) && children.stream().anyMatch(area -> area.contains(x, z));
            }
        };
    }

    static SkillArea intersection(List<SkillArea> areas) {
        List<SkillArea> children = List.copyOf(areas);
        if (children.isEmpty()) return empty();
        Bounds bounds = children.get(0).bounds();
        for (SkillArea area : children) bounds = bounds.intersection(area.bounds());
        Bounds result = bounds;
        return new SkillArea() {
            public Bounds bounds() { return result; }
            public boolean contains(double x, double z) {
                return result.contains(x, z) && children.stream().allMatch(area -> area.contains(x, z));
            }
        };
    }

    static SkillArea difference(SkillArea base, List<SkillArea> subtract) {
        Objects.requireNonNull(base, "base");
        List<SkillArea> children = List.copyOf(subtract);
        return new SkillArea() {
            public Bounds bounds() { return base.bounds(); }
            public boolean contains(double x, double z) {
                return base.contains(x, z) && children.stream().noneMatch(area -> area.contains(x, z));
            }
        };
    }

    private static Bounds emptyBounds() { return new Bounds(0, 0, -1, -1); }
    private static double cross(double ax, double az, double bx, double bz) { return ax * bz - az * bx; }
    private static boolean onSegment(double x, double z, double ax, double az, double bx, double bz) {
        return Math.abs(cross(bx - ax, bz - az, x - ax, z - az)) <= EPSILON
            && x >= Math.min(ax, bx) - EPSILON && x <= Math.max(ax, bx) + EPSILON
            && z >= Math.min(az, bz) - EPSILON && z <= Math.max(az, bz) + EPSILON;
    }
    private static void dimension(double value) {
        finite(value);
        if (value < 0) throw new IllegalArgumentException("Area dimensions must be non-negative");
    }
    private static void finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Area coordinates must be finite");
        }
    }
}
