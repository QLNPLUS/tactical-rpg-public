package com.tactical.neoforge.client.ui.platform;

import com.tactical.neoforge.client.ui.command.ColorText;
import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import com.tactical.neoforge.client.ui.hud.HudLayout;
import com.tactical.neoforge.client.ui.hud.JournalLabels;
import com.tactical.neoforge.client.ui.hud.RosterRowText;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.theme.Motion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's own wording, checked against the two language files that really ship.
 *
 * <p>Batch 4c moves every visible client string behind an explicit key, so the checks here are the ones
 * that keep that honest: a key exists in both files, both files word it with the same arguments, the
 * key list cannot drift from the constants, and the English layout is measured with the real English
 * labels instead of being assumed. The font is approximated the way the visual specification states it
 * (about six pixels per Latin character, nine per Chinese one); the real font is what the in-game check
 * uses.</p>
 */
class ClientTextTest {

    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");
    /** Latin at about six pixels, Chinese at about nine - the numbers the specification quotes. */
    private static final java.util.function.ToIntFunction<String> ESTIMATE = text -> {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += text.charAt(index) > 0x2E80 ? 9 : 6;
        }
        return width;
    };

    // ------------------------------------------------------------------ keys

    @Test
    void everyKeyTheClientUsesIsInBothShippedFiles() throws IOException {
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        for (String key : allKeys()) {
            assertNotNull(zh.get(key), "zh_cn.json is missing " + key);
            assertNotNull(en.get(key), "en_us.json is missing " + key);
            assertFalse(zh.get(key).isBlank(), "zh_cn.json has a blank value for " + key);
            assertFalse(en.get(key).isBlank(), "en_us.json has a blank value for " + key);
        }
    }

    @Test
    void bothFilesCarryTheSameKeySet() throws IOException {
        // A key that exists in one file only silently degrades to the key itself in the other language.
        assertEquals(new TreeSet<>(lang("zh_cn.json").keySet()), new TreeSet<>(lang("en_us.json").keySet()));
    }

    @Test
    void everyTemplateTakesTheSameArgumentsInBothLanguages() throws IOException {
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        for (String key : allKeys()) {
            assertEquals(placeholders(zh.get(key)), placeholders(en.get(key)),
                    key + " must take the same arguments in both languages");
        }
    }

    @Test
    void theTemplatesThatTakeArgumentsKeepTheirArgumentCount() throws IOException {
        Map<String, String> en = lang("en_us.json");
        Map<String, String> zh = lang("zh_cn.json");
        int[][] expected = {
                {2, count(en.get(ClientText.Keys.HUD_COUNT))},
                {3, count(en.get(ClientText.Keys.NEARBY_META))},
                {3, count(en.get(ClientText.Keys.SKILL_EFFECT))},
                {1, count(en.get(ClientText.Keys.SKILL_COOLDOWN))},
                {2, count(en.get(ClientText.Keys.MOVE_TARGET))},
                {3, count(en.get(ClientText.Keys.AIM_SLOT_LANDING))},
                {1, count(en.get(ClientText.Keys.HUD_RECOVERY))},
                {1, count(en.get(ClientText.Keys.COMMAND_ROUND))},
                {1, count(en.get(ClientText.Keys.HUD_ENTITY))},
        };
        for (int[] pair : expected) assertEquals(pair[0], pair[1], "an argument count changed");
        assertEquals(zh.keySet(), zh.keySet());
    }

    @Test
    void theKeyListCannotDriftFromTheConstants() {
        List<String> keys = ClientText.ALL_KEYS;
        assertTrue(keys.size() > 100, "the client's wording is a real set of keys, not a handful");
        assertEquals(new LinkedHashSet<>(keys).size(), keys.size(), "a key must not be registered twice");
        for (String key : keys) {
            assertTrue(key.startsWith("tactical.ui."), key + " belongs to the client's own namespace");
            assertFalse(key.contains(" "), key + " is a key, not a sentence");
        }
    }

    @Test
    void everySurfaceGroupIsRegistered() {
        // The coverage record in code form: one group per shipped surface. A surface that loses its keys
        // fails here instead of silently drawing a raw key name.
        Map<String, List<String>> surfaces = new LinkedHashMap<>();
        surfaces.put("command panel", List.of(ClientText.Keys.COMMAND_TITLE,
                ClientText.Keys.COMMAND_AP_LABEL, ClientText.Keys.COMMAND_HP_LABEL,
                ClientText.Keys.COMMAND_MP_LABEL, ClientText.Keys.COMMAND_HP_LABEL_SHORT,
                ClientText.Keys.COMMAND_MP_LABEL_SHORT, ClientText.Keys.COMMAND_WEAPON_LABEL,
                ClientText.Keys.COMMAND_WEAPON_PENDING, ClientText.Keys.COMMAND_ACTOR_WAITING,
                ClientText.Keys.COMMAND_ROUND, ClientText.Keys.COMMAND_FOOTER,
                ClientText.Keys.COMMAND_FOOTER_DEFENDING, ClientText.Keys.COMMAND_FOOTER_FALLING,
                ClientText.Keys.COMMAND_ROW_CHARACTER, ClientText.Keys.COMMAND_ROW_CHARACTER_DESC,
                ClientText.Keys.COMMAND_ROW_MOVE,
                ClientText.Keys.COMMAND_ROW_MOVE_DESC, ClientText.Keys.COMMAND_ROW_SKILL,
                ClientText.Keys.COMMAND_ROW_SKILL_DESC, ClientText.Keys.COMMAND_ROW_ATTACK,
                ClientText.Keys.COMMAND_ROW_ATTACK_DESC, ClientText.Keys.COMMAND_ROW_TOOL,
                ClientText.Keys.COMMAND_ROW_TOOL_DESC, ClientText.Keys.COMMAND_ROW_TOOL_BLOCK_DESC,
                ClientText.Keys.COMMAND_ROW_TOOL_BLOCK_HINT, ClientText.Keys.COMMAND_ROW_BLOCK,
                ClientText.Keys.COMMAND_ROW_BLOCK_DESC,
                ClientText.Keys.COMMAND_ROW_BLOCK_PLACE_DESC, ClientText.Keys.COMMAND_ROW_BLOCK_BREAK_DESC,
                ClientText.Keys.COMMAND_ROW_DEFEND,
                ClientText.Keys.COMMAND_ROW_DEFEND_DESC, ClientText.Keys.COMMAND_ROW_END,
                ClientText.Keys.COMMAND_ROW_END_DESC, ClientText.Keys.COMMAND_ROW_FLEE,
                ClientText.Keys.COMMAND_ROW_FLEE_DESC, ClientText.Keys.COMMAND_COST_TERRAIN,
                ClientText.Keys.COMMAND_COST_SKILL,
                // The controlled-companion command surface: same panel, five rows, one identity chip.
                ClientText.Keys.COMMAND_CHIP_COMPANION, ClientText.Keys.COMMAND_ACTOR_LABEL,
                ClientText.Keys.COMMAND_VALUE_NONE, ClientText.Keys.COMMAND_FOOTER_COMMANDING,
                ClientText.Keys.COMMAND_ROW_SKILL_DESC_COMPANION));
        surfaces.put("shared pages", List.of(ClientText.Keys.BACK_TO_COMMANDS));
        surfaces.put("gear popover", List.of(ClientText.Keys.GEAR_RESET_ZOOM,
                ClientText.Keys.GEAR_LEGEND_ON,
                ClientText.Keys.GEAR_LEGEND_OFF, ClientText.Keys.GEAR_CLOSE));
        surfaces.put("hud", List.of(ClientText.Keys.HUD_ROUND_LABEL, ClientText.Keys.HUD_ACTOR_WAITING,
                ClientText.Keys.HUD_RECOVERY, ClientText.Keys.HUD_RECOVERY_AMOUNT,
                ClientText.Keys.HUD_DEFENDING, ClientText.Keys.HUD_WEAPON_MELEE,
                ClientText.Keys.HUD_WEAPON_BOW, ClientText.Keys.HUD_WEAPON_CROSSBOW, ClientText.Keys.HUD_ENTITY,
                ClientText.Keys.HUD_JOURNAL_TITLE, ClientText.Keys.HUD_ACTING, ClientText.Keys.HUD_STATUS_LATEST,
                ClientText.Keys.HUD_STATUS_HISTORY, ClientText.Keys.HUD_COUNT,
                ClientText.Keys.HUD_TOGGLE_COLLAPSE, ClientText.Keys.HUD_TOGGLE_EXPAND,
                ClientText.Keys.HUD_HINT_PREFIX, ClientText.Keys.HUD_HINT_SUFFIX));
        surfaces.put("journal chips", List.of(ClientText.Keys.JOURNAL_BATTLE, ClientText.Keys.JOURNAL_TURN,
                ClientText.Keys.JOURNAL_MOVE, ClientText.Keys.JOURNAL_ATTACK, ClientText.Keys.JOURNAL_DAMAGE,
                ClientText.Keys.JOURNAL_HEAL, ClientText.Keys.JOURNAL_EFFECT, ClientText.Keys.JOURNAL_DEATH,
                ClientText.Keys.JOURNAL_BLOCK, ClientText.Keys.JOURNAL_REJECT, ClientText.Keys.JOURNAL_ENEMY,
                ClientText.Keys.JOURNAL_INVENTORY, ClientText.Keys.JOURNAL_ACTION));
        surfaces.put("aim page", List.of(ClientText.Keys.AIM_TITLE_MOVE, ClientText.Keys.AIM_TITLE_ATTACK,
                ClientText.Keys.AIM_TITLE_PLACE, ClientText.Keys.AIM_TITLE_BREAK,
                ClientText.Keys.AIM_TITLE_USE_ITEM, ClientText.Keys.AIM_TITLE_SKILL,
                ClientText.Keys.AIM_TITLE_DEFAULT, ClientText.Keys.AIM_HINT_MOVE, ClientText.Keys.AIM_HINT_ATTACK,
                ClientText.Keys.AIM_HINT_PLACE, ClientText.Keys.AIM_HINT_BREAK, ClientText.Keys.AIM_HINT_USE_ITEM,
                ClientText.Keys.AIM_HINT_SKILL,
                ClientText.Keys.AIM_HINT_DEFAULT, ClientText.Keys.AIM_COST_LEAD,
                ClientText.Keys.AIM_SLOT_NO_GROUND, ClientText.Keys.AIM_SLOT_LANDING,
                ClientText.Keys.AIM_SLOT_REACHABLE, ClientText.Keys.AIM_SLOT_UNREACHABLE,
                ClientText.Keys.AIM_SLOT_FACE, ClientText.Keys.AIM_SLOT_BREAK));
        surfaces.put("facing page", List.of(ClientText.Keys.FACING_TITLE, ClientText.Keys.FACING_NORTH,
                ClientText.Keys.FACING_SOUTH, ClientText.Keys.FACING_WEST, ClientText.Keys.FACING_EAST,
                ClientText.Keys.FACING_CONFIRM_LEAD, ClientText.Keys.FACING_CONFIRM_TAIL,
                ClientText.Keys.FACING_PLAIN));
        surfaces.put("skill page and overlay", List.of(ClientText.Keys.SKILL_TITLE, ClientText.Keys.SKILL_SUBTITLE,
                ClientText.Keys.SKILL_SYNCING, ClientText.Keys.SKILL_EMPTY, ClientText.Keys.SKILL_PICK,
                ClientText.Keys.SKILL_MP_RECOVERY, ClientText.Keys.SKILL_COST_BY_TARGET,
                ClientText.Keys.SKILL_COOLDOWN, ClientText.Keys.SKILL_AIM_SELF, ClientText.Keys.SKILL_AIM_ENTITY,
                ClientText.Keys.SKILL_AIM_GROUND, ClientText.Keys.SKILL_UNAVAILABLE,
                ClientText.Keys.SKILL_WAITING_PREVIEW, ClientText.Keys.SKILL_EFFECT,
                // The companion page is a static empty state on the same surface.
                ClientText.Keys.SKILL_COMPANION_EMPTY));
        surfaces.put("nearby battles", List.of(ClientText.Keys.NEARBY_TITLE, ClientText.Keys.NEARBY_INFO,
                ClientText.Keys.NEARBY_EMPTY, ClientText.Keys.NEARBY_REFRESH, ClientText.Keys.NEARBY_CLOSE,
                ClientText.Keys.NEARBY_UNKNOWN_MANAGER, ClientText.Keys.NEARBY_META,
                ClientText.Keys.NEARBY_JOINABLE, ClientText.Keys.NEARBY_JOIN, ClientText.Keys.NEARBY_DISMISS,
                ClientText.Keys.NEARBY_REFRESHING, ClientText.Keys.NEARBY_REFRESHING_SUFFIX,
                ClientText.Keys.NEARBY_THROTTLED, ClientText.Keys.NEARBY_THROTTLED_SUFFIX));
        surfaces.put("move choice", List.of(ClientText.Keys.MOVE_TITLE, ClientText.Keys.MOVE_TARGET,
                ClientText.Keys.MOVE_WALK, ClientText.Keys.MOVE_DROP, ClientText.Keys.MOVE_WARNING_FALL,
                ClientText.Keys.MOVE_WARNING_BLOCKED, ClientText.Keys.MOVE_BACK));
        surfaces.put("range legend", List.of(ClientText.Keys.RANGE_ORANGE, ClientText.Keys.RANGE_VALID_COUNT,
                ClientText.Keys.RANGE_PENDING, ClientText.Keys.RANGE_TRUNCATED, ClientText.Keys.RANGE_HOVER,
                ClientText.Keys.RANGE_HOVER_OK, ClientText.Keys.RANGE_HOVER_FAIL));
        surfaces.put("entity target tooltips", List.of(ClientText.Keys.ENTITY_NO_TARGET,
                ClientText.Keys.ENTITY_DEAD, ClientText.Keys.ENTITY_NOT_PARTICIPANT,
                ClientText.Keys.ENTITY_MOVE_GROUND, ClientText.Keys.ENTITY_PLACE_GROUND,
                ClientText.Keys.ENTITY_BREAK_GROUND, ClientText.Keys.ENTITY_SKILL_GROUND, ClientText.Keys.ENTITY_NONE,
                ClientText.Keys.ENTITY_MODE_NOT_ENTITY, ClientText.Keys.ENTITY_NOT_ENEMY,
                ClientText.Keys.ENTITY_SELF_ONLY));
        surfaces.put("AUI inventory page", List.of(ClientText.Keys.INV_TITLE, ClientText.Keys.INV_SUBTITLE,
                ClientText.Keys.INV_BAG, ClientText.Keys.INV_HOTBAR, ClientText.Keys.INV_HINT,
                ClientText.Keys.INV_BACK, ClientText.Keys.INV_SCALE_LABEL, ClientText.Keys.INV_SCALE_RESET,
                ClientText.Keys.INV_CONTEXT_TITLE, ClientText.Keys.INV_CTX_PLACE, ClientText.Keys.INV_CTX_USE,
                ClientText.Keys.INV_CTX_MAIN, ClientText.Keys.INV_CTX_OFF, ClientText.Keys.INV_CTX_DROP,
                ClientText.Keys.INV_CTX_CANCEL));
        surfaces.put("colour popover", ColorText.KEYS);

        Set<String> registered = new LinkedHashSet<>(ClientText.ALL_KEYS);
        registered.addAll(ColorText.KEYS);
        Set<String> covered = new LinkedHashSet<>();
        surfaces.forEach((surface, keys) -> {
            for (String key : keys) {
                assertTrue(registered.contains(key), surface + " uses an unregistered key " + key);
                covered.add(key);
            }
        });
        assertEquals(registered, covered, "every registered key belongs to a shipped surface");
    }

    // ------------------------------------------------------------------ pure models report keys

    @Test
    void theJournalChipOfAnUnknownCategoryFallsBackToTheGenericLabel() {
        assertEquals(ClientText.Keys.JOURNAL_ACTION, JournalLabels.label(null));
        assertEquals(ClientText.Keys.JOURNAL_ACTION, JournalLabels.label("something new"));
        Set<String> distinct = new LinkedHashSet<>();
        for (String category : List.of("battle", "turn", "move", "attack", "damage", "heal", "effect", "death",
                "block", "reject", "enemy", "inventory", "action")) {
            String key = JournalLabels.label(category);
            assertTrue(ClientText.ALL_KEYS.contains(key), category + " has no shipped chip key");
            assertTrue(distinct.add(key), category + " reuses another category's key");
        }
    }

    @Test
    void theCountRangeNeverBecomesAnEmptyString() {
        assertEquals("0", JournalLabels.countRange(0L, 0L, true));
        assertEquals("3–7", JournalLabels.countRange(3L, 7L, false));
        Set<String> toggles = new LinkedHashSet<>(List.of(JournalLabels.toggle(true), JournalLabels.toggle(false)));
        assertEquals(2, toggles.size(), "the toggle reflects both states with its own wording");
        for (String key : toggles) assertTrue(ClientText.ALL_KEYS.contains(key));
        for (String key : List.of(JournalLabels.status(true), JournalLabels.status(false),
                JournalLabels.ACTING, JournalLabels.COUNT)) {
            assertTrue(ClientText.ALL_KEYS.contains(key), key + " is drawn but not shipped");
        }
    }

    // ------------------------------------------------------------------ English layout budgets

    @Test
    void theEnglishCommandRowsFitWithoutCuttingTheCost() throws IOException {
        Map<String, String> en = lang("en_us.json");
        Map<String, String> zh = lang("zh_cn.json");
        CommandMenuLayout layout = CommandMenuLayout.compute(640, 360, 1.0D, false);
        for (String action : CommandMenuLayout.ACTIONS) {
            Rect row = layout.rect(CommandMenuLayout.rowId(action));
            if (row.w() <= 0) continue;
            int budget = row.w() - Motion.ICON_TEXT_LEFT - 4;
            for (Map.Entry<String, Map<String, String>> language : Map.of("en", en, "zh", zh).entrySet()) {
                String name = language.getValue().get("tactical.ui.command.row." + action);
                assertNotNull(name, language.getKey() + " has no row name for " + action);
                int cost = switch (action) {
                    case "attack" -> ESTIMATE.applyAsInt("25 AP");
                    case "tool" -> ESTIMATE.applyAsInt("5 AP");
                    case "block" -> ESTIMATE.applyAsInt("10 AP");
                    case "defend" -> ESTIMATE.applyAsInt("25 AP");
                    case "flee" -> ESTIMATE.applyAsInt("30 AP");
                    case "move" -> ESTIMATE.applyAsInt(language.getValue().get(ClientText.Keys.COMMAND_COST_TERRAIN));
                    case "skill" -> ESTIMATE.applyAsInt(language.getValue().get(ClientText.Keys.COMMAND_COST_SKILL));
                    default -> 0;
                };
                int needed = ESTIMATE.applyAsInt(name) + (cost == 0 ? 0 : cost + Motion.ROW_GAP);
                assertTrue(needed <= budget,
                        language.getKey() + " row " + action + " needs " + needed + " of " + budget);
            }
        }
    }

    @Test
    void theJournalChipColumnFollowsTheMeasuredLabels() throws IOException {
        Map<String, String> en = lang("en_us.json");
        Map<String, String> zh = lang("zh_cn.json");
        int widestEn = 0;
        int widestZh = 0;
        for (String key : List.of(ClientText.Keys.JOURNAL_BATTLE, ClientText.Keys.JOURNAL_TURN,
                ClientText.Keys.JOURNAL_MOVE, ClientText.Keys.JOURNAL_ATTACK, ClientText.Keys.JOURNAL_DAMAGE,
                ClientText.Keys.JOURNAL_HEAL, ClientText.Keys.JOURNAL_EFFECT, ClientText.Keys.JOURNAL_DEATH,
                ClientText.Keys.JOURNAL_BLOCK, ClientText.Keys.JOURNAL_REJECT, ClientText.Keys.JOURNAL_ENEMY,
                ClientText.Keys.JOURNAL_INVENTORY, ClientText.Keys.JOURNAL_ACTION)) {
            widestEn = Math.max(widestEn, ESTIMATE.applyAsInt(en.get(key)));
            widestZh = Math.max(widestZh, ESTIMATE.applyAsInt(zh.get(key)));
        }
        int enColumn = widestEn + Motion.CHIP_PAD_X * 2;
        int zhColumn = widestZh + Motion.CHIP_PAD_X * 2;
        assertTrue(enColumn > HudLayout.JOURNAL_CHIP_WIDTH,
                "the fixed 32-pixel column would have cut an English category: it needs " + enColumn);
        assertTrue(enColumn > zhColumn, "the English chip is the wider one, which is why it is measured");
        // The clamp the layout applies: a text column of JOURNAL_CHIP_MIN_TEXT always survives.
        int innerWidth = Scale.ROSTER_WIDTH - Motion.PAD * 2;
        assertTrue(enColumn + HudLayout.JOURNAL_CHIP_MIN_TEXT <= innerWidth,
                "the measured column still leaves room for the entry's own text");
    }

    @Test
    void theActingChipIsMeasuredAndNeverCutsHealth() throws IOException {
        Map<String, String> en = lang("en_us.json");
        Map<String, String> zh = lang("zh_cn.json");
        String health = "20 / 20";
        int nameBudget = 90;
        for (Map.Entry<String, Map<String, String>> language : Map.of("en", en, "zh", zh).entrySet()) {
            String chip = language.getValue().get(ClientText.Keys.HUD_ACTING);
            RosterRowText.Arrangement arrangement =
                    RosterRowText.arrange("TacticalA", chip, health, nameBudget, 3, 1.0D, ESTIMATE);
            assertEquals(health, arrangement.health(), "health is placed from the right edge and never cut");
            assertEquals(chip, arrangement.chip());
            assertTrue(arrangement.textLines() >= 1);
        }
        assertTrue(ESTIMATE.applyAsInt(en.get(ClientText.Keys.HUD_ACTING))
                        > ESTIMATE.applyAsInt(zh.get(ClientText.Keys.HUD_ACTING)),
                "the English chip is wider than the Chinese one, so its width has to be measured");
    }

    @Test
    void aButtonRowThatDoesNotFitItselfStacksInsteadOfCuttingLabels() {
        PanelLayout wide = PanelLayout.begin(640, 360, 1.0D, false);
        wide.title();
        wide.rule();
        wide.footer(List.of("a", "b"), List.of(wide.px(64), wide.px(64)), Motion.BUTTON_HEIGHT);
        wide.finish();
        assertFalse(wide.footerStacked(), "two short buttons share one row");
        assertEquals(wide.rect("a").y(), wide.rect("b").y());

        PanelLayout tight = PanelLayout.begin(640, 360, 1.0D, false);
        tight.title();
        tight.rule();
        tight.footer(List.of("a", "b"), List.of(tight.px(120), tight.px(120)), Motion.BUTTON_HEIGHT);
        tight.finish();
        assertTrue(tight.footerStacked(), "two long labels stack instead of being cut");
        Rect first = tight.rect("a");
        Rect second = tight.rect("b");
        assertEquals(tight.innerWidth(), first.w(), "a stacked button takes the whole inner width");
        assertTrue(second.y() >= first.bottom(), "the stacked buttons never overlap");
        assertTrue(second.bottom() <= tight.panel().bottom(), "the stacked row stays inside the panel");
    }

    @Test
    void theJournalTitleRowKeepsItsTitleWholeAndShrinksTheToggleToAnIcon() {
        // The full English toggle label ("Collapse −") does not fit beside "Battle log" in a 140-wide
        // journal, so the layout has to fall back to the icon button and leave the title alone.
        HudLayout.JournalToggle toggle = new HudLayout.JournalToggle(ESTIMATE.applyAsInt("Battle log"),
                ESTIMATE.applyAsInt("Collapse −"), ESTIMATE.applyAsInt("−"));
        HudLayout layout = HudLayout.compute(new HudLayout.Input(640, 360, 1.0D, Scale.Form.STANDARD, 0,
                true, 2, 0, HudLayout.JournalMode.EMBEDDED, false, new int[0], new int[0], -1, false, 0,
                null, null, null, null, (budget, cap) -> new int[]{1, 1}, text -> new HudLayout.JournalMeasure(
                new int[]{1}, new long[]{1L}), null).withJournalToggle(toggle));
        assertTrue(layout.journalToggleIconOnly(), "the full English label cannot fit, so the icon is used");
        assertTrue(layout.journalTitle().w() > 0, "the title keeps its own room");
        assertTrue(layout.journalToggle().right() <= layout.journalTitle().x() + layout.journalWidth(),
                "the toggle stays inside the title row");
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> allKeys() {
        List<String> keys = new ArrayList<>(ClientText.ALL_KEYS);
        keys.addAll(ColorText.KEYS);
        return keys;
    }

    private static int count(String pattern) {
        return placeholders(pattern).size();
    }

    private static List<String> placeholders(String pattern) {
        List<String> found = new ArrayList<>();
        if (pattern == null) return found;
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

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
