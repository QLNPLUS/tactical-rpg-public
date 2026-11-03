package com.tactical.core.progression;

import java.util.Locale;

/**
 * The server-side growth rules: every price, cap and coefficient in one immutable value.
 *
 * <p>The rules are read from configuration and are re-read for every authoritative decision, so nothing
 * here caches them. Two facts follow and both matter: the derived modifiers are computed only from
 * <em>invested</em> points (a displayed baseline of {@link CharacterStat#BASE} never becomes a bonus), and
 * {@link #fingerprint()} identifies the exact rule set a price was quoted under, which is how a page that
 * was priced under previous rules is recognised as stale instead of being charged at a new price.</p>
 */
public record ProgressionRules(int maxLevel, int statCap, int pointsPerLevel, long xpBase, long xpPerLevel,
                               double vigorHealth, double mindMana, double enduranceApPercent,
                               double strengthDamage, double dexterityCharge, double intelligenceSkillPower) {

    /** The approved first-release values; also what an unloaded configuration falls back to. */
    public static final ProgressionRules DEFAULTS =
            new ProgressionRules(100, 99, 1, 100L, 50L, 2.0D, 5.0D, 2.0D, 0.5D, 0.01D, 0.02D);

    /** Upper bound of any single coefficient, so a rule set can never produce a non-finite modifier. */
    private static final double MAX_COEFFICIENT = 1_000_000.0D;

    public ProgressionRules {
        if (maxLevel < 1 || maxLevel > 1_000_000) throw new IllegalArgumentException("Invalid max level " + maxLevel);
        if (statCap < CharacterStat.BASE || statCap > 1_000_000)
            throw new IllegalArgumentException("Invalid stat cap " + statCap);
        if (pointsPerLevel < 0 || pointsPerLevel > 1_000_000)
            throw new IllegalArgumentException("Invalid points per level " + pointsPerLevel);
        if (xpBase < 0 || xpBase > 1_000_000_000L) throw new IllegalArgumentException("Invalid xp base " + xpBase);
        if (xpPerLevel < 0 || xpPerLevel > 1_000_000_000L)
            throw new IllegalArgumentException("Invalid xp per level " + xpPerLevel);
        coefficient(vigorHealth, "vigor health");
        coefficient(mindMana, "mind mana");
        coefficient(enduranceApPercent, "endurance ap");
        coefficient(strengthDamage, "strength damage");
        coefficient(dexterityCharge, "dexterity charge");
        coefficient(intelligenceSkillPower, "intelligence skill power");
    }

    private static void coefficient(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > MAX_COEFFICIENT)
            throw new IllegalArgumentException("Invalid " + name + " coefficient " + value);
    }

    /** How many points one displayed stat may hold above its baseline. */
    public int maximumInvested() { return statCap - CharacterStat.BASE; }

    /** Vanilla-XP price of the next character level; saturating, never negative. */
    public long xpToNextLevel(int characterLevel) {
        int level = Math.max(1, characterLevel);
        long growth = xpPerLevel * (long) (level - 1);
        long total = xpBase + growth;
        return Math.max(0L, total < xpBase ? Long.MAX_VALUE : total);
    }

    /**
     * Displayed value of one stat: the baseline plus the points actually invested.
     *
     * <p>It is deliberately <em>not</em> clamped to {@link #statCap()}. A cap that is lowered while points
     * are already invested must show what the character really has - the stored points are history and are
     * never taken back or silently rewritten - and the cap only refuses the next increment. Clamping here
     * would hide committed investment and make the page disagree with the real attribute.</p>
     */
    public int displayValue(int invested) {
        long value = (long) CharacterStat.BASE + Math.max(0, invested);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    /** The configured coefficient of one stat. */
    public double coefficient(CharacterStat stat) {
        return switch (stat) {
            case VIGOR -> vigorHealth;
            case MIND -> mindMana;
            case ENDURANCE -> enduranceApPercent;
            case STRENGTH -> strengthDamage;
            case DEXTERITY -> dexterityCharge;
            case INTELLIGENCE -> intelligenceSkillPower;
        };
    }

    /**
     * The bonus this rule set attaches to {@code invested} points of one stat.
     *
     * <p>Units follow the attribute it is applied to: absolute max health, absolute max MP, percentage
     * points of {@code tactical:max_ap} (the attribute keeps 100 = 100%), charge multiplier, absolute
     * attack damage and the skill-power multiplier respectively.</p>
     */
    public double bonus(CharacterStat stat, int invested) {
        int points = Math.max(0, invested);
        double value = coefficient(stat) * points;
        return Double.isFinite(value) ? value : MAX_COEFFICIENT * points;
    }

    /** The skill-damage multiplier of a character with {@code invested} intelligence points. */
    public double skillPower(int investedIntelligence) {
        double value = 1.0D + bonus(CharacterStat.INTELLIGENCE, investedIntelligence);
        return Double.isFinite(value) && value >= 1.0D ? value : 1.0D;
    }

    /** Applies the skill-power multiplier exactly once to a resolved skill damage amount. */
    public double skillDamage(double amount, int investedIntelligence) {
        double scaled = amount * skillPower(investedIntelligence);
        return Double.isFinite(scaled) && scaled >= 0 ? scaled : amount;
    }

    /**
     * Identity of the whole rule set, including the level cap and the stat cap.
     *
     * <p>It is compared against the fingerprint stored next to a character profile. A difference means the
     * price or the caps a page showed are no longer the ones the server would charge, so the profile's
     * revision is advanced and the old request is refused rather than repriced silently.</p>
     */
    public String fingerprint() {
        return String.format(Locale.ROOT, "v1|max=%d|cap=%d|pts=%d|xp=%d+%d|vigor=%s|mind=%s|endu=%s|str=%s|dex=%s|int=%s",
                maxLevel, statCap, pointsPerLevel, xpBase, xpPerLevel,
                number(vigorHealth), number(mindMana), number(enduranceApPercent),
                number(strengthDamage), number(dexterityCharge), number(intelligenceSkillPower));
    }

    private static String number(double value) { return Double.toString(value); }

    /** Same rules in the shorter form used by logs and failure details. */
    @Override public String toString() { return fingerprint(); }
}
