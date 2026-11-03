package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the six derived cells explain when the pointer rests on them.
 *
 * <p>The defect this pins: the derived cells reused the stat rows' per-point sentence, so hovering
 * "Max health" claimed "+2 max health <em>per point</em>" - a rate that belongs to the VIT row above - and
 * nothing at all about what the published maximum is. A derived value is a finished number, so its tooltip
 * explains the metric itself. Both line groups are keys, and the key mapping is the one the page resolves, so
 * this check fails if the screen goes back to the effect sentence.</p>
 */
class CharacterDerivedTooltipTest {

    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");

    /** Wording a per-point sentence uses, which a metric explanation may not. */
    private static final String[] PER_POINT_WORDS = {"per point", "每点", "invested", "draft", "已投", "待投"};

    @Test
    void aDerivedCellNeverReusesTheStatRowsPerPointSentence() {
        for (CharacterStat stat : CharacterStat.values()) {
            CharacterScreen.Keys.Tip tip = CharacterScreen.Keys.derivedTip(stat);
            assertEquals(CharacterScreen.Keys.derivedName(stat), tip.primary(),
                    stat + " is introduced by its own metric name");
            assertEquals(CharacterScreen.Keys.derivedHelp(stat), tip.secondary(),
                    stat + " explains itself, not the stat row above it");
            assertNotEquals(CharacterScreen.Keys.statEffect(stat), tip.secondary(),
                    stat + " must not borrow the stat row's per-point effect key");
            assertNotEquals(CharacterScreen.Keys.stat(stat), tip.primary(),
                    stat + " keeps its derived name, not the stat's full name");
        }
    }

    @Test
    void noDerivedExplanationCarriesACoefficientOrACounter() throws IOException {
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        for (CharacterStat stat : CharacterStat.values()) {
            String key = CharacterScreen.Keys.derivedHelp(stat);
            for (Map.Entry<String, Map<String, String>> language : Map.of("en_us", en, "zh_cn", zh).entrySet()) {
                String text = language.getValue().get(key);
                String where = language.getKey() + " " + key;
                assertNotNull(text, where + " is missing");
                assertFalse(text.isBlank(), where + " is blank");
                assertTrue(placeholders(text).isEmpty(),
                        where + " takes no coefficient argument, got " + text);
                for (String banned : PER_POINT_WORDS) {
                    assertFalse(text.toLowerCase(Locale.ROOT).contains(banned.toLowerCase(Locale.ROOT)),
                            where + " must not talk about " + banned + ", got " + text);
                }
                // It has to be a sentence, not the bare metric name the cell already draws.
                assertNotEquals(language.getValue().get(CharacterScreen.Keys.derivedName(stat)), text,
                        where + " says more than the cell's own label");
            }
            assertNotEquals(en.get(key), zh.get(key), key + " is really translated");
        }
    }

    /**
     * The wording names what the server really reads, so the page cannot promise more than the rules implement.
     *
     * <p>Skill power scales the damage of this mod's own skills and nothing else - not plain attacks, not costs,
     * not cooldowns, not effect levels - so its explanation may not read like a general "all skill effects" or a
     * healing bonus. Max AP is the finished action-point figure the battle rounds, and the turn charge is a
     * multiplier, which is why those two say so.</p>
     */
    @Test
    void theWordingMatchesWhatTheRuleActuallyApplies() throws IOException {
        Map<String, String> zh = lang("zh_cn.json");
        Map<String, String> en = lang("en_us.json");
        String powerEn = en.get(CharacterScreen.Keys.derivedHelp(CharacterStat.INTELLIGENCE));
        String powerZh = zh.get(CharacterScreen.Keys.derivedHelp(CharacterStat.INTELLIGENCE));
        assertTrue(powerEn.contains("damage") && powerEn.contains("skill"), "English names skill damage: " + powerEn);
        assertTrue(powerZh.contains("技能") && powerZh.contains("伤害"), "Chinese names skill damage: " + powerZh);
        assertFalse(powerEn.toLowerCase(Locale.ROOT).contains("heal"), "skill power is not a healing bonus");
        assertFalse(powerZh.contains("治疗"), "skill power is not a healing bonus");

        assertTrue(en.get(CharacterScreen.Keys.derivedHelp(CharacterStat.DEXTERITY)).contains("Multiplier"),
                "the turn charge is a multiplier");
        assertTrue(zh.get(CharacterScreen.Keys.derivedHelp(CharacterStat.DEXTERITY)).contains("倍率"),
                "the turn charge is a multiplier");
        assertTrue(en.get(CharacterScreen.Keys.derivedHelp(CharacterStat.ENDURANCE)).contains("action points"),
                "max AP is the finished action-point figure");
        assertTrue(zh.get(CharacterScreen.Keys.derivedHelp(CharacterStat.ENDURANCE)).contains("行动点"),
                "max AP is the finished action-point figure");
    }

    private static List<String> placeholders(String pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern == null ? "" : pattern);
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
