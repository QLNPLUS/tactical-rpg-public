package com.tactical.neoforge.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skill's text keeps its literal contract, and only an explicit key is ever translated.
 *
 * <p>The regression this locks: the definition text used to be a plain String that a later batch would
 * have had to translate by guessing, and "a value that looks like a key" must never be looked up. So the
 * literal stays the literal - 128/2048 characters, newlines and Unicode included - the optional
 * {@code name_key}/{@code description_key} adds a real translation identity, and the authored text stays
 * that key's fallback so an unsupported language still reads the original wording.</p>
 */
class SkillTextTest {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("tactical", "text_fixture");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[a-zA-Z%]");

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void aLiteralNameAndDescriptionAreNeverLookedUp() {
        SkillDefinition definition = SkillDefinition.parse(ID, json("""
            {"name":"背刺","description":"从背后发动\\n原版近战，倍率 = 1+0.25*level。"}
            """));
        assertNull(definition.nameKey());
        assertNull(definition.descriptionKey());
        assertEquals("背刺", plain(definition.displayName()));
        assertEquals("从背后发动\n原版近战，倍率 = 1+0.25*level。", plain(definition.displayDescription()),
                "newlines and Unicode inside the authored description survive untouched");
    }

    @Test
    void aValueThatLooksLikeAKeyStaysLiteralText() {
        SkillDefinition definition = SkillDefinition.parse(ID, json("""
            {"name":"tactical.skill.backstab.name","description":"entity.minecraft.zombie"}
            """));
        assertEquals("tactical.skill.backstab.name", plain(definition.displayName()),
                "a key-shaped literal is not a translation identity");
        assertEquals("entity.minecraft.zombie", plain(definition.displayDescription()));
    }

    @Test
    void anExplicitKeyKeepsTheAuthoredTextAsItsFallback() {
        SkillDefinition definition = SkillDefinition.parse(ID, json("""
            {"name":"背刺","description":"说明","name_key":"tactical.skill.backstab.name",
             "description_key":"tactical.skill.backstab.description"}
            """));
        TranslatableContents name = translatable(definition.displayName());
        assertEquals("tactical.skill.backstab.name", name.getKey());
        assertEquals("背刺", name.getFallback(), "the original wording is the fallback of the key");
        TranslatableContents description = translatable(definition.displayDescription());
        assertEquals("tactical.skill.backstab.description", description.getKey());
        assertEquals("说明", description.getFallback());
    }

    @Test
    void theBuilderOffersTheSameExplicitIdentity() {
        SkillDefinition definition = SkillDefinition.builder(ID)
                .name("背刺").nameKey("tactical.skill.backstab.name")
                .description("说明").descriptionKey("tactical.skill.backstab.description")
                .build();
        assertEquals("tactical.skill.backstab.name", translatable(definition.displayName()).getKey());
        assertEquals("tactical.skill.backstab.description", translatable(definition.displayDescription()).getKey());
    }

    @Test
    void theLiteralLengthContractIsUnchanged() {
        StringBuilder name = new StringBuilder();
        while (name.length() < 128) name.append('a');
        StringBuilder description = new StringBuilder();
        while (description.length() < 2048) description.append('描');
        SkillDefinition accepted = SkillDefinition.builder(ID).name(name.toString())
                .description(description.toString()).build();
        assertEquals(128, accepted.name().length());
        assertEquals(2048, accepted.description().length());
        assertEquals(description.toString(), plain(accepted.displayDescription()),
                "a maximum-length description is carried whole, never truncated to a shorter frame");
        assertThrows(IllegalArgumentException.class,
                () -> SkillDefinition.builder(ID).name(name + "b").build());
        assertThrows(IllegalArgumentException.class,
                () -> SkillDefinition.builder(ID).description(description + "述").build());
        assertThrows(IllegalArgumentException.class,
                () -> SkillDefinition.builder(ID).nameKey(" ").build(),
                "an explicit key cannot be blank");
    }

    @Test
    void theBuiltinSkillDeclaresItsTranslationIdentity() {
        SkillDefinition backstab = SkillRegistry.get(ResourceLocation.fromNamespaceAndPath("tactical", "backstab"));
        assertTrue(backstab != null, "the builtin skill is registered");
        assertEquals("tactical.skill.backstab.name", backstab.nameKey());
        assertEquals("tactical.skill.backstab.description", backstab.descriptionKey());
        assertEquals("背刺", translatable(backstab.displayName()).getFallback(),
                "the Chinese authored line stays the fallback in every language");
    }

    @Test
    void availabilityReadinessIsTheFlagAndNotTheText() {
        SkillAvailability ready = new SkillAvailability(ID, 1, true, "", 5, 2.0D, 0);
        assertEquals("", plain(ready.reason()), "the compatibility form is a literal");
        assertTrue(ready.ready());
        SkillAvailability blocked = new SkillAvailability(ID, 1, false, "冷却中", 5, 2.0D, 2);
        assertTrue(!blocked.ready() && "冷却中".equals(plain(blocked.reason())));
        SkillAvailability keyed = new SkillAvailability(ID, 1, false,
                SkillText.translatable(SkillText.KEY_REASON_COOLDOWN, Integer.toString(2)), 5, 2.0D, 2);
        assertEquals(SkillText.KEY_REASON_COOLDOWN, translatable(keyed.reason()).getKey(),
                "a structured reason stays a key with its argument");
    }

    @Test
    void anEmptyComponentIsTheOnlyAllowedMarker() {
        assertTrue(SkillText.isEmpty(SkillText.none()));
        assertTrue(!SkillText.isEmpty(SkillText.translatable(SkillText.KEY_REJECT_OUT_OF_RANGE)),
                "a key is never mistaken for an allowed outcome");
        assertEquals(SkillText.KEY_REJECT_OUT_OF_RANGE,
                ((TranslatableContents) SkillText.translatable(SkillText.KEY_REJECT_OUT_OF_RANGE).getContents()).getKey());
        assertTrue(SkillText.isKey(SkillText.translatable(SkillText.KEY_REJECT_OUT_OF_RANGE), SkillText.KEY_REJECT_OUT_OF_RANGE));
        assertTrue(!SkillText.isKey(Component.literal(SkillText.KEY_REJECT_OUT_OF_RANGE), SkillText.KEY_REJECT_OUT_OF_RANGE),
                "a literal that equals a key is still a literal");
    }

    // ---- both shipped languages ---------------------------------------------------------

    @Test
    void everySkillKeyExistsInBothLanguagesWithTheSameArguments() {
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        List<String> keys = declaredKeys();
        assertTrue(keys.size() > 40, "the skill wording is read from its own constants");
        for (String key : keys) {
            assertTrue(zh.has(key), "zh_cn is missing " + key);
            assertTrue(en.has(key), "en_us is missing " + key);
            List<String> zhArgs = placeholders(zh.get(key).getAsString());
            List<String> enArgs = placeholders(en.get(key).getAsString());
            assertEquals(zhArgs, enArgs, key + " must substitute the same arguments in the same order");
        }
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(zh.keySet()), "both files carry the same key set");
    }

    @Test
    void everySkillTemplateOnlyUsesTheSupportedPlaceholder() {
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject table = lang(language);
            for (String key : declaredKeys()) {
                Matcher matcher = PLACEHOLDER.matcher(table.get(key).getAsString());
                while (matcher.find()) {
                    String token = matcher.group();
                    assertTrue(token.endsWith("s") || "%".equals(token),
                            language + " " + key + " uses an unsupported placeholder " + token);
                }
            }
        }
    }

    private static List<String> placeholders(String template) {
        List<String> args = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if ("%".equals(matcher.group())) continue;
            args.add(matcher.group());
        }
        return args;
    }

    /** Every {@code KEY_*} constant of {@link SkillText}: the producer is the source of the key list. */
    private static List<String> declaredKeys() {
        List<String> keys = new ArrayList<>();
        for (Field field : SkillText.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            if (!field.getName().startsWith("KEY_")) continue;
            try {
                keys.add((String) field.get(null));
            } catch (IllegalAccessException inaccessible) {
                throw new AssertionError(inaccessible);
            }
        }
        return keys;
    }

    private static JsonObject lang(String language) {
        String path = "assets/tactical/lang/" + language + ".json";
        try (InputStream stream = SkillTextTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("missing " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String plain(Component component) {
        PlainTextContents contents = assertInstanceOf(PlainTextContents.class, component.getContents(),
                "expected literal text, got " + component.getContents().getClass().getName());
        return contents.text();
    }

    private static TranslatableContents translatable(Component component) {
        return assertInstanceOf(TranslatableContents.class, component.getContents(),
                "expected a translation key, got " + component.getContents().getClass().getName());
    }
}
