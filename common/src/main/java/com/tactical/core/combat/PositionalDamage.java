package com.tactical.core.combat;

/** Pure positional rules. Yaw zero faces +Z, yaw 90 faces -X. */
public final class PositionalDamage {
    public enum Sector { FRONT, SIDE, REAR, UNKNOWN }
    public static final double MAX_FACTOR = 1_000_000.0;
    public static final Rules DEFAULTS = new Rules(1, 1.2, 1.5, 45, 45, 0.05, 0.85, 1.15);

    public record Rules(double front, double side, double rear, double frontHalfAngle,
                        double rearHalfAngle, double heightPerLevel, double heightMin, double heightMax) {
        public Rules {
            if (!validFactor(front) || !validFactor(side) || !validFactor(rear)
                    || front > MAX_FACTOR || side > MAX_FACTOR || rear > MAX_FACTOR
                    || !Double.isFinite(frontHalfAngle) || frontHalfAngle < 0 || frontHalfAngle > 90
                    || !Double.isFinite(rearHalfAngle) || rearHalfAngle < 0 || rearHalfAngle > 90
                    || !Double.isFinite(heightPerLevel) || heightPerLevel < 0
                    || !validFactor(heightMin) || !validFactor(heightMax)
                    || heightMin > heightMax || heightMax > MAX_FACTOR) {
                throw new IllegalArgumentException("Invalid positional damage rules");
            }
        }
    }

    private PositionalDamage() { }

    /** Source XZ points from target to attacker or projectile launch origin.
     * Cone boundaries are inclusive; when both touch at 90 degrees, front wins. */
    public static Sector sector(double targetYaw, double sourceX, double sourceZ,
                                double frontHalfAngle, double rearHalfAngle) {
        if (!Double.isFinite(targetYaw) || !Double.isFinite(sourceX) || !Double.isFinite(sourceZ)
                || !Double.isFinite(frontHalfAngle) || !Double.isFinite(rearHalfAngle)
                || frontHalfAngle < 0 || frontHalfAngle > 90 || rearHalfAngle < 0 || rearHalfAngle > 90) {
            return Sector.UNKNOWN;
        }
        double scale = Math.max(Math.abs(sourceX), Math.abs(sourceZ));
        if (scale == 0) return Sector.UNKNOWN;
        double x = sourceX / scale, z = sourceZ / scale;
        double yaw = Math.toRadians(targetYaw % 360);
        double facingX = -Math.sin(yaw), facingZ = Math.cos(yaw);
        double angle = Math.toDegrees(Math.atan2(Math.abs(facingX * z - facingZ * x),
                facingX * x + facingZ * z));
        if (angle <= frontHalfAngle + 1.0E-10) return Sector.FRONT;
        if (angle >= 180 - rearHalfAngle - 1.0E-10) return Sector.REAR;
        return Sector.SIDE;
    }

    public static Sector sector(double targetYaw, double sourceX, double sourceZ) {
        return sector(targetYaw, sourceX, sourceZ, DEFAULTS.frontHalfAngle(), DEFAULTS.rearHalfAngle());
    }

    public static double direction(Sector sector, Rules rules) {
        if (sector == null) return 1;
        return switch (sector) {
            case FRONT -> rules.front();
            case SIDE -> rules.side();
            case REAR -> rules.rear();
            case UNKNOWN -> 1;
        };
    }

    /** Floor each feet Y separately before subtraction; unknown heights are neutral. */
    public static double height(double sourceFeetY, double targetFeetY, Rules rules) {
        if (!Double.isFinite(sourceFeetY) || !Double.isFinite(targetFeetY)) return 1;
        if (rules.heightPerLevel() == 0) return Math.max(rules.heightMin(), Math.min(rules.heightMax(), 1));
        double difference = Math.floor(sourceFeetY) - Math.floor(targetFeetY);
        return Math.max(rules.heightMin(), Math.min(rules.heightMax(), 1 + difference * rules.heightPerLevel()));
    }

    public static boolean validFactor(double factor) {
        return Double.isFinite(factor) && factor >= 0;
    }

    /** Ignore invalid input; cap each independent factor bucket at one million. */
    public static double multiply(double current, double factor) {
        if (!validFactor(factor)) return current;
        return Math.min(MAX_FACTOR, current * Math.min(MAX_FACTOR, factor));
    }
}
