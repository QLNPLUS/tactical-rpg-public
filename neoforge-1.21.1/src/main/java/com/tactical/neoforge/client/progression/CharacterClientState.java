package com.tactical.neoforge.client.progression;

import com.tactical.neoforge.network.CharacterStatePacket;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * The client's mirror of its own authoritative character state.
 *
 * <p>This is a real cache of the last accepted snapshot, not an empty shell: it holds the answered state, the
 * request it answered, the owner it belongs to and the revision it is valid for, and it refuses anything
 * older. Three guards keep a late or foreign answer from overwriting a newer page:</p>
 *
 * <ol>
 *   <li><b>Owner.</b> The answer must belong to the player this client actually is. A payload for another
 *       account is dropped, never rendered.</li>
 *   <li><b>Revision.</b> A lower revision than the one on screen is stale; it is dropped so a reordered
 *       answer cannot roll the page back. An equal revision is accepted, because a refusal answers the same
 *       state with a reason and must reach the player.</li>
 *   <li><b>Request order.</b> Within one revision, an answer to an older request is dropped.</li>
 * </ol>
 *
 * <p>Nothing here computes a price, a level, a cap or a derived value: every number arrives from the server.
 * The class also owns the two pieces of local flow state the page needs - the request counter and the
 * "waiting for an answer, do not submit again" mark - and the pending open-screen intent, which the page
 * consumes once it is really shown.</p>
 *
 * <p>The identity source is replaceable so these rules can be tested without a running client; the
 * production path reads the local player.</p>
 */
public final class CharacterClientState {

    /** Where the client's own identity comes from; replaceable for tests. */
    public interface Identity {
        /** The local player's id, or {@code null} before a player is installed. */
        UUID owner();
    }

    private static final Identity LIVE = new Identity() {
        @Override public UUID owner() {
            Minecraft client = Minecraft.getInstance();
            return client.player == null ? null : client.player.getUUID();
        }
    };

    private static volatile Identity identity = LIVE;
    private static CharacterStatePacket current;
    private static UUID owner;
    private static int highestRequest = -1;
    private static int counter;
    private static int awaiting = -1;
    private static boolean openPending;
    /** True when an answer said the transaction stands but its live numbers could not be read back. */
    private static boolean needsRefresh;
    /** The last such answer, kept as structure so the page can say why it must refresh. */
    private static CharacterStatePacket staleNotice;

    private CharacterClientState() { }

    /** Installs a different identity source; {@code null} restores the live client. */
    public static void install(Identity replacement) { identity = replacement == null ? LIVE : replacement; }

    /** Clears the cached state (a disconnect or a world change), keeping the installed identity source. */
    public static void reset() {
        current = null;
        owner = null;
        highestRequest = -1;
        awaiting = -1;
        openPending = false;
        needsRefresh = false;
        staleNotice = null;
    }

    /**
     * Offers one arriving answer.
     *
     * @return true when it became the state on screen; false when it was foreign or older and was dropped
     */
    public static boolean accept(CharacterStatePacket packet) {
        if (packet == null) return false;
        UUID own = identity.owner();
        if (own != null && !own.equals(packet.owner())) return false;
        if (owner != null && !owner.equals(packet.owner())) reset();
        if (!packet.syncAvailable()) {
            // The transaction stands; only the fresh numbers are missing. Nothing on screen is replaced, so a
            // placeholder zero can never look like a real balance or a real derived value - but the wait this
            // client started is over, and the page learns it must ask again.
            if (awaiting >= 0 && packet.requestId() >= awaiting) awaiting = -1;
            needsRefresh = true;
            staleNotice = packet;
            return false;
        }
        if (current != null) {
            if (packet.revision() < current.revision()) return false;
            if (packet.revision() == current.revision() && packet.requestId() < highestRequest) return false;
        }
        owner = packet.owner();
        current = packet;
        needsRefresh = false;
        staleNotice = null;
        if (packet.requestId() > highestRequest) highestRequest = packet.requestId();
        if (awaiting >= 0 && packet.requestId() >= awaiting) awaiting = -1;
        if (packet.openScreen()) openPending = true;
        return true;
    }

    /** The last accepted state, or {@code null} when nothing has arrived yet. */
    public static CharacterStatePacket current() { return current; }

    /** Revision on screen, or {@code -1} when nothing has arrived yet. */
    public static long revision() { return current == null ? -1L : current.revision(); }

    /** Owner the cached state belongs to, or {@code null}. */
    public static UUID owner() { return owner; }

    /** Starts a new request: returns its id and marks the page as waiting for that answer. */
    public static int beginRequest() {
        counter = counter == Integer.MAX_VALUE ? 0 : counter + 1;
        awaiting = counter;
        return counter;
    }

    /** True while an answer to the outstanding request has not arrived; the page disables repeat submits. */
    public static boolean awaiting() { return awaiting >= 0; }

    /** The request id being waited for, or {@code -1}. */
    public static int awaitingRequest() { return awaiting; }

    /** True when the server asked this client to open the page and the page has not consumed it yet. */
    public static boolean takeOpenRequest() {
        boolean pending = openPending;
        openPending = false;
        return pending;
    }

    /** Whether an open request is pending, without consuming it. */
    public static boolean openRequestPending() { return openPending; }

    /**
     * True when the last answer said "committed, but the fresh numbers are not readable yet".
     *
     * <p>The page must show itself as out of date and ask for a new snapshot; it must not present the older
     * numbers as current and must not let anything be changed on top of them.</p>
     */
    public static boolean needsRefresh() { return needsRefresh; }

    /** The structured notice that asked for a refresh, or null; kept for the page's own wording. */
    public static CharacterStatePacket refreshNotice() { return staleNotice; }

    /** Clears the refresh demand, e.g. after a fresh snapshot was requested. */
    public static void clearRefreshDemand() {
        needsRefresh = false;
        staleNotice = null;
    }
}
