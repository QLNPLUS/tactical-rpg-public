package com.tactical.neoforge.client;

/**
 * The journal's two independent collapse sources, kept apart.
 * <p>
 * In the standard form the ordinary user preference decides the body. The tight and narrow forms
 * fold the journal to its title row by default so the command panel keeps every description row;
 * their toggle switches an explicit in-form expand instead of rewriting that saved preference.
 * Leaving the folded forms drops the explicit expand, so the ordinary preference applies again.
 * Collapsing by preference and folding by form are therefore never OR-ed together, which would make
 * an explicit expand impossible whenever the preference was collapsed.
 * <p>
 * Pure state: no viewport, no Minecraft dependency.
 */
final class JournalFold {
    private boolean folded;
    private boolean expanded;

    /** Called on every layout pass with the current window form. */
    void form(boolean folds) {
        // Entering or leaving the folded forms starts from the form's own default; a resize between
        // the two folded forms keeps an explicit expand.
        if (folds != folded) expanded = false;
        folded = folds;
    }

    boolean folded() { return folded; }

    /** The toggle inside a folded form. Returns the body state it switched to. */
    boolean toggleExplicit() {
        expanded = !expanded;
        return expanded;
    }

    /** Whether the body is hidden right now, given the ordinary saved preference. */
    boolean collapsed(boolean ordinaryCollapsed) {
        return folded ? !expanded : ordinaryCollapsed;
    }
}
