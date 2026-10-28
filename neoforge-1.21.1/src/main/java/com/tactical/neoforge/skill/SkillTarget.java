package com.tactical.neoforge.skill;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.LivingEntity;
import java.util.UUID;

/** A requested entity or ground anchor. Authority, range and facing are rechecked server-side. */
public record SkillTarget(UUID entity,GridPos ground,float yaw) {
    public SkillTarget {
        if((entity==null)==(ground==null))throw new IllegalArgumentException("Exactly one skill target anchor is required");
        if(!Float.isFinite(yaw))throw new IllegalArgumentException("Nonfinite skill orientation");
    }
    public static SkillTarget entity(UUID id) {return new SkillTarget(id,null,0);}
    public static SkillTarget ground(GridPos position,float yaw) {return new SkillTarget(null,position,yaw);}
    public static SkillTarget self(LivingEntity actor) {return entity(actor.getUUID());}
}
