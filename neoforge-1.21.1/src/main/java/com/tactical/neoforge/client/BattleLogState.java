package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.hud.JournalViewport;
import com.tactical.neoforge.network.BattleLogPacket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-client-thread state, independent of rendering and Minecraft singletons. */
public final class BattleLogState {
    /**
     * Encounter id to the membership generation whose traffic is refused.
     *
     * <p>Retirement is per generation, not per encounter: a player who leaves and later re-enters the
     * <em>same</em> encounter gets a newer generation, which lifts the refusal, while the generation of
     * the entry that was left can never come back.</p>
     */
    private final Map<UUID, Long> retired = new LinkedHashMap<>();
    private UUID battleId;
    private long sequence;
    private List<BattleLogPacket.Entry> history = List.of();
    private boolean finished;
    private boolean collapsed;
    private boolean dirty;
    /**
     * Where the body is scrolled to: an entry's own sequence plus the pixels scrolled into it, or
     * {@link JournalViewport.Anchor#NEWEST} while the newest entry hugs the body's bottom edge.
     *
     * <p>A pixel distance would have to be corrected by hand every time an entry is appended, evicted
     * or re-wrapped; an anchor survives all three because it names content, not a distance. The
     * viewport resolves it against the heights the layout measured this frame.</p>
     */
    private JournalViewport.Anchor journalAnchor = JournalViewport.Anchor.NEWEST;

    /**
     * Refuse one encounter's older traffic after the server ended that membership.
     *
     * @param generation the exit generation; a later entry into the same encounter has a higher one.
     */
    public void retire(java.util.UUID session, long generation) {
        if (session == null) return;
        retired.put(session, Math.max(generation, retired.getOrDefault(session, Long.MIN_VALUE)));
        if (retired.size() > BattleLogPacket.MAX_HISTORY) {
            retired.remove(retired.keySet().iterator().next());
        }
    }

    /** True when this encounter's payload belongs to a membership that was already replaced. */
    public boolean isRetired(java.util.UUID session, long generation) {
        if (session == null) return false;
        Long retiredAt = retired.get(session);
        return retiredAt != null && generation <= retiredAt;
    }

    public boolean accept(BattleLogPacket packet, long ignoredNowMillis) {
        if (isRetired(packet.battleId(), packet.membershipGeneration())) return false;
        if (packet.lifecycle() == BattleLogPacket.CLEAR) {
            if (!packet.battleId().equals(battleId) || packet.sequence() < sequence) return false;
            clear();
            return true;
        }
        if (!packet.battleId().equals(battleId)) {
            // A joiner's first snapshot can be UPDATE or FINISH: every snapshot carries the complete
            // bounded history, so adopting it is safe and nobody has to rely on a START arriving
            // first. CLEAR is handled above and never adopts.
            retireCurrent();
            battleId = packet.battleId();
            // A newer generation for this encounter is a legitimate re-entry, so its refusal is lifted.
            retired.remove(battleId);
            sequence = -1;
            finished = false;
            collapsed = false;
            journalAnchor = JournalViewport.Anchor.NEWEST;
        }
        if (packet.sequence() <= sequence || finished) return false;
        // Nothing is re-anchored here on purpose. The anchor names an entry and the pixels scrolled
        // into it, so a snapshot that appends newer entries - or evicts the oldest ones at the head of
        // the bounded history - leaves a browsing reader exactly where they were, while an unchanged
        // NEWEST anchor keeps following the end of the log.
        sequence = packet.sequence();
        history = packet.history();
        // Keep the terminal sequence to reject queued updates, but never retain its UI.
        finished = packet.lifecycle() == BattleLogPacket.FINISH;
        dirty = true;
        return true;
    }

    public boolean toggleCollapsed() {
        if (!visible()) return false;
        collapsed = !collapsed;
        dirty = true;
        return true;
    }

    /** World changes retire the UUID so an already queued snapshot cannot revive it. */
    public void clear() {
        retireCurrent();
        battleId = null;
        sequence = 0;
        history = List.of();
        finished = false;
        collapsed = false;
        journalAnchor = JournalViewport.Anchor.NEWEST;
        dirty = true;
    }

    public void disconnect() {
        clear();
        retired.clear();
    }

    private void retireCurrent() {
        if (battleId == null) return;
        retire(battleId, BattleMembership.generation());
    }

    public boolean visible() { return battleId != null && !finished && !history.isEmpty(); }
    public UUID battleId() { return battleId; }
    public long sequence() { return sequence; }
    public boolean finished() { return finished; }
    public boolean collapsed() { return collapsed; }
    public List<BattleLogPacket.Entry> history() { return history; }

    /**
     * One wheel notch browses text lines: positive deltas move into older history, negative ones
     * towards the newest entry. The HUD hands over the raw wheel notch, so a notch down (the negative
     * native delta) returns towards the end of the log, which is the ordinary console direction the
     * user asked for.
     *
     * <p>The movement is in the viewport's own unit - GUI pixels - so a single entry taller than the
     * body can be read line by line instead of being skipped whole. The caller passes the heights the
     * layout measured this frame, which is the same content it paints and reports.</p>
     */
    public boolean scroll(double delta, JournalViewport.Content content) {
        return scroll(delta, !collapsed, content);
    }

    /**
     * The caller decides whether the body is really drawn: the tight/narrow form's explicit expand
     * shows the body without changing the ordinary collapse preference.
     */
    public boolean scroll(double delta, boolean bodyShown, JournalViewport.Content content) {
        if (!visible() || !bodyShown || !Double.isFinite(delta) || delta == 0) return false;
        int notches = (int) Math.min(BattleLogPacket.MAX_HISTORY, Math.max(1, Math.ceil(Math.abs(delta))));
        int step = content == null || content.count() <= 0 ? 1 : Math.max(1, content.lineHeight());
        JournalViewport.Anchor next = JournalViewport.shift(content, journalAnchor,
                delta > 0 ? notches * step : -notches * step);
        if (!next.equals(journalAnchor)) {
            journalAnchor = next;
            dirty = true;
        }
        // Consume even at either boundary so the camera never receives journal wheel input.
        return true;
    }

    /**
     * The layout reports the position it really resolved this frame. Adopting it keeps one browsed
     * position for every body size, so a shrink cannot leave the reader past the oldest entry and a
     * view that reached the newest end starts following new entries again.
     */
    public void setJournalAnchor(JournalViewport.Anchor anchor) {
        JournalViewport.Anchor next = anchor == null ? JournalViewport.Anchor.NEWEST : anchor;
        if (next.equals(journalAnchor)) return;
        journalAnchor = next;
        dirty = true;
    }

    /** Where the body is scrolled to: the entry under its top edge, or the newest end. */
    public JournalViewport.Anchor journalAnchor() {
        return journalAnchor;
    }

    /** True while the reader is browsing older history instead of following the newest entry. */
    public boolean browsing() {
        return !journalAnchor.newest();
    }

    /**
     * The retained history, oldest first. Every entry the client still keeps is browsable, so this is
     * also what the journal's scroll bar reports as its total - the window is chosen by the layout.
     */
    public List<BattleLogPacket.Entry> retainedHistory() {
        return history;
    }

    public boolean dirty() { return dirty; }
    public void markClean() { dirty = false; }
}
