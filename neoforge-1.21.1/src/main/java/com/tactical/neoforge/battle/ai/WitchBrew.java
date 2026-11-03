package com.tactical.neoforge.battle.ai;

/**
 * The potion this mod's witch tactics choose, named once instead of as a raw vanilla holder.
 *
 * <p>Self-drink and thrown brews are one vocabulary because they share one cooldown and one action slot:
 * the decision code never has to know which vanilla potion registry entry a name resolves to, and the
 * server is the only place that turns a name into a real item stack and a real effect application.</p>
 */
public enum WitchBrew {
    // Self-drink: the same three buffs and one heal that vanilla can pick while drinking.
    HEALING(false),
    WATER_BREATHING(false),
    FIRE_RESISTANCE(false),
    SWIFTNESS(false),
    // Thrown: the debuff set vanilla picks for a non-raider target, plus instant harming.
    SLOWNESS(true),
    POISON(true),
    WEAKNESS(true),
    HARMING(true);

    private final boolean thrown;

    WitchBrew(boolean thrown) {
        this.thrown = thrown;
    }

    /** True for the brews this mod throws at an opponent, false for the ones a witch drinks herself. */
    public boolean thrown() { return thrown; }

    /** True for the ones a witch drinks herself; never thrown, never a splash. */
    public boolean selfDrink() { return !thrown; }
}
