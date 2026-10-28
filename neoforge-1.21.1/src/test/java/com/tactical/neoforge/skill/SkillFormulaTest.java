package com.tactical.neoforge.skill;

import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillFormulaTest {
    @Test void arithmeticClampingAndLazyTernaryUseExplicitInputs() {
        SkillFormula.Compiled formula = SkillFormula.compile(
            "level > 0 ? clamp(base * (1 + 0.25 * level), 0, 100) : 0", Set.of("base", "level"));
        assertEquals(17.5, formula.evaluate(Map.of("base", 10.0, "level", 3.0)));
        assertEquals(100, formula.evaluate(Map.of("base", 100.0, "level", 5.0)));
        assertEquals(0, formula.evaluate(Map.of("base", 100.0, "level", 0.0)));
        assertEquals(7, SkillFormula.compile("0 ? 1 / 0 : 7", Set.of()).evaluate(Map.of()));
        assertEquals(Set.of("base", "level"), formula.variables());
    }

    @Test void variablesAreAnAllowlistRatherThanAnImplicitDeclaration() {
        assertThrows(IllegalArgumentException.class,
            () -> SkillFormula.compile("base + typo", Set.of("base")));
        SkillFormula.Compiled constant = SkillFormula.compile("2 + 3", Set.of("unused"));
        assertEquals(Set.of(), constant.variables());
        assertEquals(5, constant.evaluate(Map.of()));
    }

    @Test void malformedAndUnseededRandomFormulasFailDuringCompilation() {
        for (String source : new String[] {"", "base +", "unknown(1)", "clamp(1,2)", "random()", "random ( ) + 1"}) {
            assertThrows(IllegalArgumentException.class,
                () -> SkillFormula.compile(source, Set.of("base")), source);
        }
        assertThrows(IllegalArgumentException.class,
            () -> SkillFormula.compile("(".repeat(65) + "1" + ")".repeat(65), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFormula.compile("1".repeat(SkillFormula.MAX_SOURCE_LENGTH + 1), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFormula.compile("-".repeat(300) + "1", Set.of()));
    }

    @Test void missingNonFiniteAndNonFiniteResultsFailEvaluation() {
        SkillFormula.Compiled formula = SkillFormula.compile("base / level", Set.of("base", "level"));
        assertThrows(IllegalArgumentException.class, () -> formula.evaluate(Map.of("base", 1.0)));
        assertThrows(IllegalArgumentException.class,
            () -> formula.evaluate(Map.of("base", Double.NaN, "level", 1.0)));
        assertThrows(IllegalArgumentException.class,
            () -> formula.evaluate(Map.of("base", 1.0, "level", 0.0)));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFormula.compile("sqrt(base)", Set.of("base")).evaluate(Map.of("base", -1.0)));
        assertThrows(IllegalArgumentException.class,
            () -> SkillFormula.compile("clamp(base,level,0)", Set.of("base", "level"))
                .evaluate(Map.of("base", 1.0, "level", 2.0)));
    }

    @Test void cachedFormulaDoesNotLeakBindingsAcrossCallsOrConcurrentCasts() {
        SkillFormula.Compiled formula = SkillFormula.compile("base * 2 + level", Set.of("base", "level"));
        assertEquals(5, formula.evaluate(Map.of("base", 2.0, "level", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> formula.evaluate(Map.of("base", 2.0)));
        IntStream.range(0, 1000).parallel().forEach(i ->
            assertEquals(i * 2.0 + 3, formula.evaluate(Map.of("base", (double) i, "level", 3.0))));
        assertEquals(1, formula.evaluate(Map.of("base", 0.0, "level", 1.0)));
    }
}
