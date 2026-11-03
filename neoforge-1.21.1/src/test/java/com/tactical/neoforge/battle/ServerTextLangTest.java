package com.tactical.neoforge.battle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * The server's own wording (journal producers, chat, command feedback, damage breakdown, item refusal)
 * must exist in both shipped languages, with the same arguments and in the same order.
 *
 * <p>The keys are read from {@link BattleText}'s own constants, so a key that is added to the producer but
 * never translated fails here instead of degenerating into the bare key on a client. Argument order is
 * pinned as well: a translatable substitutes the n-th argument into the n-th {@code %s}, so a template that
 * reorders its placeholders in one language would put a target name where a weapon belongs.</p>
 */
class ServerTextLangTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");

    @Test
    void bothLanguagesCarryTheSameKeys() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(zh.keySet()),
                "zh_cn and en_us must carry the same key set");
    }

    @Test
    void everyServerTextKeyExistsInBothLanguages() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        List<String> keys = serverTextKeys();
        assertTrue(keys.size() >= 120, "the key list must cover the migrated producers, found " + keys.size());
        for (String key : keys) {
            assertNotNull(zh.get(key), "zh_cn is missing " + key);
            assertNotNull(en.get(key), "en_us is missing " + key);
            assertTrue(!zh.get(key).getAsString().isBlank(), "zh_cn has a blank value for " + key);
            assertTrue(!en.get(key).getAsString().isBlank(), "en_us has a blank value for " + key);
        }
    }

    @Test
    void everyServerTextKeyUsesTheSamePlaceholdersInBothLanguages() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        for (String key : serverTextKeys()) {
            assertEquals(placeholders(en.get(key).getAsString()), placeholders(zh.get(key).getAsString()),
                    key + " must take the same arguments, in the same order, in both languages");
        }
    }

    @Test
    void noServerTextTemplateCarriesAFormatConversion() {
        // Numbers are formatted on the argument side (Locale.ROOT), so a template must never carry a
        // printf conversion such as %.2f: that would make the rendered digits locale dependent.
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        Pattern notAPlainPlaceholder = Pattern.compile("(?<!%)%(?!s|%)");
        for (String key : serverTextKeys()) {
            for (JsonObject table : List.of(zh, en)) {
                String template = table.get(key).getAsString();
                assertTrue(!notAPlainPlaceholder.matcher(template).find(),
                        key + " must use plain %s placeholders only, found '" + template + "'");
            }
        }
    }

    @Test
    void theArgumentCountsOfTheMigratedTemplatesArePinned() {
        JsonObject en = load("en_us");
        JsonObject zh = load("zh_cn");
        // Encounter rows.
        assertEquals(1, placeholders(en.get(BattleText.KEY_BATTLE_STARTED).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleText.KEY_BATTLE_INVITE).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleText.KEY_BATTLE_LEAVE).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_BATTLE_HANDOVER).getAsString()).size());
        // Movement and attacks.
        assertEquals(2, placeholders(en.get(BattleText.KEY_MOVE_OFFER).getAsString()).size());
        assertEquals(4, placeholders(en.get(BattleText.KEY_MOVE_WALK).getAsString()).size());
        assertEquals(4, placeholders(zh.get(BattleText.KEY_MOVE_DROP).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleText.KEY_ATTACK_MELEE).getAsString()).size());
        assertEquals(3, placeholders(en.get(BattleText.KEY_ATTACK_RANGED).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleText.KEY_REJECT_OUT_OF_RANGE).getAsString()).size());
        // Defence, items, damage breakdown, commands.
        assertEquals(3, placeholders(en.get(BattleText.KEY_BLOCK).getAsString()).size());
        assertEquals(3, placeholders(en.get(BattleText.KEY_ITEM_ON_TARGET).getAsString()).size());
        assertEquals(2, placeholders(en.get(BattleText.KEY_ITEM_SELF).getAsString()).size());
        // The damage breakdown: one template for the header, one fragment per modifier, so a neutral factor
        // can be left out without leaving a dangling separator.
        assertEquals(1, placeholders(en.get(BattleText.KEY_DAMAGE_POSITION).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_DAMAGE_FACTOR_DIRECTION).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_DAMAGE_FACTOR_HEIGHT).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_DAMAGE_FACTOR_SKILL).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_DAMAGE_FACTOR_DEFENSE).getAsString()).size());
        assertEquals(1, placeholders(zh.get(BattleText.KEY_DAMAGE_POSITION).getAsString()).size());
        assertEquals(1, placeholders(zh.get(BattleText.KEY_DAMAGE_FACTOR_DEFENSE).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_CHAT_INVITE).getAsString()).size());
        assertEquals(1, placeholders(en.get(BattleText.KEY_CHAT_MULTIPLE_INVITES_JOIN).getAsString()).size());
        assertEquals(3, placeholders(en.get(BattleText.KEY_COMMAND_AP_RECEIPT).getAsString()).size());
        assertEquals(3, placeholders(en.get(BattleText.KEY_COMMAND_STATUS_SINGLE).getAsString()).size());
        // The percent sign of the flee report is a literal %% and not an argument.
        assertEquals(1, placeholders(en.get(BattleText.KEY_INFO_FLEE_FAILED).getAsString()).size());
    }

    @Test
    void theCommandReceiptKeepsItsTechnicalFormat() {
        // Server-side evidence parses "<name> AP: <before> -> <after>"; translating the wording of the
        // command must not change that format in either language.
        String expected = "%s AP: %s -> %s";
        assertEquals(expected, load("en_us").get(BattleText.KEY_COMMAND_AP_RECEIPT).getAsString());
        assertEquals(expected, load("zh_cn").get(BattleText.KEY_COMMAND_AP_RECEIPT).getAsString());
    }

    @Test
    void everyCodeEnumHasOneKeyPerValue() {
        // A missing mapping would silently render an empty chat line for that code.
        assertEquals(com.tactical.neoforge.battle.multi.BattleEligibility.Denial.values().length - 1,
                count("KEY_DENIAL_"), "every denial except NONE needs its own key");
        assertEquals(BattleText.Exit.values().length, count("KEY_EXIT_"), "every exit code needs its own key");
        assertEquals(BattleText.End.values().length, count("KEY_END_"), "every end code needs its own key");
        assertEquals(ItemTargetRule.Denial.values().length - 1, count("KEY_ITEM_REFUSAL_"),
                "every item refusal except NONE needs its own key");
    }

    @Test
    void theEarlierJournalKeysAreStillThere() {
        JsonObject zh = load("zh_cn");
        JsonObject en = load("en_us");
        for (String key : List.of(BattleJournal.KEY_START, BattleJournal.KEY_FINISH, BattleJournal.KEY_DAMAGE,
                BattleJournal.KEY_BLOCKED, BattleJournal.KEY_HEAL, BattleJournal.KEY_DEATH,
                BattleJournal.KEY_EFFECT_GAINED, BattleJournal.KEY_EFFECT_UPDATED,
                BattleJournal.KEY_EFFECT_REMOVED, BattleJournal.KEY_EFFECT_EXPIRED,
                BattleJournal.KEY_DURATION_INFINITE, BattleJournal.KEY_DURATION_SECONDS,
                BattleJournal.KEY_SOURCE_MAGIC, BattleJournal.KEY_SOURCE_WITHER, BattleJournal.KEY_SOURCE_FALL,
                BattleJournal.KEY_SOURCE_FIRE, BattleJournal.KEY_SOURCE_DROWN, BattleJournal.KEY_SOURCE_STARVE,
                BattleJournal.KEY_SOURCE_OTHER, BattleJournal.KEY_TOO_LONG,
                "attribute.tactical.max_mp", "enchantment.tactical.backstab", "key.tactical.menu",
                "tactical.ui.gear.theme", "tactical.ui.color.save")) {
            assertNotNull(zh.get(key), "zh_cn lost " + key);
            assertNotNull(en.get(key), "en_us lost " + key);
        }
    }

    private static int count(String constantPrefix) {
        int found = 0;
        for (Field field : BattleText.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            if (field.getName().startsWith(constantPrefix)) found++;
        }
        return found;
    }

    private static List<String> serverTextKeys() {
        List<String> keys = new ArrayList<>();
        for (Field field : BattleText.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            if (!field.getName().startsWith("KEY_")) continue;
            try {
                keys.add((String) field.get(null));
            } catch (IllegalAccessException failure) {
                throw new AssertionError("cannot read " + field.getName(), failure);
            }
        }
        keys.sort(null);
        return keys;
    }

    private static List<String> placeholders(String pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    private static JsonObject load(String language) {
        String path = "/assets/tactical/lang/" + language + ".json";
        try (InputStream input = ServerTextLangTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing language resource " + path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new AssertionError("unreadable language resource " + path, failure);
        }
    }
}
