package com.tactical.neoforge.client;

/** Small dependency-free frame-clock helpers; no tick-based animation sampling. */
final class EntranceTiming {
    static final int DURATION_MS = 200;
    private long previousFrame;
    private int samples;
    private long totalNanos;
    private long worstNanos;
    private int over25ms;

    void frame(long now) {
        if (previousFrame != 0 && now > previousFrame) {
            long delta = now - previousFrame;
            samples++;
            totalNanos += delta;
            worstNanos = Math.max(worstNanos, delta);
            if (delta > 25_000_000L) over25ms++;
        }
        previousFrame = now;
    }

    int samples() { return samples; }
    double meanMs() { return samples == 0 ? 0 : totalNanos / (samples * 1_000_000.0); }
    double worstMs() { return worstNanos / 1_000_000.0; }
    int over25ms() { return over25ms; }
}
