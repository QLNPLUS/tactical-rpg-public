package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.ColorText;
import com.tactical.neoforge.client.ui.hud.HudLayout;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.platform.TextStamp;
import com.tactical.neoforge.client.ui.theme.Motion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review round's rules, as checks: the two resets in the gear popover keep their own wording, a
 * value that must survive truncation does, a shortened line always keeps its whole text, the journal's
 * chip column cannot cut a label at any width the layout allows, and a rebuilt page's text is rewritten.
 */
class ClientTextFixRoundTest {

    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final ToIntFunction<String> ESTIMATE = text -> {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += text.charAt(index) > 0x2E80 ? 9 : 6;
        }
        return width;
    };

    // ---------------------------------------------------------------- R2: one popover, two resets

    @Test
    void theZoomResetAndTheHudSizeResetAreTwoDifferentWordingKeys() throws IOException {
        assertEquals("tactical.ui.gear.reset_zoom", ClientText.Keys.GEAR_RESET_ZOOM);
        assertNotEquals(ClientText.Keys.GEAR_RESET_ZOOM, ColorText.GEAR_RESET_SIZES,
                "the size preset's reset and the HUD sizes' reset must not share a key");
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        for (String key : List.of(ClientText.Keys.GEAR_RESET_ZOOM, ColorText.GEAR_RESET_SIZES)) {
            assertNotNull(zh.get(key), "zh_cn is missing " + key);
            assertNotNull(en.get(key), "en_us is missing " + key);
        }
        assertNotEquals(zh.get(ClientText.Keys.GEAR_RESET_ZOOM), zh.get(ColorText.GEAR_RESET_SIZES),
                "the two buttons must read differently in Chinese");
        assertNotEquals(en.get(ClientText.Keys.GEAR_RESET_ZOOM), en.get(ColorText.GEAR_RESET_SIZES),
                "the two buttons must read differently in English");
        // The popover's inner width is fixed, so the new label has to fit it without being cut.
        int budget = Motion.PANEL_WIDTH_NARROW - Motion.PAD * 2 - Motion.BUTTON_PAD_X * 2;
        for (Map<String, String> language : List.of(zh, en)) {
            assertTrue(ESTIMATE.applyAsInt(language.get(ClientText.Keys.GEAR_RESET_ZOOM)) <= budget,
                    "the zoom reset label must fit the popover: " + language.get(ClientText.Keys.GEAR_RESET_ZOOM));
        }
    }

    // ---------------------------------------------------------------- R1-2 / R3-1: values survive

    @Test
    void aValueThatMustStayKeepsTheEnd() {
        String head = "Left click to attack · right click to go back";
        String tail = " · 10 AP";
        for (int budget = ESTIMATE.applyAsInt(tail); budget <= ESTIMATE.applyAsInt(head + tail); budget++) {
            String shown = TextMetrics.ellipsisKeepingTail(head, tail, budget, 1.0D, ESTIMATE);
            assertTrue(shown.endsWith(tail), "the price must survive at budget " + budget + ": " + shown);
            assertTrue(ESTIMATE.applyAsInt(shown) <= Math.max(budget, ESTIMATE.applyAsInt(tail)),
                    "the kept line must fit its budget: " + shown);
        }
        // When even the tail is too wide the tail is what remains: dropping it would hide the value.
        assertEquals(" · 10 AP", TextMetrics.ellipsisKeepingTail(head, tail, 4, 1.0D, ESTIMATE));
    }

    @Test
    void theRecoveryLineKeepsItsNumberAsItsOwnKey() throws IOException {
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        assertEquals("+%s", en.get(ClientText.Keys.HUD_RECOVERY_AMOUNT));
        assertEquals("+%s", zh.get(ClientText.Keys.HUD_RECOVERY_AMOUNT));
        // "+50" fits the space the recovery line gets once the AP figures have taken theirs.
        int footerWidth = 140 - Motion.PAD * 2;
        int figures = ESTIMATE.applyAsInt("AP ") + ESTIMATE.applyAsInt("100") + ESTIMATE.applyAsInt(" / 150");
        int left = footerWidth - figures - 6;
        assertTrue(ESTIMATE.applyAsInt("+50") <= left, "the amount fits beside a three-digit AP pair");
    }

    // ---------------------------------------------------------------- R3-2: a cut line stays readable

    @Test
    void theJournalChipColumnCannotCutALabelAtAnyAllowedWidth() throws IOException {
        // The chip column is derived from the widest real label; a label may only be cut if the panel were
        // narrower than its own readable floor plus that label, which the width range excludes. This is
        // the check that lets the chip stay tooltip-free without hiding anything.
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        for (Map<String, String> language : List.of(zh, en)) {
            int widest = 0;
            for (String key : List.of(ClientText.Keys.JOURNAL_BATTLE, ClientText.Keys.JOURNAL_TURN,
                    ClientText.Keys.JOURNAL_MOVE, ClientText.Keys.JOURNAL_ATTACK, ClientText.Keys.JOURNAL_DAMAGE,
                    ClientText.Keys.JOURNAL_HEAL, ClientText.Keys.JOURNAL_EFFECT, ClientText.Keys.JOURNAL_DEATH,
                    ClientText.Keys.JOURNAL_BLOCK, ClientText.Keys.JOURNAL_REJECT, ClientText.Keys.JOURNAL_ENEMY,
                    ClientText.Keys.JOURNAL_INVENTORY, ClientText.Keys.JOURNAL_ACTION)) {
                widest = Math.max(widest, ESTIMATE.applyAsInt(language.get(key)));
            }
            int chipColumn = widest + Motion.CHIP_PAD_X * 2;
            for (int designWidth = HudLayout.ROSTER_MIN_WIDTH; designWidth <= HudLayout.ROSTER_MAX_WIDTH;
                 designWidth += 5) {
                int inner = Scale.effectivePx(designWidth, 1.0D) - Motion.PAD * 2;
                assertTrue(chipColumn + HudLayout.JOURNAL_CHIP_MIN_TEXT <= inner,
                        "a " + designWidth + "-wide journal still fits its chip column");
            }
        }
    }

    // ---------------------------------------------------------------- R4: rebuilt page, rewritten text

    @Test
    void aRebuiltDocumentMakesTheTextStaleAgain() {
        long language = "en_us".hashCode();
        long resources = 7L;
        long document = 11L;
        long stamp = TextStamp.of(language, resources, document);
        assertEquals(stamp, TextStamp.of(language, resources, document), "an idle frame keeps the text");
        assertNotEquals(stamp, TextStamp.of("zh_cn".hashCode(), resources, document),
                "a language change rewrites the page");
        assertNotEquals(stamp, TextStamp.of(language, resources + 1, document),
                "a resource reload rewrites the page");
        assertNotEquals(stamp, TextStamp.of(language, resources, document + 1),
                "a rebuilt document rewrites the page - its fresh elements hold the placeholder text");
        assertFalse(TextStamp.of(language, resources, document) == 0L);
    }

    @Test
    void theEnglishActionButtonsAreShortEnoughToBeToldApart() throws IOException {
        Map<String, String> en = lang("en_us.json");
        String main = en.get(ClientText.Keys.INV_CTX_MAIN);
        String off = en.get(ClientText.Keys.INV_CTX_OFF);
        assertNotNull(main);
        assertNotNull(off);
        assertNotEquals(main, off, "the two hand buttons must say different things");
        assertFalse(main.startsWith("Swap to ") && off.startsWith("Swap to "),
                "sharing a cut-off prefix is what made them indistinguishable");
        // The action box is 180 CSS pixels wide with its own padding: the labels must fit whole.
        for (String label : List.of(main, off, en.get(ClientText.Keys.INV_CTX_DROP),
                en.get(ClientText.Keys.INV_CTX_CANCEL))) {
            assertTrue(ESTIMATE.applyAsInt(label) <= 120, label + " must fit the action box");
        }
    }

    // ---------------------------------------------------------------- helpers

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
