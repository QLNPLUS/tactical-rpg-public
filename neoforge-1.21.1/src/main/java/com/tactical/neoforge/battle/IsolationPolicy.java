package com.tactical.neoforge.battle;

/**
 * Pure decision rules for {@link MobIsolation}.
 *
 * <p>Split out so the exclusion contract can be unit tested without a server: when the roster is
 * full, when a failed placement may be retried, and which mobs count as intruders. The neatness
 * matters - these are exactly the three places where the old behaviour (a silent refusal) turned
 * into a mob that kept crowding the participants.</p>
 */
final class IsolationPolicy {

    private IsolationPolicy() {
    }

    /**
     * The single threshold: exclusion is active only while the encounter already holds its cap.
     * Below it, ordinary admission owns every mob and nothing may be held out.
     */
    static boolean holdsWhileFull(int hostileCount, int capacity) {
        return capacity > 0 && hostileCount >= capacity;
    }

    /** A failed placement may be retried later, up to a bounded number of attempts. */
    static boolean retryDue(int attempts, int retryAt, int now, int maxAttempts) {
        return attempts < maxAttempts && now >= retryAt;
    }

    /**
     * A mob is an intruder when it is inside the battlefield, when it hunts one of this
     * encounter's members, or when it is close enough to displace a member without any target.
     * The last case is why damage protection alone was never enough.
     */
    static boolean intrudes(boolean insideZone, boolean targetsMember, double distanceToMemberSqr,
            double contactRadiusSqr) {
        return insideZone || targetsMember || distanceToMemberSqr <= contactRadiusSqr;
    }
}
