package com.tactical.neoforge.progression;

import com.tactical.core.progression.ProgressionRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * Reads the authoritative growth rules out of the common configuration.
 *
 * <p>Nothing is cached: every decision re-reads the current values, because a reloaded rule set has to take
 * effect for the next request. A configuration that has not been loaded yet - which is the normal state
 * inside a plain unit test - falls back to the same defaults the specification freezes, exactly like the
 * existing MP accessors do.</p>
 */
public final class ProgressionSettings {

    private ProgressionSettings() { }

    /** The current rules, or the frozen defaults when the configuration is unavailable or invalid. */
    public static ProgressionRules rules() {
        try {
            TacticalConfig config = TacticalConfig.INSTANCE;
            return new ProgressionRules(
                    integer(config.progressionMaxLevel, 100),
                    integer(config.progressionStatCap, 99),
                    integer(config.progressionPointsPerLevel, 1),
                    integer(config.progressionXpBase, 100),
                    integer(config.progressionXpPerLevel, 50),
                    decimal(config.progressionVigorHp, 2.0D),
                    decimal(config.progressionMindMp, 5.0D),
                    decimal(config.progressionEnduranceAp, 2.0D),
                    decimal(config.progressionStrengthDamage, 0.5D),
                    decimal(config.progressionDexterityCharge, 0.01D),
                    decimal(config.progressionIntelligenceSkillPower, 0.02D));
        } catch (RuntimeException unavailable) {
            // A rule set that violates its own invariants must never take the server down with it.
            if (!(unavailable instanceof IllegalStateException)) Tactical.LOGGER.warn(
                    "[Tactical] progression rules are unusable; the frozen defaults decide this decision", unavailable);
            return ProgressionRules.DEFAULTS;
        }
    }

    private static int integer(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private static double decimal(ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    /** Convenience for call sites that only need a supplier-shaped read. */
    public static IntSupplier maxLevel() { return () -> rules().maxLevel(); }

    /** Convenience for call sites that only need a supplier-shaped read. */
    public static DoubleSupplier skillPowerPerPoint() {
        return () -> rules().coefficient(com.tactical.core.progression.CharacterStat.INTELLIGENCE);
    }
}
