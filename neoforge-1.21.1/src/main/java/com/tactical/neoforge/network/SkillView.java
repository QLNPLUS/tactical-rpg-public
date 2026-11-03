package com.tactical.neoforge.network;

import com.tactical.neoforge.skill.SkillDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Display metadata and authoritative availability only; formulas and executable definitions stay server-side.
 *
 * <p>Name, description and reason are native {@link Component}s: the encounter is authored on the server,
 * but the row is read by each member in that member's own language, so only the display point resolves
 * them. Readiness is the explicit {@code ready} flag - never "the reason resolved to blank".</p>
 *
 * <p>The String constructor is the compatibility form for callers that only have finished text: it is
 * carried as a <em>literal</em>, never guessed to be a translation key, and keeps the original per-field
 * character contract (128 / 2048 / 512).</p>
 */
public record SkillView(ResourceLocation id, Component name, Component description, int level, int maxLevel,
                        SkillDefinition.Aim aim, boolean area, boolean targetCosts, boolean ready, Component reason,
                        int apCost, double mpCost, int cooldown) {
    public SkillView {
        if (id == null || aim == null || name == null || description == null || reason == null
                || level < 1 || maxLevel < level || maxLevel > 1024 || apCost < 0 || cooldown < 0
                || !Double.isFinite(mpCost) || mpCost < 0 || mpCost > 1_000_000)
            throw new IllegalArgumentException("Invalid skill display view");
    }

    /** Compatibility form: the three texts are literals with their original character limits. */
    public SkillView(ResourceLocation id, String name, String description, int level, int maxLevel,
                     SkillDefinition.Aim aim, boolean area, boolean targetCosts, boolean ready, String reason,
                     int apCost, double mpCost, int cooldown) {
        this(id, literal(name, 128), literal(description, 2048), level, maxLevel, aim, area, targetCosts, ready,
                literal(reason, 512), apCost, mpCost, cooldown);
    }

    private static Component literal(String text, int limit) {
        String value = text == null ? "" : text;
        if (value.length() > limit) throw new IllegalArgumentException("Invalid skill display view");
        return Component.literal(value);
    }
}
