package com.tactical.neoforge.client.ui.character;

/**
 * Input ownership of the character page, as pure decisions.
 *
 * <p>The page is a modal: while it is open every key, every wheel notch and every pointer gesture belongs to
 * it, wherever the pointer is, so nothing reaches the hotbar, the battle page, the HUD or the camera behind
 * it. This class only decides <em>who</em> owns the event; the screen decides what to do with it. Keeping the
 * decision separate is what lets the routing be checked without a game window, and it is the single place the
 * battle input chain asks before its own hotbar and camera branches.</p>
 */
public final class CharacterModalPolicy {

    /** A key, reduced to what the page cares about. */
    public enum Key { TAB, SHIFT_TAB, ENTER, SPACE, ESCAPE, HOTBAR, INVENTORY, OTHER }

    /** What has to happen with that key. */
    public enum Route {
        /** Handled inside the page (focus move, activation, confirmation). */
        PAGE,
        /** Closes the level-up confirmation and nothing else. */
        CLOSE_POPUP,
        /** Closes the page. */
        CLOSE_PAGE,
        /** Swallowed with no effect, so it cannot reach the hotbar, the inventory or the battle page. */
        CONSUME,
        /** The page is not open; ordinary routing applies. */
        VANILLA
    }

    /** Who receives one pointer gesture. */
    public enum Pointer { PAGE, DEFAULT }

    private CharacterModalPolicy() { }

    /** Escaping a popup closes only the popup; escaping the page closes the page. */
    public static Route route(boolean pageOpen, boolean popupOpen, Key key) {
        if (!pageOpen) return Route.VANILLA;
        if (key == null) return Route.CONSUME;
        if (popupOpen) {
            return switch (key) {
                case ESCAPE -> Route.CLOSE_POPUP;
                case ENTER, SPACE, TAB, SHIFT_TAB -> Route.PAGE;
                case HOTBAR, INVENTORY, OTHER -> Route.CONSUME;
            };
        }
        return switch (key) {
            case ESCAPE -> Route.CLOSE_PAGE;
            case TAB, SHIFT_TAB, ENTER, SPACE -> Route.PAGE;
            case HOTBAR, INVENTORY, OTHER -> Route.CONSUME;
        };
    }

    /** The wheel belongs to the page whenever it is open, so no notch reaches the HUD, hotbar or camera. */
    public static boolean ownsWheel(boolean pageOpen) { return pageOpen; }

    /**
     * One notch is applied by the page itself, with the same sign rule as every shared list
     * ({@code ScrollList.scrollBy}: offset minus the delta's own sign).
     *
     * <p>GLFW reports a notch <em>downwards</em> as a negative delta, and the page's step moves its window
     * forward, so content rises. Returning {@code +1} for a positive delta - the previous version - scrolled
     * the table against the roster and the journal, which is what the user felt as a reversed wheel. The
     * result is {@code +1} forward (content up), {@code -1} back, and 0 when the event carries no vertical
     * delta at all.</p>
     */
    public static int wheelStep(boolean pageOpen, double scrollY) {
        if (!pageOpen || !Double.isFinite(scrollY) || scrollY == 0.0D) return 0;
        return scrollY > 0 ? -1 : 1;
    }

    /** Every button belongs to the page while it is open, including middle/right gestures of a camera mod. */
    public static Pointer pointer(boolean pageOpen) { return pageOpen ? Pointer.PAGE : Pointer.DEFAULT; }

    /**
     * Only the left button activates a control.
     *
     * <p>Right and middle clicks are still swallowed by the modal page - they must never reach the HUD, the
     * hotbar or the camera behind it - but they can never confirm an upgrade or an allocation either.</p>
     */
    public static boolean activates(int button) { return button == 0; }

    /** True when the page swallows this button; every button is swallowed while the page is open. */
    public static boolean swallows(int button) { return true; }

    /** A gesture that started over the page must never be handed to the camera when it ends elsewhere. */
    public static boolean swallowResidualGesture(boolean pageOpen) { return pageOpen; }

    /** Which reduced key a raw GLFW code is, given the player's own bindings. */
    public static Key classify(int keyCode, boolean tab, boolean shiftTab, boolean enter, boolean space,
                               boolean escape, boolean hotbar, boolean inventory) {
        if (escape || keyCode == 256) return Key.ESCAPE;
        if (shiftTab) return Key.SHIFT_TAB;
        if (tab) return Key.TAB;
        if (enter) return Key.ENTER;
        if (space) return Key.SPACE;
        if (hotbar) return Key.HOTBAR;
        if (inventory) return Key.INVENTORY;
        return Key.OTHER;
    }
}
