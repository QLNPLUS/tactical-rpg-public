package com.tactical.neoforge.client;

/** Local-clock preview lifetime and one in-flight request per unchanged aim context. */
final class AttackRangeRefresh {
    static final int REFRESH_TICKS = 4;
    static final int MAX_AGE_TICKS = 20;
    private int sequence;
    private int pendingId;
    private long requestedTick = Long.MIN_VALUE;
    private long snapshotRequestTick = Long.MIN_VALUE;

    /** Returns zero while a response is in flight, or until the refresh interval elapses. */
    int request(long now) {
        if (pendingId != 0 && elapsed(now, requestedTick, MAX_AGE_TICKS)) return 0;
        if (elapsed(now, requestedTick, REFRESH_TICKS)) return 0;
        pendingId = ++sequence;
        if (pendingId == 0) pendingId = ++sequence;
        requestedTick = now;
        return pendingId;
    }

    boolean accept(int requestId, long now) {
        if (pendingId == 0 || requestId != pendingId || !elapsed(now, requestedTick, MAX_AGE_TICKS)) return false;
        // Bound age from sending, not receiving: a late response must not revive an old preview.
        snapshotRequestTick = requestedTick;
        pendingId = 0;
        return true;
    }

    boolean fresh(long now) {
        return elapsed(now, snapshotRequestTick, MAX_AGE_TICKS);
    }

    /** Invalidate both the displayed preview and old replies without reusing their request ids. */
    void invalidate() {
        pendingId = 0;
        requestedTick = snapshotRequestTick = Long.MIN_VALUE;
    }

    private static boolean elapsed(long now, long since, int limit) {
        return since != Long.MIN_VALUE && now >= since && now - since < limit;
    }
}
