package com.tactical.neoforge.client.ui.character;

import com.tactical.neoforge.network.CharacterStatePacket;

/**
 * The bridge between the client's answer cache and the open page.
 *
 * <p>Pure on purpose. The network handler only knows {@code CharacterClientState}; the page must still see
 * every answer exactly once, including the answers that carry no numbers, and must not be confused by a cache
 * that never changes its request id. Three rules do that:</p>
 *
 * <ul>
 *   <li><b>Identity, not just the request id.</b> Two consecutive server pushes can both be request {@code 0}
 *       with the same revision and still differ in the balance, the derived values or {@code canMutate}; they
 *       are different answers and both must arrive. Identity is the whole answer, compared fact by fact (see
 *       {@link #sameAnswer}), so a genuine duplicate is dropped and a new push of the same request is not.</li>
 *   <li><b>The unreadable answer travels separately.</b> It is stored in its own slot, so a bridge that only
 *       read {@code current()} would never release the wait it answers. It is delivered once, it only ends the
 *       wait it belongs to (the model applies the stale gate), and it triggers a snapshot refresh - never a
 *       replay of the upgrade or the allocation.</li>
 *   <li><b>Preview or state.</b> A preview answer that the server really previewed goes to the preview path; a
 *       refused preview ({@code draftValid=false}) and every commit answer go to the general path, so a refusal
 *       still releases the wait.</li>
 * </ul>
 */
public final class CharacterAnswerBridge {

    /** What the bridge reads from the client cache. Production reads {@code CharacterClientState}. */
    public interface Source {
        CharacterStatePacket current();

        boolean needsRefresh();

        CharacterStatePacket refreshNotice();

        void clearRefreshDemand();
    }

    private CharacterStatePacket lastState;
    private CharacterStatePacket lastNotice;

    /** Marks an answer as already shown, e.g. the snapshot a freshly opened page adopted from the cache. */
    public void markSeen(CharacterStatePacket packet) {
        if (packet == null) return;
        if (packet.syncAvailable()) lastState = packet;
        else lastNotice = packet;
    }

    /**
     * Delivers whatever is new in the cache to the page.
     *
     * @return true when the page's model or presentation should be refreshed
     */
    public boolean deliver(CharacterPanelModel model, Source source) {
        if (model == null || source == null) return false;
        boolean changed = false;
        if (source.needsRefresh()) {
            CharacterStatePacket notice = source.refreshNotice();
            if (notice != null && !sameAnswer(notice, lastNotice) && model.acceptUnavailable(notice)) {
                lastNotice = notice;
                changed = true;
            }
            // Consumed either way: the cache only reports the demand, the page owns the reaction.
            source.clearRefreshDemand();
            if (model.ensureRefreshRequested()) changed = true;
        }
        CharacterStatePacket packet = source.current();
        if (packet != null && !sameAnswer(packet, lastState)) {
            lastState = packet;
            if (packet.draftValid() && packet.syncAvailable()) model.acceptPreview(packet);
            else model.acceptPacket(packet);
            if (model.ensureRefreshRequested()) changed = true;
            changed = true;
        }
        return changed;
    }

    /**
     * Whether two answers are the same answer, fact by fact.
     *
     * <p>A request id and a revision do not make two answers equal: a server push under the same request id can
     * carry a different balance, different derived values, a different {@code canMutate} or an unreadable body,
     * and the page must see that one. So the comparison covers every value the page can show, not a subset of
     * metadata, and it does so by reading the packets rather than by keeping a hash of them.</p>
     *
     * <p>The refusal <em>text</em> is not compared: it is derived from {@code reasonCode}, which is, and a
     * component's identity would only ever report "different" and deliver the same answer twice.</p>
     */
    static boolean sameAnswer(CharacterStatePacket a, CharacterStatePacket b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.requestId() == b.requestId()
                && a.revision() == b.revision()
                && a.openScreen() == b.openScreen()
                && a.profileValid() == b.profileValid()
                && a.canMutate() == b.canMutate()
                && a.syncAvailable() == b.syncAvailable()
                && a.draftValid() == b.draftValid()
                && a.level() == b.level()
                && a.maxLevel() == b.maxLevel()
                && a.statCap() == b.statCap()
                && a.pointsPerLevel() == b.pointsPerLevel()
                && a.nextLevelPoints() == b.nextLevelPoints()
                && a.granted() == b.granted()
                && a.unspent() == b.unspent()
                && a.xpAvailable() == b.xpAvailable()
                && a.xpToNextLevel() == b.xpToNextLevel()
                && java.util.Objects.equals(a.owner(), b.owner())
                && java.util.Objects.equals(a.reasonCode(), b.reasonCode())
                && java.util.Arrays.equals(a.coefficients(), b.coefficients())
                && java.util.Arrays.equals(a.invested(), b.invested())
                && java.util.Arrays.equals(a.display(), b.display())
                && java.util.Arrays.equals(a.derived(), b.derived())
                && java.util.Arrays.equals(a.draftIncrements(), b.draftIncrements())
                && java.util.Arrays.equals(a.draftDerived(), b.draftDerived());
    }
}
