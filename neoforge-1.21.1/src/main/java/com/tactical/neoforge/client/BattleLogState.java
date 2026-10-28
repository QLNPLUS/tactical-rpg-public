package com.tactical.neoforge.client;

import com.tactical.neoforge.network.BattleLogPacket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-client-thread state, independent of rendering and Minecraft singletons. */
public final class BattleLogState {
    public static final int VISIBLE_LINES = 7;
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
    private int historyOffset;
    private int visibleLines = VISIBLE_LINES;

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
            historyOffset = 0;
        }
        if (packet.sequence() <= sequence || finished) return false;
        // Anchor the last displayed entry rather than an index: bounded snapshots evict old rows.
        long anchor = historyOffset > 0 && !history.isEmpty()
                ? history.get(history.size() - historyOffset - 1).sequence() : -1;
        sequence = packet.sequence();
        history = packet.history();
        if (anchor >= 0) {
            historyOffset = (int) history.stream().filter(entry -> entry.sequence() > anchor).count();
            historyOffset = Math.min(historyOffset, maxOffset());
        }
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
        historyOffset = 0;
        visibleLines = VISIBLE_LINES;
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
    /** Positive native wheel deltas browse older entries; negative deltas return to latest. */
    public boolean scroll(double delta) {
        if (!visible() || collapsed || !Double.isFinite(delta) || delta == 0) return false;
        int steps = (int) Math.min(BattleLogPacket.MAX_HISTORY, Math.max(1, Math.ceil(Math.abs(delta))));
        int next = Math.max(0, Math.min(maxOffset(), historyOffset + (delta > 0 ? steps : -steps)));
        if (next != historyOffset) {
            historyOffset = next;
            dirty = true;
        }
        // Consume even at either boundary so the camera never receives journal wheel input.
        return true;
    }

    public void setVisibleLines(int lines) {
        visibleLines = Math.max(1, Math.min(VISIBLE_LINES, lines));
        int next = Math.min(historyOffset, maxOffset());
        if (next != historyOffset) {
            historyOffset = next;
            dirty = true;
        }
    }

    private int maxOffset() { return Math.max(0, history.size() - visibleLines); }
    public int historyOffset() { return historyOffset; }
    public List<BattleLogPacket.Entry> visibleHistory() {
        int end = history.size() - historyOffset;
        return history.subList(Math.max(0, end - visibleLines), end);
    }
    public boolean dirty() { return dirty; }
    public void markClean() { dirty = false; }
}
