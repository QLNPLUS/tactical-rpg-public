package com.tactical.core.progression;

import java.util.Locale;

/**
 * Every way this feature can refuse a request, as a stable code.
 *
 * <p>A code is structured data, not a sentence: the server turns it into a translatable component with
 * {@code tactical.progression.reason.<code>}, so the answer is read in the receiving player's own
 * language and the delivery document can enumerate exactly which texts exist.</p>
 */
public enum ProgressionReason {
    /** No refusal: the request was applied, or the snapshot is simply informational. */
    OK,
    /** The request's expected revision no longer matches the stored one; refresh instead of overwriting. */
    STALE_REVISION,
    /** The revision advanced because the server's growth rules changed since the page was priced. */
    RULES_CHANGED,
    /** The character is in a battle: viewing is allowed, changing levels or points is not. */
    IN_BATTLE,
    /** The player is dead or no longer in the world; spending here could duplicate dropped experience. */
    DEAD,
    /** A spectator has no character to spend on. */
    SPECTATOR,
    /** The character is already at the configured level cap. */
    LEVEL_CAP,
    /** The spent XP does not cover the next level's configured price. */
    INSUFFICIENT_XP,
    /** The draft spends more unspent points than the character owns. */
    INSUFFICIENT_POINTS,
    /** Nothing to confirm: an allocation of zero points is not an intent. */
    NOTHING_TO_ALLOCATE,
    /** One stat would exceed the configured displayed-value cap. */
    STAT_CAP,
    /** The submitted array is not six finite non-negative increments. */
    INVALID_INCREMENT,
    /** No profile exists yet and it could not be created. */
    PROFILE_UNKNOWN,
    /** The stored profile is unreadable or inconsistent; it is never reset and never grants points. */
    PROFILE_INVALID,
    /** The caller was not a server player on the server thread. */
    NOT_A_PLAYER,
    /** The XP or profile transaction failed while it was being committed; nothing was consumed. */
    SERVER_ERROR,
    /**
     * The transaction was committed, but the answer's live numbers could not be read back.
     *
     * <p>The revision and the stored points are known and travel with the answer; the experience and the
     * derived values do not, so a client must not present them as facts and must ask for a fresh snapshot.
     * Nothing was rolled back and nothing may be replayed.</p>
     */
    SYNC_UNAVAILABLE;

    public String key() { return "tactical.progression.reason." + name().toLowerCase(Locale.ROOT); }

    /** True when this reason means the request was actually applied. */
    public boolean accepted() { return this == OK; }
}
