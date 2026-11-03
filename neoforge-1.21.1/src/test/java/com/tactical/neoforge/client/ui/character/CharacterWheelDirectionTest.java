package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one real wheel notch does to the character page's body.
 *
 * <p>The page had its own sign rule and that rule was inverted: a notch downwards stepped the table towards
 * the top of the list while the roster and the journal stepped forward, so the same gesture moved this page
 * the other way. The check here is the composed one - the policy's step, applied by the layout, clamped at
 * both ends - because the sign function alone would not catch a second flip on the way in.</p>
 */
class CharacterWheelDirectionTest {

    /** The specification's own estimate: 6 px per Latin glyph, 9 px per CJK glyph. */
    private static final ToIntFunction<String> MEASURE = text -> {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += text.charAt(index) < 0x2E80 ? 6 : 9;
        }
        return width;
    };

    private static final List<String> ZH = List.of("确认加点", "取消草稿", "升级");
    private static final List<String> POPUP = List.of("Confirm level up", "Cancel");

    /** Every stat editable: the state in which the table has the most items to scroll. */
    private static final CharacterLayout.Content EDITABLE =
            new CharacterLayout.Content(true, false, false, false, false, true, true, true,
                    CharacterLayout.Content.mask(true, true, true, true, true, true),
                    CharacterLayout.Content.mask(true, true, true, true, true, true));

    /** A short window at a high preset: the form from the acceptance list whose body really scrolls. */
    private static CharacterLayout shortWindow(int scroll) {
        return CharacterLayout.build(427, 240, 1.15D, EDITABLE, MEASURE, ZH, POPUP, scroll);
    }

    private static int rowTop(CharacterLayout layout, CharacterStat stat) {
        for (CharacterLayout.StatRow row : layout.statRows()) {
            if (row.stat() == stat) return row.row().y();
        }
        return Integer.MIN_VALUE;
    }

    @Test void theShortWindowReallyScrolls() {
        CharacterLayout layout = shortWindow(0);
        assertTrue(layout.scrollable(), "the body of a 427x240 window at 115% does not fit");
        assertTrue(layout.maxScrollOffset() >= 2, "and it has more than one notch of content below");
    }

    @Test void aNotchDownStepsForwardAndANotchUpStepsBack() {
        CharacterLayout top = shortWindow(0);
        int down = top.scrolled(CharacterModalPolicy.wheelStep(true, -1.0D));
        assertEquals(top.scrollOffset() + 1, down, "a notch down moves the window one item forward");

        // The window really moved: the next stat is at the top, and a row both frames carry is drawn higher.
        CharacterLayout stepped = shortWindow(down);
        assertEquals(top.statRows().get(1).stat(), stepped.statRows().getFirst().stat(),
                "the second stat is at the top after one notch down");
        CharacterStat shared = stepped.statRows().getFirst().stat();
        assertTrue(rowTop(stepped, shared) < rowTop(top, shared),
                "a row both frames carry is drawn higher after the window moved forward");

        CharacterLayout second = shortWindow(1);
        assertEquals(0, second.scrolled(CharacterModalPolicy.wheelStep(true, 1.0D)),
                "a notch up moves it back to the top");
        assertEquals(2, second.scrolled(CharacterModalPolicy.wheelStep(true, -1.0D)),
                "and a second notch down moves it two items in");
    }

    @Test void bothEndsAreClampedAndAPageThatFitsNeverMoves() {
        CharacterLayout top = shortWindow(0);
        assertEquals(0, top.scrolled(CharacterModalPolicy.wheelStep(true, 1.0D)), "the top is the top");

        CharacterLayout bottom = shortWindow(top.maxScrollOffset());
        int maximum = bottom.maxScrollOffset();
        assertEquals(maximum, bottom.scrolled(CharacterModalPolicy.wheelStep(true, -1.0D)),
                "the end is the end");
        assertEquals(maximum - 1, bottom.scrolled(CharacterModalPolicy.wheelStep(true, 1.0D)),
                "and a notch up still works from the end");

        // A tall window draws the whole body, so the same notch changes nothing at all.
        CharacterLayout tall = CharacterLayout.build(640, 360, 1.00D, EDITABLE, MEASURE, ZH, POPUP, 0);
        assertFalse(tall.scrollable(), "640x360 at 100% shows the whole body");
        assertEquals(0, tall.scrolled(CharacterModalPolicy.wheelStep(true, -1.0D)));
        assertEquals(0, tall.scrolled(CharacterModalPolicy.wheelStep(true, 1.0D)));
    }
}
