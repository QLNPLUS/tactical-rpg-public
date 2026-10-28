package com.tactical.neoforge.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SkillDefinitionTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("tactical", "model_fixture");
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void jsonCompilesCostsEffectTurnsDamageAndHighestGrantCapSchema() {
        SkillDefinition definition = SkillDefinition.parse(ID, json("""
            {"max_level":5,"aim":"entity","targets":["enemy"],"ap_cost":"10+level","mp_cost":"7",
             "cooldown":"1","damage":{"mode":"formula","type":"minecraft:magic","amount":"attack_damage*level"},
             "effects":[{"id":"minecraft:slowness","level":"level+1","duration":"level+1","duration_unit":"turns"}],
             "enchantments":[{"id":"tactical:backstab","slots":["main_hand","head"]}]}
            """));
        assertEquals(5, definition.maxLevel());
        assertEquals(SkillDefinition.Aim.ENTITY, definition.aim());
        assertEquals(Set.of(SkillDefinition.Relation.ENEMY), definition.targets());
        assertEquals(13, definition.apCost().evaluate(Map.of("level", 3.0)));
        assertEquals(7, definition.mpCost().evaluate(Map.of()));
        assertEquals(1, definition.cooldown().evaluate(Map.of()));
        assertEquals(18, definition.damage().amount().evaluate(Map.of("level", 3.0, "attack_damage", 6.0)));
        assertEquals(ResourceLocation.withDefaultNamespace("magic"), definition.damage().type());
        assertEquals(SkillDefinition.DurationUnit.TURNS, definition.effects().getFirst().unit());
        assertEquals(4, definition.effects().getFirst().level().evaluate(Map.of("level", 3.0)));
        assertEquals(4, definition.effects().getFirst().duration().evaluate(Map.of("level", 3.0)));
        assertEquals(Set.of(EquipmentSlot.MAINHAND, EquipmentSlot.HEAD), definition.enchantments().getFirst().slots());
    }

    @Test void nativeWeaponModePreservesSourceAndRejectsFormulaTypos() {
        SkillDefinition weapon = SkillDefinition.builder(ID).weaponDamage("1+0.25*level").build();
        assertEquals(SkillDefinition.DamageMode.WEAPON, weapon.damage().mode());
        assertEquals(ResourceLocation.withDefaultNamespace("player_attack"), weapon.damage().type());
        assertEquals(2.25, weapon.damage().amount().evaluate(Map.of("level", 5.0)));
        assertThrows(IllegalArgumentException.class, () -> SkillDefinition.parse(ID, json("""
            {"damage":{"mode":"weapon","type":"minecraft:magic","multiplier":"1"}}
            """)));
        assertThrows(IllegalArgumentException.class,
            () -> SkillDefinition.builder(ID).costs("levle+10", "0").build());
        assertThrows(IllegalArgumentException.class,
            () -> SkillDefinition.builder(ID).damage(ResourceLocation.withDefaultNamespace("magic"), "random()").build());
    }

    @Test void attributeAliasesAreExplicitAndCannotOverrideRuntimeBuiltins() {
        SkillDefinition definition = SkillDefinition.parse(ID, json("""
            {"attributes":{"custom_power":"minecraft:generic.attack_damage",
             "guard":{"attribute":"minecraft:generic.armor","target":true}},"mp_cost":"custom_power+guard"}
            """));
        assertFalse(definition.attributes().get("custom_power").target());
        assertTrue(definition.attributes().get("guard").target());
        assertEquals(9, definition.mpCost().evaluate(Map.of("custom_power", 7.0, "guard", 2.0)));
        assertThrows(IllegalArgumentException.class, () -> SkillDefinition.parse(ID, json("""
            {"attributes":{"level":"minecraft:generic.armor"}}
            """)));
        assertThrows(IllegalArgumentException.class, () -> SkillDefinition.parse(ID, json("""
            {"attributes":{"bad-name":"minecraft:generic.armor"}}
            """)));
    }

    @Test void builderCopiesJsonAndDefinitionCollectionsAreReadOnly() {
        JsonObject input = json("{\"name\":\"original\",\"max_level\":5}");
        var builder = SkillDefinition.builder(ID).json(input);
        input.addProperty("name", "mutated"); input.addProperty("max_level", 9999);
        SkillDefinition definition = builder.build();
        assertEquals("original", definition.name()); assertEquals(5, definition.maxLevel());
        assertThrows(UnsupportedOperationException.class, () -> definition.targets().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.effects().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.attributes().clear());
        assertThrows(IllegalArgumentException.class, () -> SkillDefinition.builder(ID).maxLevel(0).build());
        assertThrows(IllegalArgumentException.class, () -> SkillDefinition.builder(ID).maxLevel(1025).build());
        assertThrows(IllegalArgumentException.class, () -> SkillDefinition.builder(ID).targets().build());
    }

    @Test void dynamicShapeCompilesThroughDefinitionBuilder() {
        SkillDefinition definition = SkillDefinition.builder(ID).aim(SkillDefinition.Aim.GROUND)
            .shape(json("{\"type\":\"line\",\"width\":1,\"length\":\"level+1\",\"offset_z\":-1,\"yaw\":90}"),
                SkillDefinition.Anchor.CASTER).build();
        assertTrue(definition.area()); assertEquals(SkillDefinition.Anchor.CASTER, definition.anchor());
        var area = definition.region().resolve(Map.of("level", 2.0));
        assertTrue(area.contains(-1, -1)); assertFalse(area.contains(0, 3));
    }
}
