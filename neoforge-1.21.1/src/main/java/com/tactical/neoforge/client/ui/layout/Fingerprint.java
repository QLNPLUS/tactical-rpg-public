package com.tactical.neoforge.client.ui.layout;

/**
 * A tiny stable hash for "has anything the layout depends on changed?".
 *
 * <p>Layouts are cached because rebuilding them per frame is wasteful, and a cache needs a cheap way
 * to notice a change. Comparing whole snapshots would allocate; a 64-bit fingerprint does not, and a
 * collision only means "rebuild anyway", never a wrong layout that never updates - every input is
 * also compared, so the hash only has to be good enough to avoid constant rebuilds.</p>
 */
public final class Fingerprint {

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private Fingerprint() {
    }

    public static long start() {
        return OFFSET;
    }

    public static long mix(long hash, long value) {
        return (hash ^ value) * PRIME;
    }

    /** Null-safe: a missing string is its own value, not a silent zero collision with "". */
    public static long text(long hash, String text) {
        long result = hash;
        if (text == null) return mix(result, 0L);
        for (int index = 0; index < text.length(); index++) {
            result ^= text.charAt(index);
            result *= PRIME;
        }
        return mix(result, 1L);
    }
}
