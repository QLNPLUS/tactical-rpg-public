package com.tactical.neoforge.battle;

import com.tactical.core.combat.PositionalDamage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The damage breakdown row is structure with fixed-format numbers, not an assembled sentence.
 *
 * <p>The row now names only the modifiers that really changed something: a factor whose two-decimal form is
 * {@code 1.00} says nothing about this hit and is left out, while a non-neutral factor and a zero multiplier stay.
 * Each modifier is its own translatable fragment with its number as an argument formatted on the argument side
 * ({@code Locale.ROOT}), so a client's language can translate the wording without moving a digit, and the
 * separators only exist between fragments that are really there.</p>
 */
class PositionalSummaryTest {

    private static Component summary(PositionalDamage.Sector sector, double direction, double height,
                                     double skill, double defense) {
        return PositionalCombat.summary(
                new TacticalDamageMultipliers.Breakdown(sector, direction, height, skill), defense);
    }

    @Test
    void theRowIsATemplateWithTheSectorAsItsOnlyArgument() {
        Component row = summary(PositionalDamage.Sector.FRONT, 1.2D, 1.05D, 1.0D, 0.5D);
        TranslatableContents contents = contents(row);
        assertEquals(BattleText.KEY_DAMAGE_POSITION, contents.getKey());
        Object[] args = contents.getArgs();
        assertEquals(1, args.length, "the sector is the template's own argument, the modifiers follow as fragments");
        assertEquals(BattleText.KEY_SECTOR_FRONT, contents((Component) args[0]).getKey());
    }

    @Test
    void aNeutralModifierIsLeftOutAndTheSeparatorsOnlyJoinWhatIsShown() {
        Component row = summary(PositionalDamage.Sector.SIDE, 1.2D, 1.0D, 1.0D, 1.0D);
        List<Component> fragments = factors(row);
        assertEquals(1, fragments.size(), "only the direction really changed, got " + fragments.size());
        assertEquals(BattleText.KEY_DAMAGE_FACTOR_DIRECTION, contents(fragments.getFirst()).getKey());
        assertEquals("1.20", contents(fragments.getFirst()).getArgs()[0]);

        // The separators live in the siblings, never in a template, and there is exactly one per fragment.
        List<Component> siblings = row.getSiblings();
        assertEquals(2, siblings.size(), "one separator plus one fragment, got " + siblings.size());
        assertEquals(" · ", plain(siblings.get(0)), "the row starts with a separator, not an empty slot");
        assertEquals(BattleText.KEY_DAMAGE_FACTOR_DIRECTION, contents(siblings.get(1)).getKey(),
                "and ends with the modifier itself, never with a dangling separator");
    }

    @Test
    void everyNonNeutralModifierKeepsItsOwnFragmentInOrder() {
        List<Component> fragments = factors(summary(PositionalDamage.Sector.REAR, 1.5D, 1.25D, 1.0D, 0.5D));
        String[] keys = {
                BattleText.KEY_DAMAGE_FACTOR_DIRECTION,
                BattleText.KEY_DAMAGE_FACTOR_HEIGHT,
                BattleText.KEY_DAMAGE_FACTOR_DEFENSE,
        };
        String[] values = {"1.50", "1.25", "0.50"};
        assertEquals(keys.length, fragments.size(), "the neutral skill factor is the only one dropped");
        for (int index = 0; index < keys.length; index++) {
            TranslatableContents fragment = contents(fragments.get(index));
            assertEquals(keys[index], fragment.getKey(), "modifier " + index + " keeps its own wording");
            assertEquals(values[index], fragment.getArgs()[0], "and its own number");
        }
    }

    @Test
    void anAllNeutralHitWritesNoRowAtAll() {
        assertNull(summary(PositionalDamage.Sector.FRONT, 1.0D, 1.0D, 1.0D, 1.0D),
                "nothing changed, so there is no breakdown to show and no empty header row");
        // Any single non-neutral factor still earns a row.
        assertNotNull(summary(PositionalDamage.Sector.FRONT, 1.0D, 1.0D, 1.0D, 0.5D),
                "a raised guard is worth a row");
    }

    @Test
    void aZeroMultiplierIsNeverTreatedAsNeutral() {
        List<Component> fragments = factors(summary(PositionalDamage.Sector.UNKNOWN, 0.0D, 1.0D, 1.0D, 1.0D));
        assertEquals(1, fragments.size(), "a hit reduced to nothing must still say so");
        assertEquals("0.00", contents(fragments.getFirst()).getArgs()[0]);
    }

    @Test
    void everySectorHasItsOwnKey() {
        assertEquals(BattleText.KEY_SECTOR_SIDE, sectorKey(PositionalDamage.Sector.SIDE));
        assertEquals(BattleText.KEY_SECTOR_REAR, sectorKey(PositionalDamage.Sector.REAR));
        assertEquals(BattleText.KEY_SECTOR_UNKNOWN, sectorKey(PositionalDamage.Sector.UNKNOWN));
    }

    @Test
    void theFactorsAreFormattingStableTextAndNotDoubles() {
        // A double argument would be formatted by the client's own locale, which is exactly what the
        // argument-side formatting exists to prevent. The values used here are exactly representable in
        // binary, so rounding is decided by the format and not by the floating point approximation.
        List<Component> fragments = factors(summary(PositionalDamage.Sector.FRONT, 1.125D, 0.875D, 1.0D, 1.0D));
        assertEquals(2, fragments.size());
        for (Component fragment : fragments) {
            Object argument = contents(fragment).getArgs()[0];
            assertTrue(argument instanceof String, "every modifier travels as pre-formatted text");
            assertFalse(((String) argument).contains(","), "no locale separator may appear");
        }
        assertEquals("1.13", contents(fragments.get(0)).getArgs()[0]);
        assertEquals("0.88", contents(fragments.get(1)).getArgs()[0]);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The modifier fragments of one row: the odd siblings, i.e. everything between two separators. */
    private static List<Component> factors(Component row) {
        List<Component> fragments = new ArrayList<>();
        List<Component> siblings = row.getSiblings();
        for (int index = 0; index < siblings.size(); index++) {
            if (index % 2 == 1) fragments.add(siblings.get(index));
            else assertEquals(" · ", plain(siblings.get(index)), "a separator stands before every modifier");
        }
        return fragments;
    }

    private static String sectorKey(PositionalDamage.Sector sector) {
        return contents((Component) contents(summary(sector, 1.2D, 1D, 1D, 1D)).getArgs()[0]).getKey();
    }

    private static String plain(Component component) {
        if (!(component.getContents() instanceof PlainTextContents contents)) {
            throw new AssertionError("expected plain text, found " + component.getContents());
        }
        return contents.text();
    }

    private static TranslatableContents contents(Component component) {
        if (!(component.getContents() instanceof TranslatableContents contents)) {
            throw new AssertionError("expected a translatable component, found " + component.getContents());
        }
        return contents;
    }
}
