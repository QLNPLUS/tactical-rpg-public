package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the entrance state that could never be released: a panel prepared with the raw
 * substring {@code ";opacity:0;"} stayed transparent forever, because AUI 1.2.8 re-serializes the
 * whole style attribute whenever any declaration is written - {@code UiZoom} writes the menu root
 * font-size once per client tick - and the literal removal then matches nothing.
 *
 * <p>These tests drive the real installed AUI element/declaration API (no mocked styles), so a future
 * AUI change that reintroduces attribute rewriting without honouring declarations fails here.</p>
 */
class WindowEntranceStyleTest {

    private static Element panel() {
        Document document = new Document("tactical/screens/battle_inventory.html", false);
        return new Element(document, "div");
    }

    @Test
    void hiddenPanelIsReleasedEvenAfterAuiRewritesTheStyleAttribute() {
        Element panel = panel();
        WindowEntrance.hide(panel);
        assertEquals("0", panel.getInlineStylePropertyValue("opacity"));

        // UiZoom writes the root font-size every tick, which rewrites the attribute canonically.
        panel.setInlineStyleProperty("font-size", "14.0px");
        assertEquals("0", panel.getInlineStylePropertyValue("opacity"),
                "the prepared opacity must survive an unrelated declaration write");
        assertTrue(String.valueOf(panel.getAttribute("style")).contains("opacity"),
                "sanity: the declaration is still in the attribute: " + panel.getAttribute("style"));

        WindowEntrance.show(panel);
        assertEquals("", panel.getInlineStylePropertyValue("opacity"),
                "releasing the panel must leave no opacity declaration behind");
        assertFalse(String.valueOf(panel.getAttribute("style")).contains("opacity"),
                "the attribute must be free of opacity: " + panel.getAttribute("style"));
    }

    @Test
    void releasingThePanelKeepsEveryOtherDeclaration() {
        Element panel = panel();
        WindowEntrance.hide(panel);
        panel.setInlineStyleProperty("font-size", "15px");
        WindowEntrance.show(panel);
        assertEquals("15px", panel.getInlineStylePropertyValue("font-size"),
                "the interface-size declaration must survive the entrance");
    }

    @Test
    void animationSpecCanStillBeClearedAfterTheAttributeIsRewritten() {
        Element panel = panel();
        WindowEntrance.animate(panel);
        assertEquals("tactical-window-enter " + EntranceTiming.DURATION_MS + "ms ease-out 1 both",
                panel.getInlineStylePropertyValue("animation"));
        panel.setInlineStyleProperty("font-size", "14.0px");
        WindowEntrance.clearAnimation(panel);
        assertEquals("", panel.getInlineStylePropertyValue("animation"));
        assertFalse(String.valueOf(panel.getAttribute("style")).contains("animation"),
                "the attribute must be free of the animation spec: " + panel.getAttribute("style"));
    }

    @Test
    void theOldSubstringMarkerIsGenuinelyUnremovableAfterARewrite() {
        // Pins the defect itself: once AUI rewrites the attribute, the old literal marker is gone,
        // so a String.replace cleanup cannot release the panel.
        Element panel = panel();
        panel.setAttribute("style", ";opacity:0;");
        panel.setInlineStyleProperty("font-size", "14.0px");
        String canonical = String.valueOf(panel.getAttribute("style"));
        assertFalse(canonical.contains(";opacity:0;"), "the recorded cell was " + canonical);
        assertEquals(canonical, canonical.replace(";opacity:0;", ""));
        assertEquals("0", panel.getInlineStylePropertyValue("opacity"),
                "the panel would still be hidden, which is exactly the observed product defect");
    }

    @Test
    void repeatedInstallForTheSameGenerationDoesNotPrepareAgain() {
        WindowEntrance.Entrance first = new WindowEntrance.Entrance(7L, List.of());
        assertTrue(WindowEntrance.needsEntrance(null, 7L), "a new document needs its entrance");
        assertFalse(WindowEntrance.needsEntrance(first, 7L), "the same generation must not be prepared twice");
        assertTrue(WindowEntrance.needsEntrance(first, 8L), "a refreshed document needs a new entrance");
    }
}
