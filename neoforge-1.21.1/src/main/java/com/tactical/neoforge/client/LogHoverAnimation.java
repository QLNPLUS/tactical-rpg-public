package com.tactical.neoforge.client;

/** Pure single-hover marquee clock. The clip window never moves; no input is consumed. */
final class LogHoverAnimation {
    static final long START_DELAY_MS = 850;
    static final long END_PAUSE_MS = 1200;
    static final double PIXELS_PER_SECOND = 30;
    private String key;
    private long startedAt;
    private double lastOverflow;

    double offset(String hoveredKey, double overflow, double scale, long now) {
        if (hoveredKey == null || !Double.isFinite(overflow) || overflow <= .5) {
            reset();
            return 0;
        }
        if (!hoveredKey.equals(key) || Math.abs(lastOverflow - overflow) > .5 || now < startedAt) {
            key = hoveredKey;
            startedAt = now;
            lastOverflow = overflow;
        }
        double speed = PIXELS_PER_SECOND * Math.max(.1, scale);
        long travel = Math.max(1, (long) Math.ceil(overflow / speed * 1000));
        long cycle = START_DELAY_MS + travel + END_PAUSE_MS;
        long phase = Math.max(0, now - startedAt) % cycle;
        return Math.min(overflow, Math.max(0, phase - START_DELAY_MS) * speed / 1000);
    }

    void reset() { key = null; startedAt = 0; lastOverflow = 0; }
}
