package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.panel.LayoutKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a cached panel layout is stale: the window or the preset moved, or the content fingerprint
 * did. Nothing else triggers a rebuild, so a frame-by-frame redraw never relayouts.
 */
class LayoutKeyTest {

    private static final LayoutKey FULLHD = new LayoutKey(640, 360, 1.0D, 0x1234L);

    @Test void anUnchangedKeyKeepsTheLayout() {
        assertTrue(FULLHD.matches(new LayoutKey(640, 360, 1.0D, 0x1234L)));
        assertTrue(FULLHD.matches(new LayoutKey(640, 360, 1.00004D, 0x1234L)), "the preset is a percentage");
    }

    @Test void aDifferentWindowOrPresetRebuilds() {
        assertFalse(FULLHD.matches(new LayoutKey(641, 360, 1.0D, 0x1234L)));
        assertFalse(FULLHD.matches(new LayoutKey(640, 361, 1.0D, 0x1234L)));
        assertFalse(FULLHD.matches(new LayoutKey(640, 360, 1.05D, 0x1234L)));
    }

    @Test void aDifferentFingerprintRebuildsEvenWhenTheWindowDidNotMove() {
        assertFalse(FULLHD.matches(new LayoutKey(640, 360, 1.0D, 0x1235L)),
                "a new skill, a language change or a saved size has to reach the layout");
        assertFalse(FULLHD.matches(null), "nothing built yet is stale");
    }
}
