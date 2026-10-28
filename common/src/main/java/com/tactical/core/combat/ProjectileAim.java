package com.tactical.core.combat;

/** Low-arc aim for vanilla arrows (0.99 drag, 0.05 gravity per tick). */
public final class ProjectileAim {
    private ProjectileAim() { }

    public static float pitch(double horizontal, double vertical, double speed) {
        if (horizontal < 1.0E-6) return vertical >= 0 ? -90 : 90;
        double low = -Math.PI * 0.45;
        double high = Math.min(Math.PI * 0.49,
                Math.max(Math.PI * 0.25, Math.atan2(vertical, horizontal) + 0.2));
        for (int i = 0; i < 40; i++) {
            double angle = (low + high) * 0.5;
            if (heightAt(horizontal, speed, angle) < vertical) low = angle;
            else high = angle;
        }
        return (float) -Math.toDegrees((low + high) * 0.5);
    }

    static double heightAt(double distance, double speed, double angle) {
        double vx = speed * Math.cos(angle), vy = speed * Math.sin(angle);
        double x = 0, y = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (x + vx >= distance) return y + vy * (distance - x) / vx;
            x += vx;
            y += vy;
            vx *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return -Double.MAX_VALUE;
    }
}
