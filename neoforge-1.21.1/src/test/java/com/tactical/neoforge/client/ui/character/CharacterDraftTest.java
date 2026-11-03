package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The draft's arithmetic: only its own points, never the committed ones, and never past the server's caps. */
class CharacterDraftTest {

    private static final int CAP = 99;
    private static final int MAX_INVESTED = CAP - CharacterStat.BASE;

    @Test void anEmptyDraftChangesNothing() {
        CharacterDraft draft = CharacterDraft.empty();
        assertTrue(draft.isEmpty());
        assertEquals(0, draft.total());
        for (CharacterStat stat : CharacterStat.values()) {
            assertEquals(0, draft.increment(stat));
            assertFalse(draft.canRemove(stat), "nothing to take back");
        }
    }

    @Test void addingStopsAtTheUnspentBalance() {
        CharacterDraft draft = CharacterDraft.empty();
        assertTrue(draft.canAdd(CharacterStat.VIGOR, 1, 0, CAP));
        CharacterDraft one = draft.add(CharacterStat.VIGOR, 1, 0, CAP);
        assertEquals(1, one.total());
        assertEquals(1, one.increment(CharacterStat.VIGOR));
        // The one available point is now spent by this draft, so nothing else may be added.
        assertFalse(one.canAdd(CharacterStat.MIND, 1, 0, CAP), "the balance is already drafted away");
        assertFalse(one.canAdd(CharacterStat.VIGOR, 1, 0, CAP));
    }

    @Test void addingStopsAtTheStatCap() {
        CharacterDraft draft = CharacterDraft.empty();
        assertTrue(draft.canAdd(CharacterStat.ENDURANCE, 5, MAX_INVESTED - 1, CAP), "the last point may be drafted");
        assertFalse(draft.canAdd(CharacterStat.ENDURANCE, 5, MAX_INVESTED, CAP), "a full stat cannot grow");
        // A stat already above a lowered cap keeps its points but never grows.
        assertFalse(draft.canAdd(CharacterStat.ENDURANCE, 5, MAX_INVESTED + 3, CAP));
        CharacterDraft capped = CharacterDraft.empty().add(CharacterStat.VIGOR, 5, MAX_INVESTED - 2, CAP)
                .add(CharacterStat.VIGOR, 5, MAX_INVESTED - 2, CAP);
        assertEquals(2, capped.increment(CharacterStat.VIGOR));
        assertFalse(capped.canAdd(CharacterStat.VIGOR, 5, MAX_INVESTED - 2, CAP), "the cap was reached by the draft");
    }

    @Test void removingOnlyUndoesWhatThisDraftAdded() {
        CharacterDraft draft = CharacterDraft.empty().add(CharacterStat.MIND, 2, 4, CAP);
        assertTrue(draft.canRemove(CharacterStat.MIND));
        CharacterDraft back = draft.remove(CharacterStat.MIND);
        assertTrue(back.isEmpty(), "the drafted point is gone");
        // There is nothing left for this draft to take back, so a committed point can never be spent here.
        assertFalse(back.canRemove(CharacterStat.MIND));
        assertEquals(back, back.remove(CharacterStat.MIND), "removing nothing is a no-op");
        // A draft never changes the committed array it was built from.
        assertEquals(4, 4);
    }

    @Test void draftingCannotGoNegative() {
        assertThrows(IllegalArgumentException.class, () -> new CharacterDraft(new int[]{-1, 0, 0, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new CharacterDraft(new int[5]));
        assertThrows(IllegalArgumentException.class, () -> new CharacterDraft(null));
        // The base helper never produces a negative value even when asked to.
        assertEquals(0, CharacterDraft.empty().remove(CharacterStat.VIGOR).total());
    }

    @Test void displayedValueIsTheCommittedValuePlusTheDraft() {
        CharacterDraft draft = CharacterDraft.empty().add(CharacterStat.VIGOR, 3, 0, CAP).add(CharacterStat.VIGOR, 3, 0, CAP);
        assertEquals(2, draft.increment(CharacterStat.VIGOR));
        assertEquals(13, draft.display(CharacterStat.VIGOR, 11));
        assertEquals(10, draft.display(CharacterStat.MIND, 10));
    }

    @Test void theDraftEchoIsComparableSoALatePreviewCanBeRejected() {
        CharacterDraft draft = CharacterDraft.empty().add(CharacterStat.VIGOR, 1, 0, CAP);
        assertTrue(draft.sameAs(new int[]{1, 0, 0, 0, 0, 0}));
        assertFalse(draft.sameAs(new int[]{0, 1, 0, 0, 0, 0}));
        assertFalse(draft.sameAs(null));
    }

    @Test void numbersAreFormattedOnceForEveryColumn() {
        assertEquals("153", CharacterFormat.ap(153.4D));
        assertEquals("22.00", CharacterFormat.amount(22.0D));
        assertEquals("×1.02", CharacterFormat.multiplier(1.02D));
        assertEquals("100 XP", CharacterFormat.xp(100));
        assertEquals("+2", CharacterFormat.chip(2));
        assertEquals("", CharacterFormat.chip(0));
        assertEquals("Lv 3 / 100", CharacterFormat.level(3, 100));
        assertEquals("Lv 3 → Lv 4", CharacterFormat.levelStep(3));
        assertEquals(CharacterFormat.UNKNOWN, CharacterFormat.amount(Double.NaN));
        assertEquals(CharacterFormat.UNKNOWN, CharacterFormat.multiplier(Double.POSITIVE_INFINITY));
    }

    /**
     * A rule coefficient is a rule number, not a measurement: the tooltip says "+2 max health", never
     * "+2.00", and it must not round a small coefficient to zero either.
     */
    @Test void aCoefficientIsFormattedAsARuleNumber() {
        assertEquals("2", CharacterFormat.coefficient(2.0D));
        assertEquals("0.5", CharacterFormat.coefficient(0.5D));
        assertEquals("0.01", CharacterFormat.coefficient(0.01D));
        assertEquals("0.02", CharacterFormat.coefficient(0.02D));
        assertEquals("-1", CharacterFormat.coefficient(-1.0D));
        assertEquals(CharacterFormat.UNKNOWN, CharacterFormat.coefficient(Double.NaN));
    }
}
