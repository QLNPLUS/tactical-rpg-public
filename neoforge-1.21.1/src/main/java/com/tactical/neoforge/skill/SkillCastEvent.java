package com.tactical.neoforge.skill;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** Public NeoForge hooks around an authoritative skill transaction. */
public abstract class SkillCastEvent extends Event {
    private final SkillCastContext context;
    protected SkillCastEvent(SkillCastContext context){this.context=context;}
    public SkillCastContext context(){return context;}
    /** Cancelling occurs before AP, MP, cooldown or native world effects are changed. */
    public static final class Before extends SkillCastEvent implements ICancellableEvent {
        private final int apCost;private final double mpCost;
        public Before(SkillCastContext context,int apCost,double mpCost){super(context);this.apCost=apCost;this.mpCost=mpCost;}
        public int apCost(){return apCost;}public double mpCost(){return mpCost;}
    }
    public static final class After extends SkillCastEvent {
        public After(SkillCastContext context){super(context);}
    }
}
