package com.tactical.neoforge.skill;

import net.minecraft.resources.ResourceLocation;

/** Immutable availability snapshot shared by NPC adapters and player menu serialization. */
public record SkillAvailability(ResourceLocation id,int level,boolean ready,String reason,int apCost,
                                double mpCost,int cooldown) { }
