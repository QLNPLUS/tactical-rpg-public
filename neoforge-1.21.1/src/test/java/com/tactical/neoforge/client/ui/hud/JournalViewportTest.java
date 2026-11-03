package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal's scroll contract, checked without a game window.
 *
 * <p>The user's screenshots asked a direct question - "does the next entry disappear when it does not
 * fit?" - and the answer used to be yes: the body packed whole entries and left a large blank band
 * under the title while older lines sat right above the fold. These assertions record the replacement
 * contract: a continuous pixel viewport in which an entry that does not fit is drawn as far as it
 * reaches, a single entry taller than the body can be read line by line, the newest end still hugs the
 * body's bottom edge, and the bar, the rows and the probe describe the same frame.</p>
 */
class JournalViewportTest {

    private static final int LINE = 9;
    private static final int GAP = 2;

    /** Twelve retained entries, newest first, mixing one, two and three text lines. */
    private static final int[] MIXED = {2, 1, 3, 1, 2, 1, 1, 2, 3, 1, 2, 1};

    @Test
    void theBodyIsFilledToItsEdgesInsteadOfStoppingAtAWholeEntry() {
        // 25px of body, a stack whose second entry starts at 11 and is 18px tall: the old contract
        // dropped it and left five pixels of blank body behind.
        JournalViewport.Content content = JournalFixture.of(MIXED, LINE, GAP, 25);
        JournalViewport.View view = JournalViewport.visible(content, 0);
        assertEquals(0, view.first(), "the body starts at the oldest retained entry");
        assertTrue(content.top(view.last()) < view.bodyHeight(), "the last visible entry starts inside the body");
        assertTrue(content.top(view.last()) + content.height(view.last()) > view.bodyHeight(),
                "and it is cut by the bottom edge: that is the partial entry the user asked for");
        assertTrue(content.top(view.last() + 1) >= view.bodyHeight(), "no further entry starts inside the body");
    }

    @Test
    void aSingleEntryTallerThanTheBodyCanBeReadLineByLine() {
        // One eight-line entry in a two-line body: it used to be cut with an ellipsis, so its middle
        // lines were unreachable however far the reader scrolled.
        JournalViewport.Content content = JournalFixture.of(new int[]{8}, LINE, GAP, 2 * LINE);
        JournalViewport.Anchor anchor = JournalViewport.Anchor.NEWEST;
        assertEquals(8 * LINE - 2 * LINE, JournalViewport.scrollTop(content, anchor),
                "the newest end shows the entry's last two lines");
        int[] atTopEdge = new int[8];
        for (int notch = 0; notch < 8; notch++) {
            JournalViewport.View view = JournalViewport.visible(content, JournalViewport.scrollTop(content, anchor));
            assertEquals(0, view.first(), "the tall entry is the only retained one");
            int line = (view.scrollTop() - content.top(0)) / LINE;
            if (line >= 0 && line < atTopEdge.length) atTopEdge[line]++;
            int before = JournalViewport.scrollTop(content, anchor);
            anchor = JournalViewport.shift(content, anchor, LINE);
            if (JournalViewport.scrollTop(content, anchor) == before) break;
        }
        assertEquals(0, JournalViewport.scrollTop(content, anchor), "scrolling up reaches the entry's head");
        for (int line = 0; line < atTopEdge.length - 1; line++) {
            assertEquals(1, atTopEdge[line], "every line reaches the body's top edge, none is skipped");
        }
    }

    @Test
    void theNewestEndHugsTheBottomAndFollowsNewEntries() {
        // Fewer pixels than the body: the stack is anchored at the bottom, blank space above it.
        JournalViewport.Content shortContent = JournalFixture.of(new int[]{1, 1}, LINE, GAP, 75);
        assertEquals(0, JournalViewport.scrollTop(shortContent, JournalViewport.Anchor.NEWEST),
                "a stack shorter than the body is not scrolled anywhere");
        assertEquals(0, JournalViewport.visible(shortContent, 0).offsetPx());

        JournalViewport.Content tall = JournalFixture.of(MIXED, LINE, GAP, 40);
        assertEquals(tall.maxScroll(), JournalViewport.scrollTop(tall, JournalViewport.Anchor.NEWEST),
                "following the newest end means the bottom of the stack");
        assertTrue(JournalViewport.anchorAt(tall, tall.maxScroll()).newest(),
                "a position at the bottom is the subscription to new entries, not an entry");
        // A new entry only makes the stack taller at the newest end: the same anchor keeps the
        // reader's place, so the entries above the fold are exactly the ones that were there before.
        JournalViewport.Anchor browsing = JournalViewport.anchorAt(tall, 20);
        int[] grown = new int[MIXED.length + 1];
        grown[0] = 3;
        System.arraycopy(MIXED, 0, grown, 1, MIXED.length);
        JournalViewport.Content after = JournalFixture.of(grown, LINE, GAP, 40);
        assertEquals(20, JournalViewport.scrollTop(after, browsing),
                "the anchored position is the same pixel offset from the oldest end");
        assertEquals(browsing.sequence(), JournalViewport.anchorAt(after,
                JournalViewport.scrollTop(after, browsing)).sequence(),
                "and the same entry is under the body's top edge");
        assertEquals(tall.maxScroll(),
                JournalViewport.scrollTop(after, JournalViewport.Anchor.NEWEST) - 3 * LINE - GAP,
                "while the newest end followed the new entry: its own height and gap moved it down");
    }

    @Test
    void theOffsetIsClampedAtBothEndsAndBeyond() {
        JournalViewport.Content content = JournalFixture.uniform(6, 6, LINE, GAP, 40);
        assertEquals(0, JournalViewport.scrollTop(content, JournalViewport.Anchor.NEWEST) - content.maxScroll(),
                "the newest end is the bottom of the stack");
        JournalViewport.Anchor far = JournalViewport.shift(content, JournalViewport.Anchor.NEWEST, 999);
        assertEquals(0, JournalViewport.scrollTop(content, far), "browsing past the top clamps to the oldest");
        assertEquals(1, JournalViewport.anchorAt(content, 0).sequence(), "the oldest retained entry");
        JournalViewport.Anchor back = JournalViewport.shift(content, JournalViewport.Anchor.NEWEST, -999);
        assertTrue(back.newest(), "browsing past the bottom returns to following the newest entry");
        assertEquals(content.maxScroll(), JournalViewport.visible(content, 4 * LINE).scrollTop(),
                "an out-of-range scroll position is clamped into the stack");
    }

    @Test
    void anEvictedAnchorClampsToTheNearestPositionThatStillExists() {
        JournalViewport.Content before = JournalFixture.uniform(10, 100, LINE, GAP, 20);
        JournalViewport.Anchor oldest = JournalViewport.anchorAt(before, 0);
        assertEquals(91, oldest.sequence(), "the reader is at the oldest retained entry");
        // The bounded history dropped its first four entries: the anchor is gone, the reader is not.
        JournalViewport.Content after = JournalFixture.uniform(6, 100, LINE, GAP, 20);
        assertEquals(0, JournalViewport.scrollTop(after, oldest),
                "an evicted anchor clamps to the oldest entry that still exists");

        JournalViewport.Anchor kept = JournalViewport.anchorAt(before, 4 * (LINE + GAP));
        assertEquals(95, kept.sequence(), "an entry the eviction kept");
        assertEquals(0, JournalViewport.scrollTop(after, kept),
                "and it is now the oldest, so the body's top edge lands on it");
        assertEquals(95, JournalViewport.anchorAt(after, 0).sequence(), "the same entry, not the newest one");
    }

    @Test
    void aWrappedEntryKeepsTheBrowsedEntryAndTheClamp() {
        // The same history wrapped twice as wide: every entry is twice as tall, the anchor still names
        // the same entry and the position is clamped into the new stack.
        JournalViewport.Content narrow = JournalFixture.of(MIXED, LINE, GAP, 40);
        JournalViewport.Anchor anchor = JournalViewport.anchorAt(narrow, 25);
        int[] wider = new int[MIXED.length];
        for (int index = 0; index < MIXED.length; index++) wider[index] = MIXED[index] * 2;
        JournalViewport.Content wrapped = JournalFixture.of(wider, LINE, GAP, 40);
        int scrollTop = JournalViewport.scrollTop(wrapped, anchor);
        assertTrue(scrollTop >= 0 && scrollTop <= wrapped.maxScroll(), "the position stays inside the stack");
        assertEquals(JournalViewport.anchorAt(wrapped, scrollTop).sequence(), anchor.sequence(),
                "the same entry is still the one under the body's top edge");
        // Shrinking the body keeps the bottom reachable instead of stranding the reader below it.
        JournalViewport.Content shrunk = JournalFixture.of(wider, LINE, GAP, 18);
        assertTrue(JournalViewport.scrollTop(shrunk, anchor) <= shrunk.maxScroll());
    }

    @Test
    void theThumbStaysInsideTheTrackAndEndsAtTheNewestEntry() {
        JournalViewport.Content content = JournalFixture.of(MIXED, LINE, GAP, 40);
        Rect area = Rect.of(10, 30, 128, 40);
        Rect track = ScrollList.track(area, content.total(), content.bodyHeight(), 1.0D);
        assertTrue(track.h() > 0, "a stack taller than the body means a bar");
        int max = content.maxScroll();
        Rect newestThumb = ScrollList.thumb(track, content.total(), content.bodyHeight(),
                JournalViewport.thumbOffset(content, max), 1.0D);
        assertEquals(track.bottom(), newestThumb.bottom(), "the newest end puts the thumb at the track's bottom");
        Rect oldestThumb = ScrollList.thumb(track, content.total(), content.bodyHeight(),
                JournalViewport.thumbOffset(content, 0), 1.0D);
        assertEquals(track.y(), oldestThumb.y(), "the oldest end puts it at the track's top");
        for (int scrollTop = 0; scrollTop <= max; scrollTop += LINE) {
            Rect thumb = ScrollList.thumb(track, content.total(), content.bodyHeight(),
                    JournalViewport.thumbOffset(content, scrollTop), 1.0D);
            assertTrue(thumb.y() >= track.y(), "scrollTop " + scrollTop + " keeps the thumb inside the top edge");
            assertTrue(thumb.bottom() <= track.bottom(), "scrollTop " + scrollTop + " keeps it inside the bottom");
            assertTrue(thumb.h() >= ScrollList.MIN_THUMB || thumb.h() == track.h(), "the thumb keeps its minimum");
        }
    }

    @Test
    void aHistoryThatFitsProducesNoScrollbar() {
        JournalViewport.Content content = JournalFixture.of(new int[]{1, 1, 1}, LINE, GAP, 75);
        assertFalse(content.scrollable());
        assertEquals(Rect.EMPTY, ScrollList.track(Rect.of(0, 0, 128, 75), content.total(),
                content.bodyHeight(), 1.0D), "nothing to scroll means no track");
        assertTrue(JournalViewport.anchorAt(content, 0).newest(), "and the newest entry is the whole content");
    }

    @Test
    void anEmptyHistoryDrawsNothing() {
        assertEquals(JournalViewport.EMPTY, JournalViewport.visible(null, 0));
        JournalViewport.Content empty = new JournalViewport.Content(new long[0], new int[0], GAP, LINE, 75);
        assertEquals(JournalViewport.EMPTY, JournalViewport.visible(empty, 3));
        assertEquals(JournalViewport.Anchor.NEWEST, JournalViewport.anchorAt(empty, 0));
        assertEquals(0, JournalViewport.scrollTop(empty, JournalViewport.Anchor.NEWEST));
        assertEquals(0, empty.total());
        assertEquals(0, empty.count());
        assertEquals(JournalViewport.Anchor.NEWEST, JournalViewport.shift(empty, JournalViewport.Anchor.NEWEST, 9));
    }
}
