package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.ui.platform.ClientText;

/**
 * Resolves the colour settings' keys through the client's own language files.
 *
 * <p>It is one tiny adapter on top of the shared {@link ClientText} path, and it exists so
 * {@link ColorText} can stay pure: the popover asks this for its wording and is drawn with whatever the
 * player's language really says, which is what makes the English layout measurable in a real window.</p>
 */
public final class ColorTextResolver {

    private ColorTextResolver() {
    }

    /** The translated text, or the class's Chinese fallback when the key is not in this language. */
    public static String get(String key) {
        String fallback = ColorText.fallback(key);
        try {
            String text = ClientText.get(key);
            return text == null || text.isEmpty() || text.equals(key) ? fallback : text;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
