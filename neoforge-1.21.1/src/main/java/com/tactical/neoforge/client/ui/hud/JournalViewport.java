package com.tactical.neoforge.client.ui.hud;

/**
 * The journal's single scrolling contract, measured in GUI pixels.
 *
 * <p>The user asked for a journal that behaves like a console log: an entry that does not fit at the
 * body's edge is shown as far as it reaches instead of being withdrawn whole, which is what left a
 * large blank area under the title while older entries were still available. The old contract counted
 * <em>whole entries</em>: it packed complete rows into the body and had three different answers to
 * "how much is on screen" (the state's window, the painter's clip and the bar's total). A single entry
 * taller than the body could therefore never be read line by line, and a mixed single/multi-line
 * history could show a blank band while many lines sat right above the fold.</p>
 *
 * <p>Here the body is a viewport over a continuous pixel stack. The retained entries are stacked
 * oldest first with the approved 2px gap and keep their real height, the content's top edge is the
 * body's top edge minus the scrolled distance, and every entry that intersects the body is drawn -
 * the first and the last one possibly cut by the scissor. Nothing is stretched, no font size changes
 * and no "…" pretends a long entry ended: the reader can scroll through it line by line.</p>
 *
 * <p>Pure arithmetic on the heights it is given, so a mixed 1/3/5-line history can be checked without
 * a game window.</p>
 */
public final class JournalViewport {

    private JournalViewport() {
    }

    /**
     * The browsed position: the entry under the body's top edge and the pixels already scrolled into
     * it - possibly into the gap below it, which is why a position inside a gap is kept to the pixel
     * instead of being rounded onto an entry. It is a sequence rather than an index or a pixel
     * distance from the newest end because the retained history is bounded: evicting an old entry,
     * wrapping the same text differently after a resize or appending a new entry must not move the
     * reader's place.
     *
     * <p>{@link #NEWEST} is not a position in the stack but the subscription to its newest end: new
     * entries push the view up so the last entry always hugs the body's bottom edge.</p>
     */
    public record Anchor(long sequence, int intoPx) {

        /** Follow the newest end: the journal's default and where every scroll returns to. */
        public static final Anchor NEWEST = new Anchor(-1L, 0);

        public boolean newest() {
            return sequence < 0L;
        }

        /** Pixels scrolled past the anchor entry's top, never negative. */
        public Anchor {
            intoPx = Math.max(0, intoPx);
        }
    }

    /**
     * One frame of content: every retained entry, oldest first, with the height it really needs.
     *
     * @param sequences   the retained entries' own sequence numbers, oldest first
     * @param heights     the height of each entry in GUI pixels, in the same order
     * @param gap         the space between two entries
     * @param lineHeight  one text line, which is also what one wheel notch browses
     * @param bodyHeight  the visible body's height
     */
    public record Content(long[] sequences, int[] heights, int gap, int lineHeight, int bodyHeight) {

        public int count() {
            return heights == null ? 0 : Math.min(heights.length, sequences == null ? 0 : sequences.length);
        }

        public int height(int index) {
            return index < 0 || index >= count() ? 0 : Math.max(1, heights[index]);
        }

        /** Height of the whole stack: every entry plus the gaps between them. */
        public int total() {
            int count = count();
            if (count <= 0) return 0;
            int total = 0;
            for (int index = 0; index < count; index++) total += Math.max(1, heights[index]);
            return total + Math.max(0, gap) * (count - 1);
        }

        /** Furthest the content can be scrolled before its oldest end reaches the body's top edge. */
        public int maxScroll() {
            return Math.max(0, total() - Math.max(0, bodyHeight));
        }

        public boolean scrollable() {
            return total() > Math.max(0, bodyHeight);
        }

        /** Distance from the content's oldest end to the top of entry {@code index}. */
        public int top(int index) {
            int step = Math.max(0, gap);
            int top = 0;
            for (int slot = 0; slot < index && slot < count(); slot++) {
                top += Math.max(1, heights[slot]) + step;
            }
            return top;
        }

        /**
         * How far past entry {@code index}'s top an anchor may sit: the entry itself plus the gap
         * below it, so a position that lands between two entries survives the round trip exactly.
         */
        public int intoLimit(int index) {
            if (index < 0 || index >= count()) return 0;
            int gapBelow = index + 1 < count() ? Math.max(0, gap) : 0;
            return height(index) + gapBelow;
        }

        public int indexOf(long sequence) {
            for (int index = 0; index < count(); index++) {
                if (sequences[index] == sequence) return index;
            }
            return -1;
        }

        /** True when an anchor's own entry is older than everything the client still keeps. */
        public boolean evicted(long sequence) {
            return count() > 0 && sequence < sequences[0];
        }
    }

    /**
     * What one frame draws.
     *
     * @param scrollTop  pixels of content above the body's top edge, already clamped
     * @param total      the whole stack's height
     * @param bodyHeight the visible body height
     * @param first      index of the oldest entry that intersects the body, or -1
     * @param last       index of the newest entry that intersects the body, or -1
     */
    public record View(int scrollTop, int total, int bodyHeight, int first, int last) {

        public boolean empty() {
            return first < 0 || last < first;
        }

        /** Entries that intersect the body, partial ones included. */
        public int count() {
            return empty() ? 0 : last - first + 1;
        }

        public boolean scrollable() {
            return total > Math.max(0, bodyHeight);
        }

        /** Distance in pixels from the newest end, 0 = following it: the probe's scroll position. */
        public int offsetPx() {
            return Math.max(0, total - Math.max(0, bodyHeight) - scrollTop);
        }

        public int maxScroll() {
            return Math.max(0, total - Math.max(0, bodyHeight));
        }
    }

    /** Nothing retained, nothing drawn. */
    public static final View EMPTY = new View(0, 0, 0, -1, -1);

    /**
     * Where the content's top edge really starts: the anchor resolved, then clamped into the legal
     * range - which is what keeps a shrunk body or an evicted anchor from stranding the view.
     */
    public static int scrollTop(Content content, Anchor anchor) {
        if (content == null || content.count() <= 0) return 0;
        int max = content.maxScroll();
        if (anchor == null || anchor.newest()) return max;
        int index = content.indexOf(anchor.sequence());
        if (index < 0) {
            // The browsed entry left the bounded history: clamp to the nearest position that still
            // exists rather than jumping to the newest end, which would lose the reader's place.
            return content.evicted(anchor.sequence()) ? 0 : max;
        }
        int into = Math.max(0, Math.min(content.intoLimit(index), anchor.intoPx()));
        return Math.max(0, Math.min(max, content.top(index) + into));
    }

    /**
     * The anchor for a scroll position. The newest end is not an entry but a subscription to it, so a
     * position at (or past) the bottom becomes {@link Anchor#NEWEST} and keeps following new entries.
     */
    public static Anchor anchorAt(Content content, int scrollTop) {
        if (content == null || content.count() <= 0) return Anchor.NEWEST;
        int max = content.maxScroll();
        int clamped = Math.max(0, Math.min(max, scrollTop));
        if (clamped >= max) return Anchor.NEWEST;
        for (int index = content.count() - 1; index >= 0; index--) {
            int top = content.top(index);
            if (clamped >= top) return new Anchor(content.sequences()[index], clamped - top);
        }
        return Anchor.NEWEST;
    }

    /**
     * Browses {@code deltaPx} pixels: positive moves into older history, negative towards the newest
     * end. The result is anchored again, so the state never keeps a raw pixel distance that a resize
     * or an eviction would make meaningless.
     */
    public static Anchor shift(Content content, Anchor anchor, int deltaPx) {
        if (content == null || content.count() <= 0) return Anchor.NEWEST;
        // Older content is above: browsing back takes pixels off the scrolled distance.
        int moved = scrollTop(content, anchor) - deltaPx;
        return anchorAt(content, moved);
    }

    /** The entries that intersect the body, with the clamp that keeps the view inside the stack. */
    public static View visible(Content content, int scrollTop) {
        if (content == null || content.count() <= 0) return EMPTY;
        int total = content.total();
        int body = Math.max(0, content.bodyHeight());
        int max = Math.max(0, total - body);
        int top = Math.max(0, Math.min(max, scrollTop));
        int bottom = top + body;
        int first = -1;
        int last = -1;
        for (int index = 0; index < content.count(); index++) {
            int entryTop = content.top(index);
            int entryBottom = entryTop + content.height(index);
            // Half-open ranges: an entry that ends exactly on the body's top edge is not visible.
            if (entryBottom <= top || entryTop >= bottom) continue;
            if (first < 0) first = index;
            last = index;
        }
        return new View(top, total, body, first, last);
    }

    /**
     * Where the thumb sits from the track's top: the oldest end is the top of the track and the newest
     * end its bottom, which is the direction the user asked for. Measured in the same pixels as
     * everything else here, so the thumb is the body's share of the stack.
     */
    public static int thumbOffset(Content content, int scrollTop) {
        if (content == null || content.count() <= 0) return 0;
        return Math.max(0, Math.min(content.maxScroll(), scrollTop));
    }
}
