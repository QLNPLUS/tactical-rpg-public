package com.tactical.neoforge.progression;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import net.minecraft.network.chat.Component;

/**
 * The one place where progression wording is chosen on the server.
 *
 * <p>Every text is a translation key plus typed arguments, never a finished sentence: the answering
 * player reads it in that player's own language. The keys are listed in
 * {@code .narrafork/plans/v025-progression-language.json} so the single writer of the shared language
 * tables can merge them once.</p>
 */
public final class ProgressionText {

    public static final String PREFIX = "tactical.progression.";
    public static final String ATTRIBUTE_SKILL_POWER = "attribute.tactical.skill_power";
    public static final String PAGE_TITLE = PREFIX + "page.title";
    public static final String PAGE_OWNER_UNKNOWN = PREFIX + "page.unknown_owner";
    public static final String DRAFT_PENDING = PREFIX + "page.draft_pending";
    public static final String STATUS_VIEW_ONLY = PREFIX + "status.view_only";

    private ProgressionText() { }

    /** Display structure for one refusal code. */
    public static Component reason(ProgressionReason reason) {
        ProgressionReason safe = reason == null ? ProgressionReason.SERVER_ERROR : reason;
        return Component.translatable(safe.key());
    }

    /** A refusal whose text needs the configured value that refused it (the cap, the price, ...). */
    public static Component reason(ProgressionReason reason, Component detail) {
        return Component.translatable(reason == null ? ProgressionReason.SERVER_ERROR.key() : reason.key(), detail);
    }

    public static String statKey(CharacterStat stat) { return stat.translationKey(); }

    /** Numbers are formatted on the argument side so a locale can never reorder or re-separate them. */
    public static String number(long value) { return Long.toString(value); }

    public static String number(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }
}
