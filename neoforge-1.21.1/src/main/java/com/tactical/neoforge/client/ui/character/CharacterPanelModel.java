package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.network.CharacterStatePacket;

import java.util.Arrays;
import java.util.function.IntSupplier;

/**
 * The page's state: the last authoritative snapshot, the local draft, the one outstanding request and the
 * transient status the page reads out.
 *
 * <p>Nothing here computes a price, a cap or a derived value: the snapshot supplies all of them, and a
 * derived value only ever comes from the server's read-only preview answer. The rules this class does own are
 * the ones the specification fixes:</p>
 *
 * <ul>
 *   <li>one mutating request at a time - while an upgrade or an allocation is outstanding, {@code +},
 *       {@code −}, both confirmations and the level-up button are all inert, so nothing is sent twice;</li>
 *   <li>a read-only preview is <em>not</em> that kind of wait. Continuous {@code +}/{@code −} presses answer
 *       locally at once and the server is asked at most once in flight plus once more for the newest draft
 *       (see {@link #requestPreview()}), so a preview round trip never locks the page, never changes what the
 *       page draws as enabled and never replays the panel's geometry;</li>
 *   <li>a draft is discarded the moment the server's revision changes under it, and it is never re-sent;</li>
 *   <li>a preview answer is used only when its revision and its six echoed increments still match the
 *       current draft, so a late answer can never overwrite a newer draft;</li>
 *   <li>a refusal is displayed and re-enables the controls from the server's numbers; a "committed but not
 *       readable" answer ends the wait and demands a refresh instead of looking like a failure.</li>
 * </ul>
 *
 * <p>Pure: no Minecraft type is touched, so every rule above is unit-testable, and the screen only draws what
 * this class reports.</p>
 */
public final class CharacterPanelModel {

    /** Where requests go. The screen supplies the real transport; a test supplies a recorder. */
    public interface Actions {
        void requestSnapshot(int requestId, boolean openScreen);

        void requestPreview(int requestId, long revision, int[] increments);

        void requestUpgrade(int requestId, long revision);

        void requestAllocate(int requestId, long revision, int[] increments);
    }

    /** Which request is outstanding, if any. Only one can be. */
    public enum Pending { NONE, SNAPSHOT, PREVIEW, UPGRADE, ALLOCATE }

    /** How long a refusal stays on the status line, per the specification (3 s, then a 220 ms fade-out). */
    public static final long NOTICE_MS = 3000L;

    private final Actions actions;
    private final IntSupplier requestIds;
    private CharacterStatePacket state;
    private CharacterDraft draft = CharacterDraft.empty();
    private Pending pending = Pending.NONE;
    private int pendingRequest = -1;
    private int requestCounter;
    /** Used only when no shared counter was injected (tests). */
    private int fallbackCounter;
    /** The highest request this page ever sent; the stale gate for an unreadable answer. */
    private int highestSent = -1;
    /** True while a refresh has already been asked for, so one demand never becomes a request per frame. */
    private boolean refreshRequested;
    private int lastPreviewRequest = -1;
    private long previewRevision = -1L;
    private int[] previewIncrements = new int[CharacterStat.COUNT];
    private double[] previewDerived;
    /** The request id of the read-only preview the server has not answered yet, or -1. */
    private int previewInFlight = -1;
    /** The exact draft the in-flight preview was asked for: the refused-draft memory is built from this. */
    private int[] previewSentIncrements = new int[CharacterStat.COUNT];
    private long previewSentRevision = -1L;
    /** The draft the server last refused to preview, so a refusal is never asked again for one draft. */
    private int[] previewRefused;
    private long previewRefusedRevision = -1L;
    /** True while this client's own action right or busy state changed and the page must re-ask. */
    private boolean permissionStale;
    private boolean draftStale;
    private boolean refreshNeeded;
    private boolean upgradePopup;
    private ProgressionReason notice;
    private long noticeSetAt = -1L;

    /**
     * @param requestIds where request ids come from. Production passes
     *                   {@link com.tactical.neoforge.client.progression.CharacterClientState#beginRequest()}, so an id
     *                   keeps growing across pages and an answer from an earlier visit can never be mistaken for a
     *                   fresh one; a test passes its own counter.
     */
    public CharacterPanelModel(Actions actions, IntSupplier requestIds) {
        if (actions == null) throw new IllegalArgumentException("Missing transport");
        this.actions = actions;
        this.requestIds = requestIds == null ? () -> ++fallbackCounter : requestIds;
    }

    /** Convenience for a page that owns its numbering; the production path injects the shared counter. */
    public CharacterPanelModel(Actions actions) { this(actions, null); }

    // ---- requests -------------------------------------------------------------------------------

    /** Asks for the authoritative state; {@code openScreen} tells the server this is the "open" intent. */
    public int requestSnapshot(boolean openScreen) {
        int request = nextRequest(Pending.SNAPSHOT);
        actions.requestSnapshot(request, openScreen);
        return request;
    }

    /** One level-up confirmation; refused locally while anything else is outstanding. */
    public boolean requestUpgrade() {
        if (!canUpgrade()) return false;
        upgradePopup = false;
        int request = nextRequest(Pending.UPGRADE);
        actions.requestUpgrade(request, state.revision());
        return true;
    }

    /** One allocation confirmation; refused locally while the draft is empty or anything is outstanding. */
    public boolean requestAllocate() {
        if (!canConfirmAllocation()) return false;
        int request = nextRequest(Pending.ALLOCATE);
        actions.requestAllocate(request, state.revision(), draft.increments());
        return true;
    }

    private int nextRequest(Pending kind) {
        pending = kind;
        int suggested = requestIds.getAsInt();
        // A shared counter is monotonic; a wrapped one must still never repeat the id this page just used.
        int next = suggested;
        if (next <= 0 || next == requestCounter) {
            next = requestCounter == Integer.MAX_VALUE ? 1 : requestCounter + 1;
        }
        requestCounter = next;
        pendingRequest = next;
        highestSent = Math.max(highestSent, next);
        return next;
    }

    // ---- answers --------------------------------------------------------------------------------

    /**
     * Feeds one answer to the page.
     *
     * <p>Order matters twice here. The packet's data is applied first, and only then is it decided whether the
     * newest draft still needs a preview - so a page can never ask for a draft weighed against numbers it has
     * already replaced. And an authoritative answer whose request id is at least the preview in flight is
     * treated as superseding it: the client cache only keeps the newest packet, so the older preview's reply
     * may never reach this page at all, and leaving the slot occupied would silence every later preview.</p>
     *
     * @return true when the page's authoritative state changed
     */
    public boolean acceptPacket(CharacterStatePacket packet) {
        if (packet == null) return false;
        boolean answers = pending != Pending.NONE && packet.requestId() >= pendingRequest;
        if (!packet.syncAvailable()) {
            // Committed but unreadable: nothing on screen is replaced (a placeholder zero must never look like
            // a balance), the wait is over, and the page asks again instead of replaying the request.
            supersedePreview(packet.requestId());
            if (answers) {
                pending = Pending.NONE;
                pendingRequest = -1;
            }
            refreshNeeded = true;
            return false;
        }
        boolean changed = state == null || packet.revision() != state.revision();
        long previousRevision = state == null ? -1L : state.revision();
        // Decided before the slot is released: a refusal of the preview this page sent must be remembered even
        // when another transaction happens to be outstanding at the same moment.
        boolean answeredPreviewSlot = previewInFlight >= 0 && packet.requestId() >= previewInFlight;
        state = packet;
        refreshNeeded = false;
        refreshRequested = false;
        // A newer authoritative answer overtakes the read-only preview that was in flight; a preview that
        // answers this page itself is handled by acceptPreview and reaches here only when it was refused.
        supersedePreview(packet.requestId());
        if (state.profileValid() && previousRevision >= 0L && packet.revision() != previousRevision) {
            // The server moved under the page: the draft was weighed against other numbers, so it is dropped
            // rather than confirmed, and the level-up quote is withdrawn. Anything still travelling for the
            // old revision is void as well - its answer can no longer match a draft of this revision.
            if (!draft.isEmpty()) draftStale = true;
            draft = CharacterDraft.empty();
            clearPreview();
            invalidatePreview();
            upgradePopup = false;
        }
        if (answeredPreviewSlot && !packet.draftValid()) {
            // The server never echoes a draft it refused (it sends false with a zero array), so the refused
            // draft is taken from what this page really sent for that request.
            noteRefusedPreview();
        }
        if (answers) {
            Pending answered = pending;
            pending = Pending.NONE;
            pendingRequest = -1;
            if (!packet.accepted()) {
                notice(packet.refusal());
                if (answered == Pending.ALLOCATE) clearPreview();
            } else {
                notice = null;
                if (answered == Pending.ALLOCATE) {
                    draft = CharacterDraft.empty();
                    draftStale = false;
                    clearPreview();
                }
            }
        }
        // One place decides whether the newest draft still needs a preview: after the data is applied, after
        // a transaction has ended and after any slot has been freed.
        resumePreview();
        return changed;
    }

    /**
     * Feeds an answer that says "committed, but the fresh numbers are not readable".
     *
     * <p>It only ends the wait it actually answers: an older request's error must never release a newer wait,
     * and a notice for a request this page never sent is ignored. Nothing on screen is replaced, so a
     * placeholder zero can never look like a balance.</p>
     *
     * @return true when an outstanding request of this page was released
     */
    public boolean acceptUnavailable(CharacterStatePacket packet) {
        if (packet == null || packet.syncAvailable()) return false;
        if (pending != Pending.NONE) {
            if (packet.requestId() < pendingRequest) return false;
            pending = Pending.NONE;
            pendingRequest = -1;
        } else if (packet.requestId() < highestSent) {
            return false;
        }
        // The fresh numbers are unknown, so anything travelling for them is void: the page asks again instead
        // of drawing a derived value against numbers it can no longer trust.
        supersedePreview(packet.requestId());
        invalidatePreview();
        refreshNeeded = true;
        refreshRequested = false;
        return true;
    }

    /**
     * Asks for one fresh snapshot because the page is out of date; never twice for one demand.
     *
     * @return true when a request was actually sent
     */
    public boolean ensureRefreshRequested() {
        if (!refreshNeeded || refreshRequested) return false;
        refreshRequested = true;
        requestSnapshot(false);
        return true;
    }

    /**
     * Notes that this client's own action right or busy state changed while the page is open.
     *
     * <p>Whether the character may be changed in a battle depends on "my turn and nothing in flight", which
     * changes without the battle membership changing at all. The page therefore asks the server again instead
     * of waiting for the next action to be refused. The demand is merged - one flag, not a request per frame -
     * and it is never sent on top of a mutating transaction, whose own answer already carries the fresh
     * permission.</p>
     */
    public void markPermissionStale() { permissionStale = true; }

    /**
     * Sends the merged permission refresh if it is still needed and nothing else owns the connection.
     *
     * <p>It outranks the next preview: a permission demand clears the queued-preview intent, so a player who
     * keeps pressing {@code +}/{@code −} can never starve the refresh that tells the page whether it may be
     * edited at all. It still never displaces a mutating transaction - that transaction's own answer carries
     * the fresh permission - and it waits for the one read-only preview that is already travelling rather than
     * putting two requests in flight at once.</p>
     *
     * @return true when a request was actually sent
     */
    public boolean ensurePermissionRefresh() {
        if (!permissionStale || state == null) return false;
        if (waiting()) return false;
        // A read-only preview still travelling is not worth waiting for either: the snapshot carries the
        // permission this demand is about, and the voided preview is asked again afterwards.
        invalidatePreview();
        permissionStale = false;
        requestSnapshot(false);
        return true;
    }

    /**
     * Adopts a cached snapshot for display only, without treating it as an answer.
     *
     * <p>A freshly opened page shows what the client already knows so it is not blank for a round trip, but the
     * request it just sent is still outstanding: the cached answer belongs to an earlier visit and must not
     * release it.</p>
     */
    public boolean seed(CharacterStatePacket packet) {
        if (packet == null || !packet.syncAvailable() || state != null) return false;
        state = packet;
        return true;
    }

    /**
     * Feeds one read-only preview answer.
     *
     * <p>Used only when it answers a preview this page is waiting for, belongs to the current revision and
     * echoes the six increments of the current draft. A late answer for an older draft is dropped, so the
     * derived column can never describe points the player no longer proposes - but it still frees the one
     * preview slot, so the newest draft can be asked about immediately.</p>
     */
    public boolean acceptPreview(CharacterStatePacket packet) {
        if (packet == null || state == null || !packet.syncAvailable()) return false;
        boolean answersSlot = previewInFlight >= 0 && packet.requestId() == previewInFlight;
        if (!answersSlot) return false;
        if (!packet.draftValid()) noteRefusedPreview();
        if (pending == Pending.PREVIEW && packet.requestId() >= pendingRequest) {
            pending = Pending.NONE;
            pendingRequest = -1;
        }
        boolean used = false;
        if (packet.requestId() >= lastPreviewRequest && packet.revision() == state.revision()
                && packet.draftValid() && draft.sameAs(packet.draftIncrements())) {
            lastPreviewRequest = packet.requestId();
            previewRevision = packet.revision();
            previewIncrements = packet.draftIncrements();
            previewDerived = packet.draftDerived();
            used = true;
        }
        if (answersSlot) previewInFlight = -1;
        // The same single decision point as after any other answer: the newest merged draft goes out if it
        // still needs one and nothing outranks it.
        resumePreview();
        return used;
    }

    // ---- draft ----------------------------------------------------------------------------------

    /** Whether a point of this stat may be drafted; false while a transaction is running or read-only. */
    public boolean canAdd(CharacterStat stat) {
        return state != null && state.profileValid() && !waiting() && readOnly() == null
                && draft.canAdd(stat, state.unspent(), state.invested()[stat.index()], state.statCap());
    }

    public boolean canRemove(CharacterStat stat) {
        return state != null && state.profileValid() && !waiting() && readOnly() == null && draft.canRemove(stat);
    }

    /** Adds one drafted point and asks the server for a preview of the new draft. */
    public boolean add(CharacterStat stat) {
        if (!canAdd(stat)) return false;
        draft = draft.add(stat, state.unspent(), state.invested()[stat.index()], state.statCap());
        afterDraftChange();
        return true;
    }

    /** Removes one drafted point; it can only ever undo points this draft added. */
    public boolean remove(CharacterStat stat) {
        if (!canRemove(stat)) return false;
        draft = draft.remove(stat);
        afterDraftChange();
        return true;
    }

    private void afterDraftChange() {
        draftStale = false;
        clearPreview();
        resumePreview();
    }

    /**
     * Asks the server to preview the current draft, keeping at most one preview in flight.
     *
     * <p>Continuous {@code +}/{@code −} presses are the common case. A press that happens while a preview is
     * still travelling changes nothing here but the draft itself: the next call after the slot is freed sends
     * one request describing the draft as it stands at that moment - the merged latest state, never a replay of
     * every intermediate draft. It also refuses to ask at all while a transaction runs, while the page cannot be
     * edited, or while this client's permission is being re-asked: those would either race the answer that
     * matters or spend a request on a page the server is about to declare read-only.</p>
     */
    private void resumePreview() {
        if (state == null || draft.isEmpty()) return;
        if (previewMatchesDraft() || refusedDraft()) return;
        if (waiting() || permissionStale || readOnly() != null) return;
        if (previewInFlight >= 0) return;
        sendPreview();
    }

    private void sendPreview() {
        int request = nextRequest(Pending.PREVIEW);
        previewInFlight = request;
        previewSentIncrements = draft.increments();
        previewSentRevision = state.revision();
        actions.requestPreview(request, previewSentRevision, previewSentIncrements.clone());
    }

    /** Remembers the draft this page really sent for the preview that was just refused. */
    private void noteRefusedPreview() {
        if (previewSentRevision < 0L) return;
        previewRefused = previewSentIncrements.clone();
        previewRefusedRevision = previewSentRevision;
    }

    /**
     * Frees the preview slot when a newer authoritative answer has overtaken it.
     *
     * <p>The client cache keeps one answer, so the reply to an older preview may never reach the page at all.
     * Leaving that request marked in flight would silence every later preview, so the slot is released and the
     * request id is recorded: if the old reply does turn up afterwards, it is older than the newest accepted
     * preview and is dropped instead of being drawn.</p>
     */
    private void supersedePreview(int requestId) {
        if (previewInFlight < 0 || requestId < previewInFlight) return;
        lastPreviewRequest = Math.max(lastPreviewRequest, requestId);
        previewInFlight = -1;
    }

    /**
     * Voids the preview in flight because the numbers it was computed against are gone.
     *
     * <p>Used when the profile revision changed under the page and when a permission refresh takes the
     * connection: the answer, if it ever arrives, can no longer describe anything the page shows.</p>
     */
    private void invalidatePreview() {
        if (previewInFlight >= 0) lastPreviewRequest = Math.max(lastPreviewRequest, previewInFlight);
        previewInFlight = -1;
        previewSentRevision = -1L;
        if (pending == Pending.PREVIEW) {
            // The voided preview is no longer an outstanding request either: nothing may later look like it
            // is waiting for an answer that can only be dropped.
            pending = Pending.NONE;
            pendingRequest = -1;
        }
    }

    /** True when the held preview describes exactly the current revision and draft. */
    private boolean previewMatchesDraft() {
        return previewDerived != null && state != null && previewRevision == state.revision()
                && Arrays.equals(previewIncrements, draft.increments());
    }

    /** True when the server already refused to preview exactly this draft of exactly this revision. */
    private boolean refusedDraft() {
        return previewRefused != null && state != null && previewRefusedRevision == state.revision()
                && Arrays.equals(previewRefused, draft.increments());
    }

    /** Discards the uncommitted draft and keeps the page open, as the specification requires. */
    public void cancelDraft() {
        draft = CharacterDraft.empty();
        draftStale = false;
        clearPreview();
    }

    private void clearPreview() {
        previewDerived = null;
        previewIncrements = new int[CharacterStat.COUNT];
        previewRevision = -1L;
    }

    // ---- derivations available to the page ------------------------------------------------------

    /** The draft as the server previewed it, or null while no matching preview is held. */
    public double[] previewDerived() {
        return previewMatchesDraft() ? previewDerived.clone() : null;
    }

    /**
     * The server's current derived values, or null when there are none to show.
     *
     * <p>While the page is out of date - a commit landed but its fresh numbers were unreadable - nothing is
     * shown: the specification draws that state's table and derived area as placeholders, because last
     * round's numbers are exactly the ones the player must not act on.</p>
     */
    public double[] currentDerived() {
        if (state == null || !state.profileValid() || !state.syncAvailable() || refreshNeeded) return null;
        return state.derived();
    }

    /**
     * The displayed value of one stat, committed plus draft; null when there is nothing to show.
     *
     * <p>The same rule as {@link #currentDerived()}: an answer that could not be read leaves the page with no
     * values to act on, so it draws placeholders instead of the previous revision's numbers.</p>
     */
    public Integer display(CharacterStat stat) {
        if (state == null || !state.profileValid() || refreshNeeded) return null;
        return draft.display(stat, state.display()[stat.index()]);
    }

    // ---- state the page reads out ----------------------------------------------------------------

    public CharacterStatePacket state() { return state; }

    public CharacterDraft draft() { return draft; }

    public Pending pending() { return pending; }

    /**
     * The request id of the transaction this page is waiting for, or -1.
     *
     * <p>It is only set when a request was really sent, so the development probe can tell "the button was
     * pressed and nothing went out" from "a packet went out and the server refused it".</p>
     */
    public int pendingRequestId() { return pendingRequest; }

    public boolean waiting() { return pending != Pending.NONE && pending != Pending.PREVIEW; }

    /** True while the one read-only preview is travelling; it never blocks the draft controls. */
    public boolean previewPending() { return previewInFlight >= 0 || pending == Pending.PREVIEW; }

    public boolean refreshNeeded() { return refreshNeeded; }

    public boolean draftStale() { return draftStale; }

    public boolean upgradePopupOpen() { return upgradePopup; }

    public void openUpgradePopup() { if (canUpgrade()) upgradePopup = true; }

    public void cancelUpgradePopup() { upgradePopup = false; }

    /** Why the page cannot be changed right now, or null when it can. */
    public ProgressionReason readOnly() {
        if (refreshNeeded) return ProgressionReason.SYNC_UNAVAILABLE;
        if (state == null) return ProgressionReason.PROFILE_UNKNOWN;
        if (!state.profileValid()) return ProgressionReason.PROFILE_INVALID;
        if (!state.syncAvailable()) return ProgressionReason.SYNC_UNAVAILABLE;
        return state.canMutate() ? null : state.refusal();
    }

    public boolean readOnlyPermanently() { return readOnly() != null; }

    /** Whether the level-up confirmation would be accepted now, by the page's own numbers. */
    public boolean canUpgrade() {
        if (state == null || !state.profileValid() || waiting() || readOnly() != null) return false;
        if (state.level() >= state.maxLevel()) return false;
        return state.xpAvailable() >= state.xpToNextLevel();
    }

    /** Whether the draft could be confirmed now. */
    public boolean canConfirmAllocation() {
        return state != null && state.profileValid() && !waiting() && readOnly() == null
                && !draft.isEmpty() && draft.total() <= state.unspent();
    }

    /** The last refusal, or null once its display time has passed. {@code now} is milliseconds. */
    public ProgressionReason notice(long now) {
        if (notice == null) return null;
        if (noticeSetAt < 0L) noticeSetAt = now;
        if (now - noticeSetAt >= NOTICE_MS) {
            notice = null;
            noticeSetAt = -1L;
            return null;
        }
        return notice;
    }

    /** How far the refusal's fade-out has progressed, 0..1; the page draws its own alpha. */
    public double noticeFade(long now) {
        if (notice == null || noticeSetAt < 0L) return 0.0D;
        long age = now - noticeSetAt;
        long fadeStart = NOTICE_MS - 220L;
        if (age <= fadeStart) return 0.0D;
        return Math.min(1.0D, (age - fadeStart) / 220.0D);
    }

    private void notice(ProgressionReason reason) {
        if (reason == null || reason.accepted()) {
            notice = null;
            noticeSetAt = -1L;
            return;
        }
        notice = reason;
        noticeSetAt = -1L;
    }

    /** The service's own revision, or {@code -1} when nothing has arrived. */
    public long revision() { return state == null ? -1L : state.revision(); }
}
