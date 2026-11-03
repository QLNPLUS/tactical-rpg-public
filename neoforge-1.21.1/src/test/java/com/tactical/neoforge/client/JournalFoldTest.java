package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The journal has two collapse sources: the ordinary saved preference and the tight/narrow form's
 * fold. These are pure-logic checks of that split. The geometry the split feeds - in particular that
 * expanding never moves the roster - is checked in {@code HudLayoutTest}, and the real toggle click
 * is verified with real input in the game.
 */
class JournalFoldTest {

    @Test void theStandardFormIsDecidedOnlyByTheOrdinaryPreference() {
        var fold = new JournalFold();
        fold.form(false);
        assertFalse(fold.collapsed(false));
        assertTrue(fold.collapsed(true));
        assertFalse(fold.folded());
    }

    @Test void theFoldedFormStartsFoldedWhateverThePreferenceSays() {
        var fold = new JournalFold();
        fold.form(true);
        assertTrue(fold.collapsed(false), "an expanded preference is still folded by the tight form");
        assertTrue(fold.collapsed(true));
        assertTrue(fold.folded());
    }

    /** The regression: a preference collapsed in the standard form used to win over the explicit expand. */
    @Test void anExplicitExpandWorksAfterTheUserCollapsedInTheStandardForm() {
        var fold = new JournalFold();
        fold.form(false);
        assertTrue(fold.collapsed(true), "the user folded the journal in the standard form");
        fold.form(true);
        assertTrue(fold.collapsed(true), "the tight form starts folded");
        assertTrue(fold.toggleExplicit(), "the toggle expands the current form");
        assertFalse(fold.collapsed(true), "the saved preference no longer hides the body");
        assertFalse(fold.toggleExplicit());
        assertTrue(fold.collapsed(true), "the second toggle folds the current form again");
    }

    @Test void leavingTheFoldedFormsRestoresTheOrdinaryPreference() {
        var fold = new JournalFold();
        fold.form(false);
        fold.form(true);
        fold.toggleExplicit();
        assertFalse(fold.collapsed(true));
        fold.form(false);
        assertTrue(fold.collapsed(true), "a collapsed preference applies again in the standard form");
        fold.form(true);
        assertTrue(fold.collapsed(true), "re-entering the tight form starts folded again");
        fold.form(false);
        assertFalse(fold.collapsed(false), "an expanded preference is still expanded");
    }

    @Test void resizingBetweenTheTwoFoldedFormsKeepsTheExplicitExpand() {
        var fold = new JournalFold();
        fold.form(true);
        fold.toggleExplicit();
        fold.form(true);
        assertFalse(fold.collapsed(true), "tight to narrow is not a form change for the fold");
    }

}
