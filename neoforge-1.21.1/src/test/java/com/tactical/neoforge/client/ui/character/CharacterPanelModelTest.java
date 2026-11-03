package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.network.CharacterStatePacket;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page's state machine: one request at a time, a draft the server's revision owns, and no local pricing.
 *
 * <p>Every rule here is one the specification states in words - "while waiting, nothing may be submitted
 * again", "a changed revision clears the draft", "a late preview may not overwrite a newer draft", "a refusal
 * refreshes the page and re-enables the controls" - so each is pinned as a plain assertion.</p>
 */
class CharacterPanelModelTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    /** Records what the page asked for, so "exactly one request" can be asserted. */
    private static final class Recorder implements CharacterPanelModel.Actions {
        final List<String> calls = new ArrayList<>();
        int lastRequest = -1;
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
            lastRevision = revision;
        }

        @Override public void requestAllocate(int requestId, long revision, int[] increments) {
            calls.add("allocate");
            lastRequest = requestId;
            lastRevision = revision;
            lastIncrements = increments;
        }

        long count(String prefix) {
            return calls.stream().filter(call -> call.startsWith(prefix)).count();
        }
    }

    private static CharacterStatePacket state(int requestId, long revision, int level, int unspent, int xp,
                                              boolean canMutate, ProgressionReason reason, int[] invested,
                                              int[] derived, int[] draft, boolean draftValid, boolean syncAvailable) {
        int[] display = new int[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) display[stat.index()] = CharacterStat.BASE + invested[stat.index()];
        double[] derivedValues = new double[CharacterStat.COUNT];
        if (derived != null) {
            for (int index = 0; index < derived.length; index++) derivedValues[index] = derived[index];
        }
        double[] draftValues = new double[CharacterStat.COUNT];
        // The fixture's rule is one derived point per drafted point, so the preview must follow the draft it
        // echoes: a two-point draft is two points up, which is what "a late answer cannot overwrite a newer
        // draft" is measured against.
        if (draftValid && derived != null) {
            for (int index = 0; index < derived.length; index++) draftValues[index] = derived[index] + draft[index];
        }
        return new CharacterStatePacket(OWNER, requestId, false, true, canMutate, syncAvailable, reason.name(),
                Component.translatable(reason.key()), revision, level, 100, 99, new double[]{2, 5, 2, .5, .01, .02}, 1,
                1, level - 1, unspent, xp, 100L, invested, display, derivedValues, draft, draftValid, draftValues);
    }

    private static CharacterStatePacket simple(int requestId, long revision, int unspent) {
        return state(requestId, revision, 3, unspent, 400, true, ProgressionReason.OK, new int[6], new int[6],
                new int[6], false, true);
    }

    @Test void nothingCanBeDraftedBeforeAnySnapshotArrives() {
        CharacterPanelModel model = new CharacterPanelModel(new Recorder());
        assertNull(model.state());
        assertFalse(model.add(CharacterStat.VIGOR), "no numbers to weigh a point against");
        assertEquals(ProgressionReason.PROFILE_UNKNOWN, model.readOnly());
        assertFalse(model.canUpgrade());
        assertFalse(model.canConfirmAllocation());
    }

    @Test void aSnapshotMakesThePageEditableAndAHiddenBalancesStopsIt() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 0));
        assertNull(model.readOnly());
        assertFalse(model.add(CharacterStat.VIGOR), "no unspent point, so nothing can be drafted");
        model.acceptPacket(simple(2, 6L, 2));
        assertTrue(model.add(CharacterStat.VIGOR), "two points may be drafted");
        assertEquals(1, model.draft().increment(CharacterStat.VIGOR));
        assertEquals("preview", recorder.calls.getLast(), "a draft change asks the server for a preview");
    }

    @Test void oneDraftChangeSendsExactlyOnePreviewAndAReadOnlyPreviewNeverLocksThePage() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        assertEquals(1L, recorder.count("preview"));
        assertFalse(model.waiting(), "a read-only preview is not a permission wait");
        assertFalse(model.previewPending() && model.waiting());
        // The second press answers locally at once: the page is not locked by the preview in flight.
        assertTrue(model.add(CharacterStat.VIGOR), "continuous editing is not blocked by a preview");
        assertEquals(2, model.draft().increment(CharacterStat.VIGOR));
        assertEquals(1L, recorder.count("preview"),
                "one preview in flight at most: the newer draft is merged, not queued twice");
        assertTrue(model.previewPending(), "the page knows a preview is still travelling");
    }

    @Test void theMergedDraftIsTheOneThatIsAskedForWhenTheAnswerLands() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        int firstPreview = recorder.lastRequest;
        model.add(CharacterStat.VIGOR);
        model.add(CharacterStat.VIGOR);
        assertEquals(1L, recorder.count("preview"), "three presses still mean one request in flight");
        // The answer to the first draft arrives while the draft already holds three points.
        model.acceptPreview(state(firstPreview, 5L, 3, 3, 400, true, ProgressionReason.OK, new int[6],
                new int[6], new int[]{1, 0, 0, 0, 0, 0}, true, true));
        assertEquals(2L, recorder.count("preview"), "exactly one merged request follows");
        assertEquals(3, recorder.lastIncrements[CharacterStat.VIGOR.index()],
                "and it describes the draft as it stands now, not a replay of every intermediate state");
        assertNull(model.previewDerived(), "no derived value is invented while the merged request travels");
    }

    /**
     * A refusal is identified by what this page sent, never by the packet's echo.
     *
     * <p>The server sends {@code draftValid=false} together with a zero array for a draft it refused, so the
     * refused draft cannot be recognised from the packet. The page therefore remembers the increments and the
     * revision of the request it really sent; the same draft is never asked again, while a genuinely different
     * draft is asked about exactly once.</p>
     */
    @Test void aRefusedPreviewIsRememberedFromWhatWasSentAndIsNeverAskedTwice() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        int preview = recorder.lastRequest;
        // The real shape of a refusal: false with the zero array, and the reason that refused it.
        model.acceptPacket(state(preview, 5L, 3, 3, 400, true, ProgressionReason.INSUFFICIENT_POINTS,
                new int[6], new int[6], new int[6], false, true));
        assertEquals(ProgressionReason.INSUFFICIENT_POINTS, model.notice(0L));
        assertFalse(model.previewPending(), "the refusal frees the one preview slot");
        assertEquals(1L, recorder.count("preview"), "the refused draft was not asked again");
        // A different legal draft is asked about exactly once.
        assertTrue(model.add(CharacterStat.MIND));
        assertEquals(2L, recorder.count("preview"));
        assertEquals(1, recorder.lastIncrements[CharacterStat.VIGOR.index()]);
        assertEquals(1, recorder.lastIncrements[CharacterStat.MIND.index()]);
    }

    /**
     * A higher profile revision voids the preview that was travelling for the old one.
     *
     * <p>The draft is dropped when the revision changes, so an answer for the old revision can describe nothing
     * on the page; leaving its request marked in flight would silence every later preview.</p>
     */
    @Test void aRevisionPushVoidsThePreviewInFlightInsteadOfWedgingIt() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        assertEquals(1L, recorder.count("preview"));
        assertTrue(model.previewPending());
        // A server push carries request 0 and a newer revision; the preview reply is never delivered.
        model.acceptPacket(simple(0, 6L, 3));
        assertTrue(model.draft().isEmpty(), "the draft was weighed against numbers that changed");
        assertFalse(model.previewPending(), "the preview of the old revision is void, not still in flight");
        assertTrue(model.add(CharacterStat.VIGOR));
        assertEquals(2L, recorder.count("preview"), "a preview is sent again for the new revision");
        assertEquals(6L, recorder.lastRevision, "and it is asked for the revision the page shows");
    }

    /**
     * The permission refresh outranks the next merged preview.
     *
     * <p>Continuous editing must not starve the one request that tells the page whether it may be edited at
     * all: while the demand is pending no preview is queued, the merged snapshot goes out once, and the newest
     * draft is asked about afterwards.</p>
     */
    @Test void aPermissionRefreshOutranksTheNextMergedPreview() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        long previews = recorder.count("preview");
        model.markPermissionStale();
        assertTrue(model.add(CharacterStat.VIGOR), "the page stays editable while the demand waits");
        assertEquals(previews, recorder.count("preview"), "no preview jumps the permission refresh");
        assertTrue(model.ensurePermissionRefresh(), "the merged permission snapshot is sent");
        assertEquals(1L, recorder.count("snapshot"));
        assertFalse(model.previewPending(), "the read-only preview was voided by the refresh");
        // The snapshot answers with the same revision: the newest draft's preview follows exactly once.
        model.acceptPacket(simple(recorder.lastRequest, 5L, 3));
        assertEquals(previews + 1, recorder.count("preview"));
        assertEquals(2, recorder.lastIncrements[CharacterStat.VIGOR.index()],
                "and it describes the draft as it stands now, not the one the preview was voided for");
    }

    /** A preview answer never displaces the allocation that is really outstanding. */
    @Test void aPreviewReplyNeverDisplacesTheAllocationInFlight() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 2));
        model.add(CharacterStat.VIGOR);
        int preview = recorder.lastRequest;
        assertTrue(model.requestAllocate());
        long previews = recorder.count("preview");
        model.acceptPreview(state(preview, 5L, 3, 2, 400, true, ProgressionReason.OK, new int[6], new int[6],
                new int[]{1, 0, 0, 0, 0, 0}, true, true));
        assertEquals(CharacterPanelModel.Pending.ALLOCATE, model.pending(),
                "the allocation is still the transaction in flight");
        assertTrue(model.waiting());
        assertFalse(model.requestAllocate(), "and no second confirmation is accepted");
        assertEquals(previews, recorder.count("preview"), "no preview is queued behind a running transaction");
        assertFalse(model.previewPending());
    }

    @Test void aLatePreviewCannotOverwriteANewerDraft() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        int firstPreview = recorder.lastRequest;
        CharacterStatePacket late = state(firstPreview, 5L, 3, 3, 400, true, ProgressionReason.OK, new int[6],
                new int[6], new int[]{1, 0, 0, 0, 0, 0}, true, true);
        // The answer for the first draft arrives after the player already changed the draft again.
        model.acceptPreview(late);
        assertEquals(1.0D, model.previewDerived()[CharacterStat.VIGOR.index()], 1.0E-9);
        model.add(CharacterStat.VIGOR);
        model.acceptPreview(state(recorder.lastRequest, 5L, 3, 3, 400, true, ProgressionReason.OK, new int[6],
                new int[6], new int[]{2, 0, 0, 0, 0, 0}, true, true));
        assertEquals(2.0D, model.previewDerived()[CharacterStat.VIGOR.index()], 1.0E-9);
        // A delayed answer that describes only one point no longer matches the two-point draft.
        assertFalse(model.acceptPreview(late), "an answer for an older draft is dropped");
        assertEquals(2.0D, model.previewDerived()[CharacterStat.VIGOR.index()], 1.0E-9);
    }

    @Test void aChangedRevisionClearsTheDraftAndSaysSo() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.MIND);
        assertFalse(model.draft().isEmpty());
        model.acceptPacket(simple(2, 6L, 3));
        assertTrue(model.draft().isEmpty(), "the draft was weighed against other numbers");
        assertTrue(model.draftStale(), "the page must tell the player to allocate again");
        assertNull(model.previewDerived(), "no stale derived value survives");
        assertFalse(model.canConfirmAllocation(), "nothing to confirm");
        // The next + clears the notice, as the specification requires.
        model.add(CharacterStat.MIND);
        assertFalse(model.draftStale());
    }

    @Test void aConfirmationIsSentOnceAndClearsTheDraftOnSuccess() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 2));
        model.add(CharacterStat.VIGOR);
        model.acceptPreview(state(recorder.lastRequest, 5L, 3, 2, 400, true, ProgressionReason.OK, new int[6],
                new int[6], new int[]{1, 0, 0, 0, 0, 0}, true, true));
        assertNotNull(model.previewDerived());
        assertTrue(model.canConfirmAllocation());
        assertTrue(model.requestAllocate());
        assertEquals(1L, recorder.count("allocate"));
        assertFalse(model.requestAllocate(), "a second confirmation is refused while one is outstanding");
        assertEquals(1L, recorder.count("allocate"));
        assertTrue(model.waiting());
        // The server's success answer advances the revision and empties the draft.
        assertTrue(model.acceptPacket(state(2, 6L, 3, 0, 400, true, ProgressionReason.OK, new int[]{1, 0, 0, 0, 0, 0},
                new int[6], new int[6], false, true)));
        assertFalse(model.waiting(), "the wait is over");
        assertTrue(model.draft().isEmpty(), "a committed draft is cleared");
        assertEquals(1, model.state().invested()[CharacterStat.VIGOR.index()]);
    }

    @Test void aRefusalIsDisplayedAndReEnablesTheControls() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 1));
        model.add(CharacterStat.VIGOR);
        model.requestAllocate();
        CharacterStatePacket refused = state(recorder.lastRequest, 5L, 3, 0, 0, true,
                ProgressionReason.INSUFFICIENT_XP, new int[6], new int[6], new int[6], false, true);
        model.acceptPacket(refused);
        assertFalse(model.waiting(), "a refusal ends the wait");
        assertEquals(ProgressionReason.INSUFFICIENT_XP, model.notice(0L));
        assertNull(model.notice(CharacterPanelModel.NOTICE_MS + 10L), "the notice fades after three seconds");
        // The controls are usable again once the server's numbers are read back.
        model.acceptPacket(simple(4, 5L, 2));
        assertTrue(model.canAdd(CharacterStat.MIND));
    }

    @Test void aCommittedButUnreadableAnswerEndsTheWaitAndAsksForAFreshSnapshot() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        CharacterStatePacket good = simple(1, 5L, 1);
        model.acceptPacket(good);
        model.add(CharacterStat.VIGOR);
        model.requestAllocate();
        CharacterStatePacket unavailable = state(recorder.lastRequest, 6L, 3, 1, 0, false,
                ProgressionReason.SYNC_UNAVAILABLE, new int[6], new int[6], new int[6], false, false);
        assertFalse(model.acceptPacket(unavailable), "no page is replaced by placeholders");
        assertFalse(model.waiting(), "the wait is over");
        assertTrue(model.refreshNeeded(), "the page asks for a fresh snapshot");
        assertEquals(ProgressionReason.SYNC_UNAVAILABLE, model.readOnly(), "the page is read-only meanwhile");
        assertNull(model.currentDerived(), "no derived value is invented");
        assertEquals(5L, model.revision(), "the last real revision stays");
    }

    @Test void aLevelUpIsItsOwnTransactionWithItsOwnConfirmation() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 0));
        assertTrue(model.canUpgrade(), "400 XP covers the next level");
        model.openUpgradePopup();
        assertTrue(model.upgradePopupOpen());
        model.cancelUpgradePopup();
        assertFalse(model.upgradePopupOpen());
        assertEquals(0L, recorder.count("upgrade"), "cancelling sends nothing");
        model.openUpgradePopup();
        assertTrue(model.requestUpgrade());
        assertEquals(1L, recorder.count("upgrade"));
        assertFalse(model.upgradePopupOpen(), "the confirmation closes as the request leaves");
        assertFalse(model.requestUpgrade(), "no second confirmation while one is outstanding");
    }

    @Test void anExpensiveLevelUpIsRefusedLocallyWithItsReason() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(state(1, 5L, 3, 0, 10, true, ProgressionReason.OK, new int[6], new int[6], new int[6],
                false, true));
        assertFalse(model.canUpgrade(), "10 XP does not cover a 100 XP level");
        model.openUpgradePopup();
        assertFalse(model.upgradePopupOpen(), "an unaffordable confirmation does not open");
        assertFalse(model.requestUpgrade());
        assertEquals(0L, recorder.count("upgrade"));
    }

    @Test void readOnlyStatesComeFromTheServerNotTheLocalBattleMirror() {
        Recorder recorder = new Recorder();
        CharacterPanelModel inBattle = new CharacterPanelModel(recorder);
        inBattle.acceptPacket(state(1, 5L, 3, 2, 400, false, ProgressionReason.IN_BATTLE, new int[6], new int[6],
                new int[6], false, true));
        assertEquals(ProgressionReason.IN_BATTLE, inBattle.readOnly());
        assertFalse(inBattle.add(CharacterStat.VIGOR), "a read-only page drafts nothing");
        assertFalse(inBattle.requestAllocate());
        CharacterPanelModel dead = new CharacterPanelModel(new Recorder());
        dead.acceptPacket(state(1, 5L, 3, 2, 400, false, ProgressionReason.DEAD, new int[6], new int[6], new int[6],
                false, true));
        assertEquals(ProgressionReason.DEAD, dead.readOnly());
        CharacterPanelModel invalid = new CharacterPanelModel(new Recorder());
        invalid.acceptPacket(new CharacterStatePacket(OWNER, 1, false, false, false, true,
                ProgressionReason.PROFILE_INVALID.name(), Component.empty(), 0L, 0, 100, 99, new double[6], 1, 1, 0,
                0, 0L, 0L, new int[6], new int[6], new double[6], new int[6], false, new double[6]));
        assertEquals(ProgressionReason.PROFILE_INVALID, invalid.readOnly());
        assertEquals(CharacterFormat.UNKNOWN, CharacterFormat.amount(Double.NaN));
        assertNull(invalid.currentDerived(), "an invalid profile shows no derived numbers");
        assertNull(invalid.display(CharacterStat.VIGOR), "and no displayed values either");
    }

    @Test void derivedValuesOnlyEverComeFromTheServerPreview() {        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 2));
        assertNull(model.previewDerived(), "no preview has been answered yet, so nothing is estimated");
        model.add(CharacterStat.VIGOR);
        // A preview for another revision is not used.
        assertFalse(model.acceptPreview(state(recorder.lastRequest, 4L, 3, 2, 400, true, ProgressionReason.OK,
                new int[6], new int[6], new int[]{1, 0, 0, 0, 0, 0}, true, true)));
        assertNull(model.previewDerived());
        assertTrue(model.acceptPreview(state(recorder.lastRequest, 5L, 3, 2, 400, true, ProgressionReason.OK,
                new int[6], new int[]{22, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0}, true, true)));
        assertEquals(23.0D, model.previewDerived()[CharacterStat.VIGOR.index()], 1.0E-9);
        // Cancelling the draft discards the preview with it.
        model.cancelDraft();
        assertNull(model.previewDerived());
        assertTrue(model.draft().isEmpty());
    }

    /**
     * The panel's geometry, as the screen derives it from the model.
     *
     * <p>It is the same mapping {@code CharacterScreen.content()} performs, so the check below measures the
     * real thing: whatever the page would lay out with these flags.
     */
    private static CharacterLayout.Content content(CharacterPanelModel model) {
        boolean[] addable = new boolean[CharacterStat.COUNT];
        boolean[] removable = new boolean[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) {
            addable[stat.index()] = model.canAdd(stat);
            removable[stat.index()] = model.canRemove(stat);
        }
        return new CharacterLayout.Content(model.state() != null && model.state().profileValid(),
                model.readOnly() != null, model.waiting(), model.upgradePopupOpen(), model.refreshNeeded(),
                model.canUpgrade(), model.canConfirmAllocation(), !model.draft().isEmpty(),
                CharacterLayout.Content.mask(addable), CharacterLayout.Content.mask(removable));
    }

    private static CharacterLayout layoutOf(CharacterPanelModel model) {
        return CharacterLayout.build(640, 360, 1.00D, content(model),
                text -> text.length() * 6, List.of("Confirm points", "Discard draft", "Level up"),
                List.of("Confirm level up", "Cancel"), 0);
    }

    /**
     * A preview round trip changes no rectangle at all.
     *
     * <p>This is the defect the user reported as flashing and as swallowed releases: a status line that
     * appeared only while a preview was outstanding made the panel a line taller, re-centred it and moved every
     * button under the pointer. The flags the geometry reads must therefore be identical before and after a
     * preview answer, and the two layouts must be equal rectangle by rectangle.</p>
     */
    @Test void aPreviewRoundTripChangesNoRectangleAndNoFocusableControl() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 3));
        model.add(CharacterStat.VIGOR);
        model.add(CharacterStat.MIND);
        CharacterLayout before = layoutOf(model);
        CharacterLayout.Content flagsBefore = content(model);

        model.acceptPreview(state(recorder.lastRequest, 5L, 3, 3, 400, true, ProgressionReason.OK, new int[6],
                new int[6], new int[]{1, 1, 0, 0, 0, 0}, true, true));
        CharacterLayout after = layoutOf(model);

        assertEquals(flagsBefore, content(model), "a preview reply changes nothing the layout depends on");
        assertEquals(before.panel(), after.panel(), "the panel did not move");
        assertEquals(before.statusRow(), after.statusRow(), "the status row keeps its budget");
        assertEquals(before.separator(), after.separator());
        assertEquals(before.statViewport(), after.statViewport());
        assertEquals(before.buttons(), after.buttons(), "not one footer button moved");
        assertEquals(before.focusOrder(), after.focusOrder(), "the Tab order is unchanged");
        assertEquals(before.boxes(), after.boxes(),
                "no control changed its rectangle, its enabled state or its hit box, so a press that spans a "
                        + "preview reply still activates on release");
        assertEquals(before.popup(), after.popup());
        for (CharacterLayout.StatRow row : before.statRows()) {
            assertEquals(row.minus(), after.rectOf(CharacterLayout.minusId(row.stat())));
            assertEquals(row.plus(), after.rectOf(CharacterLayout.plusId(row.stat())));
        }
        assertNotNull(model.previewDerived(), "and the derived preview is now drawn from the answer");
    }

    /** A permission snapshot that arrives while a transaction runs never displaces that transaction. */
    @Test void aPermissionRefreshWaitsForTheRunningTransaction() {
        Recorder recorder = new Recorder();
        CharacterPanelModel model = new CharacterPanelModel(recorder);
        model.acceptPacket(simple(1, 5L, 1));
        model.add(CharacterStat.VIGOR);
        model.requestAllocate();
        long snapshotsBefore = recorder.count("snapshot");
        model.markPermissionStale();
        assertFalse(model.ensurePermissionRefresh(), "the running allocation owns the connection");
        assertEquals(snapshotsBefore, recorder.count("snapshot"));
        assertEquals(CharacterPanelModel.Pending.ALLOCATE, model.pending());
        // Once its answer lands, the merged demand goes out exactly once.
        model.acceptPacket(state(recorder.lastRequest, 6L, 3, 0, 400, true, ProgressionReason.OK,
                new int[]{1, 0, 0, 0, 0, 0}, new int[6], new int[6], false, true));
        assertTrue(model.ensurePermissionRefresh(), "the demand survives and is sent once");
        assertEquals(snapshotsBefore + 1, recorder.count("snapshot"));
        assertFalse(model.ensurePermissionRefresh(), "and it is not repeated for the same change");
    }
}
