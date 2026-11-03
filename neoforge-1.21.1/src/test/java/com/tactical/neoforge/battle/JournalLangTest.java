package com.tactical.neoforge.battle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal's own wording must exist in both shipped languages, with the same arguments.
 *
 * <p>A translation key that exists in only one file silently degrades to the key itself for the other
 * language, and a placeholder that exists in one file but not the other silently drops a value. Both are
 * invisible in the source and only show up as broken rows in a client, so they are checked here against
 * the real resource files.</p>
 */
class JournalLangTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");

    private static final List<String> JOURNAL_KEYS = List.of(
            BattleJournal.KEY_START, BattleJournal.KEY_FINISH, BattleJournal.KEY_DAMAGE,
            BattleJournal.KEY_BLOCKED, BattleJournal.KEY_HEAL, BattleJournal.KEY_DEATH,
            BattleJournal.KEY_EFFECT_GAINED, BattleJournal.KEY_EFFECT_UPDATED,
            BattleJournal.KEY_EFFECT_REMOVED, BattleJournal.KEY_EFFECT_EXPIRED,
            BattleJournal.KEY_DURATION_INFINITE, BattleJournal.KEY_DURATION_SECONDS,
            BattleJournal.KEY_SOURCE_MAGIC, BattleJournal.KEY_SOURCE_WITHER, BattleJournal.KEY_SOURCE_FALL,
            BattleJournal.KEY_SOURCE_FIRE, BattleJournal.KEY_SOURCE_DROWN, BattleJournal.KEY_SOURCE_STARVE,
            BattleJournal.KEY_SOURCE_OTHER, BattleJournal.KEY_TOO_LONG);

    @Test
    void bothLanguagesCarryTheSameKeys() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(zh.keySet()),
                "zh_cn and en_us must carry the same key set");
    }

    @Test
    void everyJournalKeyExistsInBothLanguages() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        for (String key : JOURNAL_KEYS) {
            assertNotNull(zh.get(key), "zh_cn is missing " + key);
            assertNotNull(en.get(key), "en_us is missing " + key);
            assertTrue(!en.get(key).getAsString().isBlank(), "en_us has a blank value for " + key);
            assertTrue(!zh.get(key).getAsString().isBlank(), "zh_cn has a blank value for " + key);
        }
    }

    @Test
    void everyJournalKeyUsesTheSamePlaceholdersInBothLanguages() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        for (String key : JOURNAL_KEYS) {
            assertEquals(placeholders(en.get(key).getAsString()), placeholders(zh.get(key).getAsString()),
                    key + " must take the same arguments in both languages");
        }
    }

    @Test
    void theTemplatesThatTakeArgumentsKeepTheirArgumentCount() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        assertEquals(5, placeholders(en.get(BattleJournal.KEY_DAMAGE).getAsString()).size());
        assertEquals(5, placeholders(zh.get(BattleJournal.KEY_DAMAGE).getAsString()).size());
        assertEquals(4, placeholders(en.get(BattleJournal.KEY_EFFECT_GAINED).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleJournal.KEY_EFFECT_REMOVED).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleJournal.KEY_BLOCKED).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleJournal.KEY_HEAL).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleJournal.KEY_FINISH).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleJournal.KEY_SOURCE_OTHER).getAsString()).size());
    }

    @Test
    void theOlderKeysAreStillThere() {
        // This step only adds keys: nothing that was already translated may disappear from either file.
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        for (String key : List.of("attribute.tactical.max_mp", "attribute.tactical.mp_recovery",
                "enchantment.tactical.backstab", "key.categories.tactical", "key.tactical.menu",
                "key.tactical.end_turn", "key.tactical.flee", "attribute.tactical.ap_recovery",
                "attribute.tactical.max_ap", "attribute.tactical.turn_charge_speed",
                "tactical.ui.gear.theme", "tactical.ui.gear.range", "tactical.ui.gear.reset_sizes")) {
            assertNotNull(zh.get(key), "zh_cn lost " + key);
            assertNotNull(en.get(key), "en_us lost " + key);
        }
    }

    private static List<String> placeholders(String pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) found.add(matcher.group());
        found.sort(null);
        return found;
    }

    private static JsonObject load(String language) {
        String path = "/assets/tactical/lang/" + language + ".json";
        try (InputStream input = JournalLangTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing language resource " + path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new AssertionError("unreadable language resource " + path, failure);
        }
    }
}
