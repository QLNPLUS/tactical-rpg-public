package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.UiEnvironmentKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What invalidates a cached panel layout from outside the page.
 *
 * <p>The regression this locks: the fingerprint used to compare the same {@code Font} object and its
 * line height, so a resource pack or language reload that swapped glyph metrics inside that object
 * left every cached layout in place. The reload generation is the real signal; the other inputs stay
 * as cheap guards.</p>
 */
class UiEnvironmentKeyTest {

    private static final long REVISION = 3L;
    private static final String LANGUAGE = "zh_cn";
    private static final long GENERATION = 7L;
    private static final int FONT_IDENTITY = 0x1f2e3d4c;
    private static final int LINE_HEIGHT = 9;

    private static long key(long revision, String language, long generation, int identity, int lineHeight) {
        return UiEnvironmentKey.of(revision, language, generation, identity, lineHeight);
    }

    @Test void nothingChangingKeepsTheKeyStable() {
        long first = key(REVISION, LANGUAGE, GENERATION, FONT_IDENTITY, LINE_HEIGHT);
        for (int frame = 0; frame < 100; frame++) {
            assertEquals(first, key(REVISION, LANGUAGE, GENERATION, FONT_IDENTITY, LINE_HEIGHT),
                    "an idle frame must not rebuild the layout");
        }
    }

    @Test void aResourceReloadInTheSameLanguageInvalidatesTheLayout() {
        long before = key(REVISION, LANGUAGE, GENERATION, FONT_IDENTITY, LINE_HEIGHT);
        long after = key(REVISION, LANGUAGE, GENERATION + 1, FONT_IDENTITY, LINE_HEIGHT);
        assertNotEquals(before, after, "the language may be identical and the metrics still change");
    }

    @Test void everyOtherInputAlsoCounts() {
        long base = key(REVISION, LANGUAGE, GENERATION, FONT_IDENTITY, LINE_HEIGHT);
        assertNotEquals(base, key(REVISION + 1, LANGUAGE, GENERATION, FONT_IDENTITY, LINE_HEIGHT),
                "a saved preference changed");
        assertNotEquals(base, key(REVISION, "en_us", GENERATION, FONT_IDENTITY, LINE_HEIGHT),
                "the language changed");
        assertNotEquals(base, key(REVISION, LANGUAGE, GENERATION, FONT_IDENTITY + 1, LINE_HEIGHT),
                "the client handed out a different font object");
        assertNotEquals(base, key(REVISION, LANGUAGE, GENERATION, FONT_IDENTITY, LINE_HEIGHT + 1),
                "the font reports a different line height");
        assertNotEquals(base, key(REVISION, null, GENERATION, FONT_IDENTITY, LINE_HEIGHT),
                "a missing language is its own value, not a silent zero");
    }
}
