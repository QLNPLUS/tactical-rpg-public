package com.tactical.neoforge.skill;

import com.tactical.neoforge.battle.ServerBattle;
import net.minecraft.world.entity.LivingEntity;
import java.util.List;

/** Authoritative cast context; third-party actions receive already validated affected actors. */
public record SkillCastContext(ServerBattle battle,LivingEntity caster,SkillDefinition definition,int level,
                               SkillTarget requested,List<LivingEntity> affected) {
    public SkillCastContext {affected=List.copyOf(affected);}
}
