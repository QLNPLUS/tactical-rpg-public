package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.network.CharacterStatePacket;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache-to-page bridge, driven the way the production pump drives it.
 *
 * <p>These are the cases a model-only test cannot reach: a request id that keeps growing across pages, an
 * answer that carries no numbers at all, and two server pushes that share request {@code 0}.</p>
 */
class CharacterAnswerBridgeTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    /** The production numbering: one counter for the whole client, shared by every page instance. */
    private static final AtomicInteger SHARED_REQUESTS = new AtomicInteger();

    private static final class Recorder implements CharacterPanelModel.Actions {
        final List<String> calls = new ArrayList<>();
        int lastRequest = -1;
        /** The revision of the last request that carried one, so the page's own numbers can be asserted. */
        long lastRevision = -1L;
        int[] lastIncrements;

        @Override public void requestSnapshot(int requestId, boolean openScreen) {
            calls.add("snapshot:" + openScreen);
            lastRequest = requestId;
        }

        @Override public void requestPreview(int requestId, long revision, int[] increments) {
            calls.add("preview");
            lastRequest = requestId;
            lastRevision = revision;
            lastIncrements = increments;
        }

        @Override public void requestUpgrade(int requestId, long revision) {
            calls.add("upgrade");
            lastRequest = requestId;
        }

        @Override public void requestAllocate(int requestId, long revision, int[] increments) {
            calls.add("allocate");
            lastRequest = requestId;
        }

        long count(String prefix) {
            return calls.stream().filter(call -> call.startsWith(prefix)).count();
        }
    }

    /** The client cache as the bridge reads it. */
    private static final class Cache implements CharacterAnswerBridge.Source {
        CharacterStatePacket state;
        CharacterStatePacket notice;
        boolean needsRefresh;

        @Override public CharacterStatePacket current() { return state; }

        @Override public boolean needsRefresh() { return needsRefresh; }

        @Override public CharacterStatePacket refreshNotice() { return notice; }

        @Override public void clearRefreshDemand() { needsRefresh = false; }
    }

    private static CharacterStatePacket packet(int requestId, long revision, boolean draftValid, int unspent,
                                               boolean syncAvailable, ProgressionReason reason, int[] invested,
                                               int[] draft) {
        int[] display = new int[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) display[stat.index()] = CharacterStat.BASE + invested[stat.index()];
        return new CharacterStatePacket(OWNER, requestId, false, true, true, syncAvailable, reason.name(),
                Component.translatable(reason.key()), revision, 3, 100, 99, new double[]{2, 5, 2, .5, .01, .02}, 1, 1,
                2, unspent, 400L, 100L, invested, display, new double[CharacterStat.COUNT], draft, draftValid,
                new double[CharacterStat.COUNT]);
    }

    private static CharacterPanelModel model(Recorder recorder) {
        return new CharacterPanelModel(recorder, SHARED_REQUESTS::incrementAndGet);
    }

    @Test void aNewPageNeverReusesAnOlderRequestId() {
        Recorder first = new Recorder();
        CharacterPanelModel page = model(first);
        page.acceptPacket(packet(1, 5L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]));
        page.requestSnapshot(false);
        int firstId = first.lastRequest;

        // The player closes the page and opens it again: a fresh model and a fresh bridge, one shared counter.
        Recorder second = new Recorder();
        CharacterPanelModel reopened = model(second);
        reopened.requestSnapshot(false);
        assertTrue(second.lastRequest > firstId, "the reopened page asks with a larger id");
    }

    @Test void aCachedAnswerFromAnEarlierVisitDoesNotReleaseTheFreshRequest() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        CharacterStatePacket cached = packet(3, 5L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(page.seed(cached), "the page may show what the client already knows");
        page.requestSnapshot(false);
        assertTrue(page.waiting(), "the fresh request is still outstanding");

        // The bridge must not present the seeded answer as its answer, even though it is the current cache value.
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        bridge.markSeen(cached);
        Cache cache = new Cache();
        cache.state = cached;
        assertFalse(bridge.deliver(page, cache), "nothing new arrived");
        assertTrue(page.waiting(), "an earlier visit's answer never unlocks this request");
    }

    @Test void anUnavailableAnswerTravelsThroughTheBridgeAndOnlyAsksForASnapshot() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        page.acceptPacket(packet(1, 5L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]));
        assertTrue(page.add(CharacterStat.VIGOR), "a draft is made");
        // The preview answer releases the preview wait, exactly as the pump would after the round trip.
        page.acceptPreview(packet(recorder.lastRequest, 5L, true, 1, true, ProgressionReason.OK, new int[6],
                new int[]{1, 0, 0, 0, 0, 0}));
        assertTrue(page.requestAllocate(), "the allocation is submitted");
        int sentId = recorder.lastRequest;
        assertTrue(page.waiting());

        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        cache.state = packet(1, 5L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]);
        // The page adopted this snapshot when it opened, exactly as the production page does, so the bridge
        // must not hand it back - a stale, never-shown answer may not clear the refresh demand either.
        bridge.markSeen(cache.state);
        cache.notice = packet(sentId, 6L, false, 1, false, ProgressionReason.SYNC_UNAVAILABLE, new int[6],
                new int[6]);
        cache.needsRefresh = true;
        long snapshotsBefore = recorder.count("snapshot");
        assertTrue(bridge.deliver(page, cache), "the notice reaches the page");
        // The wait the notice answers was the allocation; the page now waits for the one snapshot it asked for.
        assertEquals(CharacterPanelModel.Pending.SNAPSHOT, page.pending(),
                "the allocation wait is over and exactly one refresh is in flight");
        assertTrue(page.waiting(), "the page waits for the fresh snapshot, it is not simply unlocked");
        assertTrue(page.refreshNeeded(), "the page knows it is out of date");
        assertEquals(ProgressionReason.SYNC_UNAVAILABLE, page.readOnly());
        assertEquals(snapshotsBefore + 1, recorder.count("snapshot"), "exactly one refresh is asked for");
        assertEquals(1L, recorder.count("allocate"), "the allocation is never replayed");
        assertEquals(0L, recorder.count("upgrade"), "and no upgrade is invented");
        assertEquals(5L, page.revision(), "the last real revision stays on screen");
        // The demand is consumed: a second pump does not ask again.
        assertFalse(bridge.deliver(page, cache), "the same demand is not delivered twice");
        assertEquals(snapshotsBefore + 1, recorder.count("snapshot"));
    }

    @Test void aNewAnswerWithTheSameRequestAndRevisionButDifferentFactsStillArrives() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        cache.state = packet(0, 7L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache));
        assertEquals(1, page.state().unspent());

        // Same request id, same revision, different ledger: that is a new answer, not a duplicate.
        cache.state = packet(0, 7L, false, 4, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache), "a same-id same-revision answer with new facts is delivered");
        assertEquals(4, page.state().unspent(), "the page shows the new balance");

        // A different draft/pool is new too, and an exact replay of the newest answer is not.
        cache.state = packet(0, 7L, true, 4, true, ProgressionReason.OK, new int[]{1, 0, 0, 0, 0, 0},
                new int[]{1, 0, 0, 0, 0, 0});
        assertTrue(bridge.deliver(page, cache), "the preview flag and its increments are facts as well");
        assertFalse(bridge.deliver(page, cache), "replaying the same immutable answer changes nothing");
    }

    @Test void anOlderUnavailableAnswerCannotReleaseANewerWait() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        page.acceptPacket(packet(1, 5L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]));
        page.add(CharacterStat.VIGOR);
        page.acceptPreview(packet(recorder.lastRequest, 5L, true, 1, true, ProgressionReason.OK, new int[6],
                new int[]{1, 0, 0, 0, 0, 0}));
        page.requestAllocate();
        int sentId = recorder.lastRequest;
        // A notice for an earlier request arrives late: it must not release the current wait.
        assertFalse(page.acceptUnavailable(packet(sentId - 1, 4L, false, 1, false,
                ProgressionReason.SYNC_UNAVAILABLE, new int[6], new int[6])));
        assertTrue(page.waiting());
        assertTrue(page.acceptUnavailable(packet(sentId, 6L, false, 1, false,
                ProgressionReason.SYNC_UNAVAILABLE, new int[6], new int[6])));
        assertFalse(page.waiting());
    }

    @Test void twoServerPushesSharingRequestZeroBothArrive() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        cache.state = packet(0, 7L, false, 0, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache));
        assertEquals(7L, page.revision());
        // The same request id, a newer revision: a different answer, not a duplicate.
        cache.state = packet(0, 8L, false, 1, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache), "a newer push under the same request id is not dropped");
        assertEquals(8L, page.revision());
        // An exact replay is a duplicate and changes nothing.
        assertFalse(bridge.deliver(page, cache));
    }

    @Test void aRefusedPreviewStillReleasesTheOnePreviewInFlight() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        page.acceptPacket(packet(1, 5L, false, 3, true, ProgressionReason.OK, new int[6], new int[6]));
        page.add(CharacterStat.VIGOR);
        // A read-only preview is not a permission wait; what it owns is the one preview slot.
        assertFalse(page.waiting());
        assertTrue(page.previewPending());
        int previewId = recorder.lastRequest;
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        cache.state = packet(previewId, 5L, false, 3, true, ProgressionReason.STAT_CAP, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache));
        assertFalse(page.previewPending(), "a refused preview is still an answer and frees the slot");
        assertFalse(page.waiting(), "and the draft stays editable");
        assertTrue(page.canAdd(CharacterStat.VIGOR), "the player may keep editing after a refused preview");
        assertNotNull(page.notice(0L), "its reason is shown");
    }

    /**
     * The cache keeps one answer, so a preview reply can be overtaken and never delivered at all.
     *
     * <p>This is the hole the page must survive: a preview is travelling, the player confirms the draft, and
     * the allocation's answer is the only one that reaches the cache. If the vanished preview were still marked
     * in flight, every later preview would queue behind it forever. The test deliberately delivers only the
     * newest packet, exactly like {@code CharacterClientState} does.</p>
     */
    @Test void aPreviewReplacedByANewerAnswerDoesNotWedgeThePage() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        page.acceptPacket(packet(1, 5L, false, 2, true, ProgressionReason.OK, new int[6], new int[6]));
        page.add(CharacterStat.VIGOR);
        assertEquals(1L, recorder.count("preview"));
        assertTrue(page.requestAllocate(), "the draft can be confirmed while its preview travels");
        int allocate = recorder.lastRequest;
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        // Only the allocation's answer is in the cache; the older preview reply is simply never delivered.
        cache.state = packet(allocate, 6L, false, 1, true, ProgressionReason.OK,
                new int[]{1, 0, 0, 0, 0, 0}, new int[6]);
        assertTrue(bridge.deliver(page, cache));
        assertFalse(page.waiting(), "the allocation is over");
        assertFalse(page.previewPending(), "the replaced preview no longer holds the slot");
        assertTrue(page.add(CharacterStat.VIGOR), "the fresh point can be drafted");
        assertEquals(2L, recorder.count("preview"), "and its preview really goes out");
        assertEquals(6L, recorder.lastRevision, "for the revision the committed answer established");
    }

    /** A late reply to a preview that was already overtaken is dropped instead of drawn. */
    @Test void aLateReplyToAnOvertakenPreviewIsDropped() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        page.acceptPacket(packet(1, 5L, false, 3, true, ProgressionReason.OK, new int[6], new int[6]));
        page.add(CharacterStat.VIGOR);
        int stalePreview = recorder.lastRequest;
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        // A newer authoritative answer overtakes the preview.
        int overtaking = recorder.lastRequest + 1;
        cache.state = packet(overtaking, 5L, false, 3, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache));
        assertNull(page.previewDerived(), "the overtaken preview gives the page no values");
        assertFalse(page.acceptPreview(packet(stalePreview, 5L, true, 3, true, ProgressionReason.OK, new int[6],
                        new int[]{1, 0, 0, 0, 0, 0})),
                "its late reply is older than what overtook it");
        assertNull(page.previewDerived(), "and still paints nothing");
    }

    /**
     * A permission refresh voids the preview in flight, and that voided preview's reply releases nothing.
     *
     * <p>The page really sent a preview, then asked for a permission snapshot - and asking for the snapshot
     * voids the preview, because the numbers it was computed against are exactly the ones the refresh may
     * replace. The cache hands over the older preview reply first, then the snapshot. The voided reply answers a
     * request the page no longer waits for: it may not paint a derived value, and above all it may not release
     * the snapshot that is really outstanding. Only the snapshot's own answer ends that wait, and the newest
     * draft is asked about exactly once afterwards.</p>
     */
    @Test void aVoidedPreviewReplyNeverPaintsNorReleasesThePermissionRefresh() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        page.acceptPacket(packet(1, 5L, false, 3, true, ProgressionReason.OK, new int[6], new int[6]));
        assertTrue(page.add(CharacterStat.VIGOR), "a draft is made and its preview really goes out");
        int voidedPreview = recorder.lastRequest;
        assertEquals(1L, recorder.count("preview"));

        page.markPermissionStale();
        assertTrue(page.ensurePermissionRefresh(), "the permission snapshot outranks the travelling preview");
        int snapshot = recorder.lastRequest;
        assertTrue(snapshot > voidedPreview, "the snapshot is the newer request");
        assertFalse(page.previewPending(), "the voided preview no longer holds the slot");
        assertEquals(CharacterPanelModel.Pending.SNAPSHOT, page.pending());

        // The older preview reply is what the cache holds first, exactly like the production cache.
        CharacterAnswerBridge bridge = new CharacterAnswerBridge();
        Cache cache = new Cache();
        cache.state = packet(voidedPreview, 5L, true, 3, true, ProgressionReason.OK, new int[6],
                new int[]{1, 0, 0, 0, 0, 0});
        assertTrue(bridge.deliver(page, cache), "the cache has an answer the page has not seen");
        assertNull(page.previewDerived(), "a preview of a voided request paints nothing");
        assertEquals(CharacterPanelModel.Pending.SNAPSHOT, page.pending(),
                "and it does not release the snapshot that is really outstanding");
        assertTrue(page.waiting());
        assertEquals(1L, recorder.count("preview"), "no preview is queued while the snapshot travels");
        assertEquals(0L, recorder.count("allocate"), "no allocation is replayed");
        assertEquals(0L, recorder.count("upgrade"), "and no level-up is invented");

        // The snapshot itself lands: the wait is over and the newest draft is previewed exactly once.
        cache.state = packet(snapshot, 5L, false, 3, true, ProgressionReason.OK, new int[6], new int[6]);
        assertTrue(bridge.deliver(page, cache));
        assertFalse(page.waiting(), "the snapshot answered the refresh");
        assertTrue(page.previewPending(), "and the newest draft's preview is now the one travelling");
        assertEquals(2L, recorder.count("preview"), "the draft is asked about exactly once afterwards");
        assertEquals(5L, recorder.lastRevision, "for the revision the page shows");
        assertEquals(1, recorder.lastIncrements[CharacterStat.VIGOR.index()],
                "and it describes the draft as it stands now");
    }

    @Test void onePageNumberingStaysMonotonicWhenTheCounterWraps() {
        AtomicInteger counter = new AtomicInteger(Integer.MAX_VALUE - 1);
        Recorder recorder = new Recorder();
        CharacterPanelModel page = new CharacterPanelModel(recorder, counter::incrementAndGet);
        page.requestSnapshot(false);
        int first = recorder.lastRequest;
        counter.set(0);
        page.requestSnapshot(false);
        assertNotEquals(first, recorder.lastRequest, "a wrapped counter never repeats the id just used");
    }

    @Test void aSeededPageWithoutAStateWaitsForItsOwnAnswer() {
        Recorder recorder = new Recorder();
        CharacterPanelModel page = model(recorder);
        assertNull(page.state());
        assertFalse(page.seed(null));
        page.requestSnapshot(false);
        assertTrue(page.waiting());
        assertTrue(page.acceptPacket(packet(recorder.lastRequest, 9L, false, 2, true, ProgressionReason.OK,
                new int[6], new int[6])));
        assertFalse(page.waiting());
        assertNotNull(page.state());
    }
}
