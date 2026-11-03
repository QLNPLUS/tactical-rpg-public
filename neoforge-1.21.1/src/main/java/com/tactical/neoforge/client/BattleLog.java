package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.hud.JournalLabels;
import com.tactical.neoforge.client.ui.hud.JournalViewport;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.network.BattleLogPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;

/**
 * The battle journal: state, fold and history - now drawn natively.
 *
 * <p>What is left here is everything a drawing pass must not own: which encounter's traffic is
 * accepted, what the two independent collapse sources decide, and where in the history the body is
 * browsed to. That position is an entry plus the pixels scrolled into it, never a count of entries,
 * so one entry taller than the body can be read line by line. The pixels come from
 * {@link com.tactical.neoforge.client.ui.hud.NativeHud}, which reads this class' answers, so the
 * toggle button, the footer text and the visible viewport can never disagree.</p>
 */
public final class BattleLog {

    private static final BattleLogState STATE = new BattleLogState();
    /**
     * The tight and narrow forms fold the journal to its title row so the command panel keeps every
     * description row. That fold and the ordinary collapse preference are separate sources: the
     * toggle inside a folded form only switches the in-form expand.
     */
    private static final JournalFold FOLD = new JournalFold();
    private static ClientLevel level;
    private static Scale.Form form = Scale.Form.STANDARD;

    private BattleLog() {
    }

    /** The form the current frame uses; entering or leaving a folded form drops its temporary expand. */
    public static void syncForm(Scale.Form next) {
        form = next;
        FOLD.form(next != Scale.Form.STANDARD);
    }

    public static Scale.Form form() {
        return form;
    }

    /** World or login change: the journal forgets every encounter, including its refusals. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) return;
        STATE.clear();
        level = minecraft.level;
    }

    /** Refuse this encounter's traffic from the membership whose exit the server just published. */
    public static void retire(java.util.UUID session, long generation) {
        STATE.retire(session, generation);
    }

    /** Life-cycle boundary: the journal forgets every encounter, including its refusals. */
    public static void disconnect() {
        STATE.disconnect();
    }

    /** Called only by the clientbound payload's enqueue-work handler. */
    public static void accept(BattleLogPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) return;
        // A log - including its CLEAR - belongs to one encounter; the current one wins over a stale
        // one. Only the membership this client currently shows may write into its journal.
        if (!BattleMembership.matches(packet.membershipServerEpoch(), packet.membershipGeneration(),
                packet.battleId())) return;
        tick();
        STATE.accept(packet, Util.getMillis());
    }

    public static boolean stateVisible() {
        return STATE.visible();
    }

    /**
     * Every retained entry, oldest first. The body's window is chosen by the layout each frame, so
     * this is the whole browsable history, not a pre-cut window that could hide the oldest entries.
     */
    public static List<BattleLogPacket.Entry> retainedHistory() {
        return STATE.retainedHistory();
    }

    /** The ordinary saved preference, ignoring whatever the current window form does. */
    public static boolean expandedPreference() {
        return STATE.visible() && !STATE.collapsed();
    }

    /**
     * Whether the body is hidden right now. Every layout, input and drawing decision uses this,
     * never the raw preference, so an explicit expand still works after the user folded the journal
     * in the standard form.
     */
    public static boolean collapsedNow() {
        return FOLD.collapsed(STATE.collapsed());
    }

    /** Whether the body is actually drawn: the panel is visible and nothing collapses it. */
    public static boolean bodyShown() {
        return STATE.visible() && !collapsedNow();
    }

    public static boolean folded() {
        return FOLD.folded();
    }

    public static String statusText() {
        return ClientText.get(JournalLabels.status(STATE.browsing()));
    }

    /**
     * The footer's count, taken from the entries the body really drew: {@code a-b} are their own
     * sequence numbers and the total stays the terminal sequence, as it always has.
     */
    public static String countText(long firstSequence, long lastSequence, boolean empty) {
        return ClientText.get(JournalLabels.COUNT,
                JournalLabels.countRange(firstSequence, lastSequence, empty), Long.toString(STATE.sequence()));
    }

    /** The terminal sequence of the encounter, so a caller can format the count itself. */
    public static long sequence() {
        return STATE.sequence();
    }

    public static JournalViewport.Anchor journalAnchor() {
        return STATE.journalAnchor();
    }

    /** The position the layout really resolved this frame; adopting it keeps one browsed place. */
    public static void setJournalAnchor(JournalViewport.Anchor anchor) {
        STATE.setJournalAnchor(anchor);
    }

    /**
     * The toggle of the title row. In a folded form it switches that form's explicit expand; in the
     * standard form it flips the ordinary preference. The two are never OR-ed together.
     */
    public static boolean toggleBody() {
        if (!STATE.visible()) return false;
        if (FOLD.folded()) FOLD.toggleExplicit();
        else STATE.toggleCollapsed();
        return true;
    }

    /**
     * Wheel on the journal body or footer. One notch browses one text line of the continuous stack: a
     * notch up goes into older history, a notch down returns towards the newest entry (the raw notch,
     * no inversion), and the journal consumes the event even at either end so the camera never
     * receives it. {@code content} is the body's own geometry, measured by the layout that paints it.
     */
    public static boolean scrollHistory(double delta, JournalViewport.Content content) {
        return STATE.scroll(delta, bodyShown(), content);
    }
}
