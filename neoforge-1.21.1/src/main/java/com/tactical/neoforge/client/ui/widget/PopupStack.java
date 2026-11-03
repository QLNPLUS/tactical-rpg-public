package com.tactical.neoforge.client.ui.widget;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The popover stack of one screen: popovers are addressed by a stable id and closed last-in-first-out.
 *
 * <p>Escape must close the topmost popover before it closes the page, and a popover that is already
 * gone must not swallow the next Escape. Both rules live here so they can be tested without a game
 * window.</p>
 */
public final class PopupStack {

    private final Deque<String> open = new ArrayDeque<>();

    /** Opens a popover. Re-opening the topmost one is a no-op so a double click cannot stack two. */
    public void push(String id) {
        if (id == null || id.isEmpty()) return;
        if (id.equals(open.peek())) return;
        open.remove(id);
        open.push(id);
    }

    public boolean isOpen(String id) {
        return id != null && open.contains(id);
    }

    public boolean top(String id) {
        return id != null && id.equals(open.peek());
    }

    /** Closes the topmost popover; returns false when the stack was already empty. */
    public boolean pop() {
        if (open.isEmpty()) return false;
        open.pop();
        return true;
    }

    /**
     * Escape handling: closes the topmost popover and reports that the key was consumed. A screen
     * only closes itself when this returns false.
     */
    public boolean escape() {
        return pop();
    }

    public void toggle(String id) {
        if (isOpen(id)) {
            open.remove(id);
        } else {
            push(id);
        }
    }

    public void clear() {
        open.clear();
    }

    public boolean isEmpty() {
        return open.isEmpty();
    }

    public int depth() {
        return open.size();
    }
}
