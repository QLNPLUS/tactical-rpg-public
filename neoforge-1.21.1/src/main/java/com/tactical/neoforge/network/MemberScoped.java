package com.tactical.neoforge.network;

import java.util.UUID;

/**
 * A payload whose content only means something for one player's current membership.
 *
 * <p>The client shows one encounter at a time and the server alone decides which. Every display
 * payload therefore carries the membership it was built for: the server stamps
 * {@code (serverEpoch, membershipGeneration)} at the moment of sending to that recipient, and the
 * client applies it only while that pair plus the encounter id equals its current active membership.
 * A payload prepared before a transition can therefore never be applied after it, and a payload built
 * for one member is never reused for another.</p>
 */
public interface MemberScoped {

    /** The encounter this payload belongs to; null only for a lifecycle reset. */
    UUID membershipSession();

    /** This payload as seen by a recipient whose membership is at {@code serverEpoch/generation}. */
    MemberScoped withMembership(long serverEpoch, long generation);

    /** Server instance the stamp belongs to; a restarted server starts a new epoch. */
    long membershipServerEpoch();

    /** Monotonic membership generation of the recipient this payload was stamped for. */
    long membershipGeneration();
}
