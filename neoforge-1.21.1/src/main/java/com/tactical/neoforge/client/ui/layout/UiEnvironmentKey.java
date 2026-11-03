package com.tactical.neoforge.client.ui.layout;

/**
 * The part of a panel's layout fingerprint that comes from outside the page: the saved preferences,
 * the language, how many times resources have reloaded, and the font's identity and line height.
 *
 * <p>Pure, so "does a reload invalidate the layout, and does an idle frame keep it" is a decision that
 * can be checked without a window. The resource generation is the real signal: a pack or language
 * change can swap glyph metrics inside the same {@code Font} object, so comparing that object alone
 * misses it. Font identity and line height stay in the key as a cheap extra guard for the cases where
 * the client does hand out a new font.</p>
 */
public final class UiEnvironmentKey {

    private UiEnvironmentKey() {
    }

    public static long of(long preferenceRevision, String language, long resourceGeneration, int fontIdentity,
                          int lineHeight) {
        long hash = Fingerprint.mix(Fingerprint.start(), preferenceRevision);
        hash = Fingerprint.text(hash, language);
        hash = Fingerprint.mix(hash, resourceGeneration);
        hash = Fingerprint.mix(hash, fontIdentity);
        return Fingerprint.mix(hash, lineHeight);
    }
}
