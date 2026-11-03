package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.theme.Palette;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour settings' wording, checked against the two language files that really ship.
 *
 * <p>The point is not that some strings exist: it is that the English labels the popover is measured
 * with are the ones in {@code en_us.json}, that the Chinese fallback cannot drift from
 * {@code zh_cn.json}, and that the longest real English label is what makes the three-button row
 * stack. A hard-coded guess about label width would let the English layout break silently.</p>
 */
class ColorTextTest {

    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");

    @Test void everyNewKeyIsInBothShippedLanguageFiles() throws IOException {
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        for (String key : ColorText.KEYS) {
            assertNotNull(zh.get(key), "zh_cn.json is missing " + key);
            assertNotNull(en.get(key), "en_us.json is missing " + key);
            assertFalse(en.get(key).isEmpty(), "en_us.json has an empty value for " + key);
            assertEquals(zh.get(key), ColorText.fallback(key),
                    "the pure fallback must be the same wording the Chinese file ships for " + key);
        }
    }

    @Test void theEnglishLabelsAreTheOnesTheLayoutIsMeasuredAgainst() throws IOException {
        Map<String, String> en = lang("en_us.json");
        assertEquals(ColorText.EN_CANCEL, en.get(ColorText.CANCEL));
        assertEquals(ColorText.EN_SAVE, en.get(ColorText.SAVE));
        assertEquals(ColorText.EN_RESET, en.get(ColorText.RESET));

        // Six pixels per character is the vanilla font's own average for this alphabet, which is why
        // "Cancel" comes out at about 36 - exactly the number the visual specification quotes.
        int cancelWidth = ColorText.EN_CANCEL.length() * 6;
        assertEquals(36, cancelWidth);
        assertTrue(cancelWidth > 22, "a 32-pixel column leaves 22 for text: Cancel cannot fit it");

        Rect gear = Rect.of(500, 40, 96, 200);
        ColorPopupLayout english = ColorPopupLayout.compute(640, 360, 1.0D, true, gear, true,
                new ColorPopupLayout.Content(Palette.THEME_PRESETS, text -> text.length() * 6,
                        en.get(ColorText.RESET), en.get(ColorText.CANCEL), en.get(ColorText.SAVE)));
        assertTrue(english.buttonsStacked(), "the real English labels force the vertical stack");

        Map<String, String> zh = lang("zh_cn.json");
        ColorPopupLayout chinese = ColorPopupLayout.compute(640, 360, 1.0D, true, gear, true,
                new ColorPopupLayout.Content(Palette.THEME_PRESETS, text -> text.length() * 6,
                        zh.get(ColorText.RESET), zh.get(ColorText.CANCEL), zh.get(ColorText.SAVE)));
        assertFalse(chinese.buttonsStacked(), "the Chinese labels fit three columns");
        assertTrue(english.panel().h() > chinese.panel().h(),
                "the stacked rows really add height instead of overflowing the panel");
    }

    @Test void theThreeButtonsAreDrawnWithWordingKeysNotControlIds() {
        // R1 regression: the popover used to hand each button its own control id as the text key, so
        // the footer read "color-reset / color-cancel / color-save". The mapping below is the only
        // place id and wording are connected, and neither the id nor an unknown key may come back out.
        String[] ids = {ColorPopupLayout.RESET, ColorPopupLayout.CANCEL, ColorPopupLayout.SAVE};
        for (String id : ids) {
            String key = ColorText.buttonKey(id);
            assertNotEquals(id, key, "a control id is not a text key");
            assertTrue(ColorText.KEYS.contains(key), key + " has to be one of the shipped keys");
            assertNotEquals(key, ColorText.fallback(key), key + " has to resolve to real wording");
        }
        assertEquals(ColorText.RESET, ColorText.buttonKey(ColorPopupLayout.RESET));
        assertEquals(ColorText.CANCEL, ColorText.buttonKey(ColorPopupLayout.CANCEL));
        assertEquals(ColorText.SAVE, ColorText.buttonKey(ColorPopupLayout.SAVE));
        assertThrows(IllegalArgumentException.class, () -> ColorText.buttonKey(ColorPopupLayout.SV));
    }

    // ------------------------------------------------------------------ helpers

    /** Reads one shipped language file, searching upwards from the module directory. */
    private static Map<String, String> lang(String name) throws IOException {
        Path file = locate(name);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(text);
        while (matcher.find()) map.put(matcher.group(1), matcher.group(2));
        return map;
    }

    private static Path locate(String name) {
        String relative = "src/main/resources/assets/tactical/lang/" + name;
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = root; candidate != null; candidate = candidate.getParent()) {
            Path direct = candidate.resolve(relative);
            if (Files.isRegularFile(direct)) return direct;
            Path nested = candidate.resolve("neoforge-1.21.1").resolve(relative);
            if (Files.isRegularFile(nested)) return nested;
        }
        throw new IllegalStateException("cannot find the language file " + name + " from " + root);
    }
}
