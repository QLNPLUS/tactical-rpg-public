package com.tactical.neoforge.battle;

/**
 * Development-only switch for the two authoritative trace lines an acceptance scene needs:
 * which unit was admitted into which encounter, and where an excluded unit was moved to.
 *
 * <p>Off by default. A normal development client and every release run without
 * {@code -Dtactical.dev.trace=true} produce no extra output, so this is a diagnostic flag, not a
 * feature. It exists because a client-side mirror cannot prove an entity's server position, and the
 * scene must not infer "the mob left" from the absence of a failure line.</p>
 */
final class DeveloperTrace {

    static final String PROPERTY = "tactical.dev.trace";

    private DeveloperTrace() {
    }

    static boolean enabled() {
        try {
            return Boolean.getBoolean(PROPERTY);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
