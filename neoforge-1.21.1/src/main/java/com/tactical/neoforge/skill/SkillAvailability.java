package com.tactical.neoforge.skill;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable availability snapshot shared by NPC adapters and player menu serialization.
 *
 * <p>{@code reason} is display structure: a translation key plus typed arguments, or - for a custom
 * condition that returned finished text - that text as a literal. Readiness is the explicit
 * {@code ready} flag; nothing downstream decides it by asking whether the reason resolved to blank
 * text.</p>
 */
public record SkillAvailability(ResourceLocation id,int level,boolean ready,Component reason,int apCost,
                                double mpCost,int cooldown) {
    /** Compatibility form for callers that only have finished text: it is a literal, never a key. */
    public SkillAvailability(ResourceLocation id,int level,boolean ready,String reason,int apCost,
                             double mpCost,int cooldown) {
        this(id,level,ready,SkillText.literalText(reason==null?"":reason),apCost,mpCost,cooldown);
    }
    public SkillAvailability {
        if(reason==null)reason=SkillText.none();
    }
}
