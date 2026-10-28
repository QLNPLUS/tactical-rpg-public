package com.tactical.neoforge.network;

import com.tactical.neoforge.skill.SkillDefinition;
import net.minecraft.resources.ResourceLocation;

/** Display metadata and authoritative availability only; formulas and executable definitions stay server-side. */
public record SkillView(ResourceLocation id, String name, String description, int level, int maxLevel,
                        SkillDefinition.Aim aim, boolean area, boolean targetCosts, boolean ready, String reason,
                        int apCost, double mpCost, int cooldown) {
    public SkillView {
        if (id == null || aim == null || name == null || description == null || reason == null
                || name.length() > 128 || description.length() > 2048 || reason.length() > 512
                || level < 1 || maxLevel < level || maxLevel > 1024 || apCost < 0 || cooldown < 0
                || !Double.isFinite(mpCost) || mpCost < 0 || mpCost > 1_000_000)
            throw new IllegalArgumentException("Invalid skill display view");
    }
}
