package com.tactical.neoforge.client.ui.platform;

/**
 * What makes a piece of already-written client text stale.
 *
 * <p>Three things can change the wording of a page: the player's language, a completed resource reload
 * (glyphs and language files both arrive through it) and the page's own document being rebuilt, which
 * hands back fresh elements that carry the markup's placeholder text again. All three feed one stamp, so
 * a caller can compare it instead of re-writing text every frame. Pure, so the rule is checkable without
 * a client.</p>
 */
public final class TextStamp {

    private TextStamp() {
    }

    public static long of(long language, long resources, long documentGeneration) {
        long stamp = language * 0x9E3779B97F4A7C15L;
        stamp = (stamp ^ resources) * 0xBF58476D1CE4E5B9L;
        return (stamp ^ documentGeneration) * 0x94D049BB133111EBL;
    }
}
