package com.tactical.neoforge.skill;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Locale;

/**
 * One place for the wording the skill system itself produces: availability reasons, cast/preview
 * rejections, the skill's own condition failures, the weapon-attack legality preview and the cast row.
 *
 * <p>Every producer here emits <em>structure</em> - a translation key plus typed arguments - and the
 * client resolves it in its own language. The server never resolves a sentence: there is no
 * {@code getString()} on the way out, and a custom condition or action that returns finished text is
 * carried as a literal, never guessed to be a translation key.</p>
 *
 * <p>A skill definition's own {@code name}/{@code description} stay literal text unless the definition
 * declares an explicit {@code name_key}/{@code description_key}; see
 * {@link SkillDefinition#displayName()}. Only such an explicit key is ever looked up, so a third-party
 * skill whose text merely looks like a key keeps reading exactly as it was written.</p>
 */
public final class SkillText {

    // ---- availability reasons (shown on an unusable row) ---------------------------------

    public static final String KEY_REASON_NOT_MY_TURN = "tactical.skill.reason.not_my_turn";
    public static final String KEY_REASON_BUSY = "tactical.skill.reason.busy";
    public static final String KEY_REASON_COOLDOWN = "tactical.skill.reason.cooldown";
    public static final String KEY_REASON_AP = "tactical.skill.reason.ap";
    public static final String KEY_REASON_MP = "tactical.skill.reason.mp";
    public static final String KEY_REASON_REQUIREMENT = "tactical.skill.reason.requirement";

    // ---- cast/preview rejections ---------------------------------------------------------

    public static final String KEY_REJECT_NOT_IN_BATTLE = "tactical.skill.reject.not_in_battle";
    public static final String KEY_REJECT_UNKNOWN_SKILL = "tactical.skill.reject.unknown_skill";
    public static final String KEY_REJECT_NOT_GRANTED = "tactical.skill.reject.not_granted";
    public static final String KEY_REJECT_COOLDOWN = "tactical.skill.reject.cooldown";
    public static final String KEY_REJECT_SELF_TARGET = "tactical.skill.reject.self_target";
    public static final String KEY_REJECT_ENTITY_TARGET = "tactical.skill.reject.entity_target";
    public static final String KEY_REJECT_GROUND_TARGET = "tactical.skill.reject.ground_target";
    public static final String KEY_REJECT_NON_AREA_ENTITY = "tactical.skill.reject.non_area_entity";
    public static final String KEY_REJECT_RELATION = "tactical.skill.reject.relation";
    public static final String KEY_REJECT_OUTSIDE_ZONE = "tactical.skill.reject.outside_zone";
    public static final String KEY_REJECT_RANGE = "tactical.skill.reject.range";
    public static final String KEY_REJECT_DISTANCE = "tactical.skill.reject.distance";
    public static final String KEY_REJECT_LINE_OF_SIGHT = "tactical.skill.reject.line_of_sight";
    public static final String KEY_REJECT_VERTICAL = "tactical.skill.reject.vertical";
    public static final String KEY_REJECT_TOO_MANY_TARGETS = "tactical.skill.reject.too_many_targets";
    public static final String KEY_REJECT_WEAPON_RANGED = "tactical.skill.reject.weapon_ranged";
    public static final String KEY_REJECT_WEAPON_SINGLE = "tactical.skill.reject.weapon_single";
    public static final String KEY_REJECT_PREVIEW_TOO_LARGE = "tactical.skill.reject.preview_too_large";
    public static final String KEY_REJECT_FORMULA = "tactical.skill.reject.formula";
    public static final String KEY_REJECT_UNKNOWN_DAMAGE_TYPE = "tactical.skill.reject.unknown_damage_type";
    public static final String KEY_REJECT_UNKNOWN_EFFECT = "tactical.skill.reject.unknown_effect";
    public static final String KEY_REJECT_EFFECT_LEVEL = "tactical.skill.reject.effect_level";
    public static final String KEY_REJECT_EFFECT_DURATION = "tactical.skill.reject.effect_duration";
    /**
     * Shown when the skill system itself failed (a broken third-party condition, action or formula that
     * threw instead of refusing). The real cause stays in the server log: a player never reads a Java
     * exception message.
     */
    public static final String KEY_REJECT_UNEXPECTED = "tactical.skill.reject.unexpected";

    // ---- the outcome of one cast ---------------------------------------------------------

    public static final String KEY_CAST_CANCELLED = "tactical.skill.cast.cancelled";
    public static final String KEY_CAST_NO_EFFECT = "tactical.skill.cast.no_effect";
    public static final String KEY_CAST_FAILED_REFUNDED = "tactical.skill.cast.failed_refunded";
    public static final String KEY_CAST_FAILED_COMMITTED = "tactical.skill.cast.failed_committed";
    public static final String KEY_CAST_LOG = "tactical.skill.cast.log";

    // ---- this mod's own cast conditions --------------------------------------------------

    public static final String KEY_CONDITION_MELEE_WEAPON = "tactical.skill.condition.melee_weapon";
    public static final String KEY_CONDITION_REAR = "tactical.skill.condition.rear";
    public static final String KEY_CONDITION_REAR_TARGET = "tactical.skill.condition.rear_target";

    // ---- weapon-attack legality preview (shared with skills and the attack range) ---------

    public static final String KEY_REJECT_FRIENDLY_FIRE = "tactical.skill.reject.friendly_fire";
    public static final String KEY_REJECT_MELEE_BLOCKED = "tactical.skill.reject.melee_blocked";
    public static final String KEY_REJECT_OUT_OF_RANGE = "tactical.skill.reject.out_of_range";
    public static final String KEY_REJECT_BALLISTIC_BLOCKED = "tactical.skill.reject.ballistic_blocked";
    public static final String KEY_REJECT_OUT_OF_RANGE_OR_BLOCKED = "tactical.skill.reject.out_of_range_or_blocked";

    // ---- formula field names (arguments of the formula rejection) -------------------------

    public static final String KEY_FIELD_AP = "tactical.skill.field.ap";
    public static final String KEY_FIELD_MP = "tactical.skill.field.mp";
    public static final String KEY_FIELD_RANGE = "tactical.skill.field.range";
    public static final String KEY_FIELD_MIN_RANGE = "tactical.skill.field.min_range";
    public static final String KEY_FIELD_DAMAGE = "tactical.skill.field.damage";
    public static final String KEY_FIELD_EFFECT_LEVEL = "tactical.skill.field.effect_level";
    public static final String KEY_FIELD_EFFECT_DURATION = "tactical.skill.field.effect_duration";
    public static final String KEY_FIELD_MULTIPLIER = "tactical.skill.field.multiplier";
    public static final String KEY_FIELD_COOLDOWN = "tactical.skill.field.cooldown";

    /**
     * Stable short line used when one skill text cannot fit a component frame. The field is refused, the
     * skill itself is kept: its identity, readiness, level and costs are untouched.
     */
    public static final String KEY_TEXT_TOO_LONG = "tactical.skill.text_too_long";

    private SkillText() {
    }

    // ---- composition ---------------------------------------------------------------------

    public static Component translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /**
     * An empty component: "this check passed".
     *
     * <p>Readiness is decided by the producer's own outcome flag, never by resolving text and asking
     * whether it was blank, so this is only the marker for an allowed weapon attack or a satisfied
     * condition.</p>
     */
    public static Component none() {
        return Component.empty();
    }

    /** Whether {@code text} is that empty marker, without resolving any translatable content. */
    public static boolean isEmpty(Component text) {
        return text == null
                || text.getContents() instanceof PlainTextContents plain
                && plain.text().isEmpty() && text.getSiblings().isEmpty();
    }

    /** Whether {@code text} is exactly the translation key {@code key} (never a resolved sentence). */
    public static boolean isKey(Component text, String key) {
        return text != null && text.getContents() instanceof TranslatableContents translatable
                && key.equals(translatable.getKey());
    }

    /**
     * A description or name as it must read: verbatim, newlines and Unicode included.
     *
     * <p>Definition text is authored data (this mod's own files or a data pack), so it is kept exactly as
     * written instead of being cleaned like a player-typed name. Only the wire frame bounds it; a value
     * that cannot fit is refused at publication time rather than silently shortened here.</p>
     */
    public static Component literalText(String text) {
        return Component.literal(text == null ? "" : text);
    }

    /**
     * The display form of one definition field: the explicit translation key with the authored literal as
     * its fallback, or the literal alone when the definition declares no key.
     *
     * <p>The fallback is the original authored text, so a language that does not carry the key - and a
     * definition whose key nobody ships - still reads exactly as before.</p>
     */
    public static Component definition(String key, String literal) {
        String text = literal == null ? "" : literal;
        return key == null || key.isBlank() ? literalText(text) : Component.translatableWithFallback(key, text);
    }

    /** Fixed text of one argument: a number or an identifier travels as text, never as a sentence. */
    public static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
