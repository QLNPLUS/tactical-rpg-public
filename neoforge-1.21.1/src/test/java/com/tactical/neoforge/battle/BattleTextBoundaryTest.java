package com.tactical.neoforge.battle;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The literal-text cleaning of player names and finished text.
 *
 * <p>Both public entry points bound their text (a name at 64 UTF-16 units, finished text at 240) after
 * removing formatting codes and control characters. The bound must never cut a supplementary-plane
 * character in half: a lone surrogate is not text, it is a corrupted string that no font can draw and
 * that survives every later copy. The cleaning must also not let a removed formatting code disguise the
 * text that follows it.</p>
 *
 * <p>The limits are UTF-16 units, exactly like the journal's own literal path, so the same input is
 * bounded identically in both places.</p>
 */
class BattleTextBoundaryTest {

    /** U+1D11E MUSICAL SYMBOL G CLEF: one code point, two UTF-16 units, no emoji in the source. */
    private static final String SUPPLEMENTARY = new String(Character.toChars(0x1D11E));

    private static String name(String text) {
        return BattleText.name(text).getString();
    }

    private static String literal(String text) {
        return BattleText.literal(text).getString();
    }

    @Test
    void aNameBoundaryNeverSplitsASupplementaryCharacter() {
        // 63 units fit, 63 + 2 do not: the character is dropped whole, the string is not cut in half.
        String dropped = name("a".repeat(63) + SUPPLEMENTARY);
        assertEquals("a".repeat(63), dropped);
        assertNoLoneSurrogate(dropped);

        // 62 units + the character exactly fill the bound and are kept whole.
        String kept = name("a".repeat(62) + SUPPLEMENTARY);
        assertEquals(64, kept.length(), "the bound is 64 UTF-16 units");
        assertTrue(kept.endsWith(SUPPLEMENTARY), "the whole character survives");
        assertNoLoneSurrogate(kept);
    }

    @Test
    void aLiteralBoundaryNeverSplitsASupplementaryCharacter() {
        String dropped = literal("a".repeat(239) + SUPPLEMENTARY);
        assertEquals(239, dropped.length());
        assertNoLoneSurrogate(dropped);

        String kept = literal("a".repeat(238) + SUPPLEMENTARY);
        assertEquals(240, kept.length(), "the bound is 240 UTF-16 units");
        assertTrue(kept.endsWith(SUPPLEMENTARY), "the whole character survives");
        assertNoLoneSurrogate(kept);

        // Plain text right at the bound is kept as is.
        assertEquals(240, literal("b".repeat(240)).length());
        assertEquals(240, literal("b".repeat(400)).length());
        assertEquals(64, name("c".repeat(64)).length());
    }

    @Test
    void controlCharactersAndFormatCodesAreStillCleaned() {
        // A control character becomes a space, and leading/trailing space is trimmed.
        assertEquals("A B", name("A\u0001B"));
        assertEquals("AB", name("\u0001AB"));
        assertEquals("AB", name("AB\t"));

        // A formatting code is removed together with its marker, so it cannot disguise what follows.
        assertEquals("红色", name("§c红色"));
        assertEquals("ab", name("a§cb"));
        assertEquals("x", name("§" + SUPPLEMENTARY + "x"));
        assertEquals("ab", name("ab§"));
        assertEquals("", name("§c"));
    }

    @Test
    void ordinaryChineseAndKeyLookingNamesStayLiteral() {
        Component chinese = BattleText.name("红色药水");
        assertEquals("红色药水", chinese.getString());
        assertTrue(chinese.getContents() instanceof PlainTextContents.LiteralContents,
                "typed Chinese is text, not a translation key");

        // A name that merely looks like a key is never treated as one.
        Component keyLooking = BattleText.name(BattleText.KEY_BATTLE_JOIN);
        assertEquals(BattleText.KEY_BATTLE_JOIN, keyLooking.getString());
        assertTrue(keyLooking.getContents() instanceof PlainTextContents.LiteralContents,
                "a name is never resolved as a key");
    }

    @Test
    void anAbsentOrEmptyNameProducesEmptyText() {
        assertEquals("", BattleText.name((String) null).getString());
        assertEquals("", name(""));
        assertEquals("", literal("§§"));
        assertFalse(BattleText.literal(null).getString().contains("\u00a7"));
    }

    private static void assertNoLoneSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)),
                        "a high surrogate at " + index + " is not followed by a low one");
                index++;
            } else {
                assertFalse(Character.isLowSurrogate(current), "a low surrogate at " + index + " has no pair");
            }
        }
    }
}
