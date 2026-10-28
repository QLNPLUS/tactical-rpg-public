package com.tactical.neoforge.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tactical.core.skill.SkillArea;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillAreasTest {
    private static SkillAreas.Compiled compile(String json) {
        return SkillAreas.compile(JsonParser.parseString(json), Set.of("level", "radius"));
    }

    @Test void dimensionsOffsetsAndYawResolvePerCastWithoutRecompiling() {
        SkillAreas.Compiled compiled = compile("""
            {"type":"line","width":2,"length":"level+1","offset_x":-2,"offset_z":3,"yaw":90}
            """);
        SkillArea shortLine = compiled.resolve(Map.of("level", 1.0));
        SkillArea longLine = compiled.resolve(Map.of("level", 4.0));
        assertTrue(shortLine.contains(-4, 3));
        assertFalse(shortLine.contains(-6, 3));
        assertTrue(longLine.contains(-6, 3));
        assertEquals(-2, shortLine.bounds().maxX(), 1e-9);
    }

    @Test void arbitraryTriangleAndRightTriangleShorthandResolve() {
        SkillArea arbitrary = compile("""
            {"type":"triangle","points":[[-2,-1],[2,-1],[0,"level"]]}
            """).resolve(Map.of("level", 3.0));
        assertTrue(arbitrary.contains(0, 1));
        assertFalse(arbitrary.contains(2, 3));
        assertTrue(compile("{\"type\":\"triangle\",\"size\":4}").resolve(Map.of()).contains(2, 2));
    }

    @Test void compositesTransformAndSubtractPreserveHoles() {
        SkillArea ring = compile("""
            {"type":"difference","base":{"type":"circle","radius":3},
             "subtract":[{"type":"circle","radius":1}],"offset_x":-4}
            """).resolve(Map.of());
        assertFalse(ring.contains(-4, 0));
        assertTrue(ring.contains(-2, 0));
        SkillArea intersection = compile("""
            {"type":"intersection","children":[{"type":"circle","radius":2},
             {"type":"rectangle","width":2,"length":3}]}
            """).resolve(Map.of());
        assertTrue(intersection.contains(0, 1));
        assertFalse(intersection.contains(1.5, 1));
        SkillArea transformed = compile("""
            {"type":"transform","area":{"type":"line","width":0,"length":3},"yaw":90}
            """).resolve(Map.of());
        assertTrue(transformed.contains(-3, 0));
        assertFalse(transformed.contains(0, 3));
    }

    @Test void malformedSchemasUnknownVariablesAndRandomDimensionsFailCompilation() {
        for (String json : new String[] {
                "{}", "{\"type\":\"sphere\"}", "{\"type\":\"circle\"}",
                "{\"type\":\"circle\",\"radius\":true}",
                "{\"type\":\"circle\",\"radius\":\"typo\"}",
                "{\"type\":\"circle\",\"radius\":\"random()\"}",
                "{\"type\":\"triangle\",\"points\":[[0,0],[1,1]]}",
                "{\"type\":\"union\",\"children\":[]}"}) {
            assertThrows(IllegalArgumentException.class, () -> compile(json), json);
        }
    }

    @Test void dynamicBoundsAndCandidateBudgetRejectExcessiveRegions() {
        SkillAreas.Compiled circle = compile("{\"type\":\"circle\",\"radius\":\"radius\"}");
        assertThrows(IllegalArgumentException.class, () -> circle.resolve(Map.of("radius", -1.0)));
        assertThrows(IllegalArgumentException.class, () -> circle.resolve(Map.of("radius", 65.0)));
        assertThrows(IllegalArgumentException.class, () -> circle.resolve(Map.of("radius", 40.0)));
        assertThrows(IllegalArgumentException.class, () -> circle.resolve(Map.of("radius", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> circle.resolve(Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> compile("{\"type\":\"circle\",\"radius\":2,\"offset_x\":64}").resolve(Map.of()));
        assertTrue(compile("{\"type\":\"line\",\"width\":0,\"length\":64}")
            .resolve(Map.of()).contains(0, 64));
    }

    @Test void nodeCountBudgetIncludesCompositeRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "union");
        JsonArray children = new JsonArray();
        root.add("children", children);
        for (int i = 0; i < 99; i++) {
            JsonObject circle = new JsonObject();
            circle.addProperty("type", "circle");
            circle.addProperty("radius", 0);
            children.add(circle);
        }
        assertTrue(SkillAreas.compile(root, Set.of()).resolve(Map.of()).contains(0, 0));
        children.add(children.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> SkillAreas.compile(root, Set.of()));
    }
}
