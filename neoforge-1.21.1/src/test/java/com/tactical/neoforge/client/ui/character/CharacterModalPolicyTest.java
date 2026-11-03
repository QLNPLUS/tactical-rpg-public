package com.tactical.neoforge.client.ui.character;

import com.tactical.neoforge.client.ui.layout.ScrollList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns a key, a wheel notch or a pointer gesture while the character page is open.
 *
 * <p>This is the routing the battle input chain asks before its hotbar, inventory and camera branches, so it
 * is checked here in both states: with the page closed nothing changes, and with it open nothing behind it can
 * react while its own keys still arrive.</p>
 */
class CharacterModalPolicyTest {

    @Test void withThePageClosedNothingChanges() {
        for (CharacterModalPolicy.Key key : CharacterModalPolicy.Key.values()) {
            assertEquals(CharacterModalPolicy.Route.VANILLA,
                    CharacterModalPolicy.route(false, false, key), "key " + key);
            assertEquals(CharacterModalPolicy.Route.VANILLA,
                    CharacterModalPolicy.route(false, true, key), "key " + key);
        }
        assertFalse(CharacterModalPolicy.ownsWheel(false));
        assertEquals(0, CharacterModalPolicy.wheelStep(false, 1.0D));
        assertEquals(CharacterModalPolicy.Pointer.DEFAULT, CharacterModalPolicy.pointer(false));
        assertFalse(CharacterModalPolicy.swallowResidualGesture(false));
    }

    @Test void thePagesOwnKeysReachThePage() {
        for (CharacterModalPolicy.Key key : new CharacterModalPolicy.Key[]{
                CharacterModalPolicy.Key.TAB, CharacterModalPolicy.Key.SHIFT_TAB,
                CharacterModalPolicy.Key.ENTER, CharacterModalPolicy.Key.SPACE}) {
            assertEquals(CharacterModalPolicy.Route.PAGE,
                    CharacterModalPolicy.route(true, false, key), "key " + key);
        }
        assertEquals(CharacterModalPolicy.Route.CLOSE_PAGE,
                CharacterModalPolicy.route(true, false, CharacterModalPolicy.Key.ESCAPE));
    }

    @Test void hotbarAndInventoryKeysAreSwallowedInAndOutOfBattle() {
        // The same answer in both states: the page never asks whether a battle is running.
        for (CharacterModalPolicy.Key key : new CharacterModalPolicy.Key[]{
                CharacterModalPolicy.Key.HOTBAR, CharacterModalPolicy.Key.INVENTORY}) {
            assertEquals(CharacterModalPolicy.Route.CONSUME, CharacterModalPolicy.route(true, false, key));
            assertEquals(CharacterModalPolicy.Route.CONSUME, CharacterModalPolicy.route(true, true, key));
        }
        assertEquals(CharacterModalPolicy.Route.CONSUME,
                CharacterModalPolicy.route(true, false, CharacterModalPolicy.Key.OTHER));
    }

    @Test void escapeClosesThePopupBeforeThePage() {
        assertEquals(CharacterModalPolicy.Route.CLOSE_POPUP,
                CharacterModalPolicy.route(true, true, CharacterModalPolicy.Key.ESCAPE));
        assertEquals(CharacterModalPolicy.Route.PAGE,
                CharacterModalPolicy.route(true, true, CharacterModalPolicy.Key.ENTER));
    }

    /**
     * The sign is GLFW's, and it is the shared lists' rule.
     *
     * <p>A notch <em>downwards</em> arrives as a negative delta and must step the page's window forward, so the
     * content rises - the same direction the roster and the journal scroll. The previous version returned
     * {@code +1} for a positive delta, which scrolled this page against every other list; the user reported that
     * as a reversed wheel.</p>
     */
    @Test void theWheelBelongsToThePageWhereverThePointerIs() {
        assertTrue(CharacterModalPolicy.ownsWheel(true));
        assertEquals(-1, CharacterModalPolicy.wheelStep(true, 1.0D), "one notch up steps back");
        assertEquals(1, CharacterModalPolicy.wheelStep(true, -1.0D), "one notch down steps forward");
        for (double delta : new double[]{-7.0D, -0.25D, 0.25D, 7.0D}) {
            assertEquals((int) -Math.signum(delta), CharacterModalPolicy.wheelStep(true, delta),
                    "any notch size, sign " + delta);
        }
        // The page's step and the shared list's rule are one rule, which is what "same as the roster" means.
        assertEquals(ScrollList.scrollBy(3, 10, 4, -1.0D), 3 + CharacterModalPolicy.wheelStep(true, -1.0D),
                "a notch down matches ScrollList.scrollBy");
        assertEquals(ScrollList.scrollBy(3, 10, 4, 1.0D), 3 + CharacterModalPolicy.wheelStep(true, 1.0D),
                "a notch up matches ScrollList.scrollBy");
        assertEquals(0, CharacterModalPolicy.wheelStep(true, 0.0D), "a horizontal-only event is not a notch");
        assertEquals(0, CharacterModalPolicy.wheelStep(true, Double.NaN));
        assertEquals(0, CharacterModalPolicy.wheelStep(true, Double.POSITIVE_INFINITY));
    }

    @Test void everyButtonBelongsToThePageAndAResidualGestureIsCancelled() {
        assertEquals(CharacterModalPolicy.Pointer.PAGE, CharacterModalPolicy.pointer(true));
        assertTrue(CharacterModalPolicy.swallowResidualGesture(true));
    }

    @Test void onlyTheLeftButtonActivatesAWhileModalPageSwallowsEveryButton() {
        assertTrue(CharacterModalPolicy.activates(0), "left activates");
        assertFalse(CharacterModalPolicy.activates(1), "right never upgrades or allocates");
        assertFalse(CharacterModalPolicy.activates(2), "middle never does either");
        for (int button : new int[]{0, 1, 2}) assertTrue(CharacterModalPolicy.swallows(button),
                "the modal page still swallows button " + button);
    }

    @Test void aRawKeyIsReducedToWhatThePageCaresAbout() {
        assertEquals(CharacterModalPolicy.Key.ESCAPE,
                CharacterModalPolicy.classify(256, false, false, false, false, true, false, false));
        assertEquals(CharacterModalPolicy.Key.SHIFT_TAB,
                CharacterModalPolicy.classify(258, true, true, false, false, false, false, false));
        assertEquals(CharacterModalPolicy.Key.TAB,
                CharacterModalPolicy.classify(258, true, false, false, false, false, false, false));
        assertEquals(CharacterModalPolicy.Key.ENTER,
                CharacterModalPolicy.classify(257, false, false, true, false, false, false, false));
        assertEquals(CharacterModalPolicy.Key.SPACE,
                CharacterModalPolicy.classify(32, false, false, false, true, false, false, false));
        assertEquals(CharacterModalPolicy.Key.HOTBAR,
                CharacterModalPolicy.classify(49, false, false, false, false, false, true, false));
        assertEquals(CharacterModalPolicy.Key.INVENTORY,
                CharacterModalPolicy.classify(69, false, false, false, false, false, false, true));
        assertEquals(CharacterModalPolicy.Key.OTHER,
                CharacterModalPolicy.classify(66, false, false, false, false, false, false, false));
    }
}
