package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scroll rules every list shares: whole rows only, one row per notch, a clamped offset and a
 * two-pixel bar that never shrinks below its minimum thumb.
 */
class ScrollListTest {

    @Test void onlyWholeRowsAreCounted() {
        assertEquals(0, ScrollList.rows(0, 20, 2));
        assertEquals(1, ScrollList.rows(20, 20, 2), "a single row needs no trailing gap");
        assertEquals(1, ScrollList.rows(40, 20, 2), "42 would be needed for two rows");
        assertEquals(2, ScrollList.rows(42, 20, 2));
        assertEquals(64, ScrollList.height(3, 20, 2));
    }

    @Test void theOffsetIsClampedToWholeRows() {
        assertEquals(0, ScrollList.clamp(-4, 10, 4));
        assertEquals(6, ScrollList.clamp(9, 10, 4), "the last window starts at n - visible");
        assertEquals(0, ScrollList.clamp(3, 2, 4), "nothing to scroll means no offset");
        assertEquals(6, ScrollList.maxOffset(10, 4));
    }

    @Test void oneNotchIsOneRow() {
        assertEquals(1, ScrollList.scrollBy(0, 10, 4, -1.0D));
        assertEquals(0, ScrollList.scrollBy(0, 10, 4, 1.0D), "already at the newest row");
        assertEquals(6, ScrollList.scrollBy(6, 10, 4, -1.0D));
        assertEquals(0, ScrollList.scrollBy(0, 4, 4, -1.0D), "a list that does not scroll keeps offset 0");
    }

    @Test void theBarExistsOnlyWhileThereIsSomethingToScroll() {
        Rect area = Rect.of(10, 20, 128, 80);
        assertEquals(Rect.EMPTY, ScrollList.track(area, 3, 4, 1.0D));
        Rect track = ScrollList.track(area, 10, 4, 1.0D);
        assertEquals(2, track.w());
        assertEquals(area.right() - 2, track.x());
        assertTrue(ScrollList.scrollable(10, 4));
        assertFalse(ScrollList.scrollable(4, 4));
    }

    @Test void theThumbKeepsItsMinimumHeightAndTracksTheOffset() {
        Rect track = Rect.of(120, 20, 2, 100);
        Rect top = ScrollList.thumb(track, 40, 4, 0, 1.0D);
        assertEquals(track.h() / 10, top.h(), "the thumb is proportional here");
        assertEquals(track.y(), top.y());
        Rect bottom = ScrollList.thumb(track, 40, 4, 36, 1.0D);
        assertEquals(track.bottom(), bottom.bottom(), "a full offset puts the thumb at the end");

        Rect tiny = ScrollList.thumb(track, 4000, 1, 0, 1.0D);
        assertEquals(8, tiny.h(), "the thumb never falls below its minimum");
    }

    @Test void rowsGiveUpFourPixelsOnlyWhenTheBarIsDrawn() {
        Rect area = Rect.of(10, 20, 128, 80);
        assertEquals(128, ScrollList.rowWidth(area, 3, 4, 1.0D));
        assertEquals(124, ScrollList.rowWidth(area, 10, 4, 1.0D));
    }
}
