package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.ScrollList;

/**
 * The wheel direction of the two scrollable HUD lists, and of nothing else.
 *
 * <p>Both lists share one direction, because the user asked for the roster to behave like the battle
 * journal: a notch down browses towards the newest end, so the entries already on screen move up, and a
 * notch up browses back towards older history, so they move down. The roster therefore receives the raw
 * notch - its offset grows on a notch down - and nothing is inverted anywhere in this chain. The journal
 * receives the same raw notch from its own state, which keeps its history oldest first and only moves
 * the viewport.</p>
 */
public final class HudWheel {

    private HudWheel() {
    }

    /**
     * Roster offset after one notch: the raw notch, exactly like the journal's own scroll.
     *
     * <p>Nothing here flips the sign, so a notch down (a negative delta) steps the window towards later
     * rows and the rows on screen move up; a notch up steps back and they move down. The list itself
     * still owns the clamping, so the ends behave as they always did.</p>
     */
    public static int rosterOffset(int offset, int total, int visible, double delta) {
        return ScrollList.scrollBy(offset, total, visible, delta);
    }
}
