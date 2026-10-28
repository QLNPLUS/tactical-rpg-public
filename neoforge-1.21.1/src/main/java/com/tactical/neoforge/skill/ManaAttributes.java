package com.tactical.neoforge.skill;

import com.tactical.neoforge.Tactical;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native absolute MP attributes for players, allies and third-party bosses alike. */
public final class ManaAttributes {
    private static final DeferredRegister<Attribute> REGISTER = DeferredRegister.create(Registries.ATTRIBUTE,Tactical.MOD_ID);
    public static final DeferredHolder<Attribute,Attribute> MAX_MP=REGISTER.register("max_mp",
            ()->new RangedAttribute("attribute.tactical.max_mp",100,0,1_000_000).setSyncable(true));
    public static final DeferredHolder<Attribute,Attribute> MP_RECOVERY=REGISTER.register("mp_recovery",
            ()->new RangedAttribute("attribute.tactical.mp_recovery",0,0,1_000_000).setSyncable(true));
    private ManaAttributes() { }
    public static void register(IEventBus bus) {
        REGISTER.register(bus);
        bus.addListener(ManaAttributes::add);
    }
    private static void add(EntityAttributeModificationEvent event) {
        for(var type:event.getTypes()) {
            event.add(type,MAX_MP);
            event.add(type,MP_RECOVERY);
        }
    }
    public static double maximum(LivingEntity actor) {
        return actor.getAttribute(MAX_MP)==null?100:actor.getAttributeValue(MAX_MP);
    }
    public static double recovery(LivingEntity actor) {
        return actor.getAttribute(MP_RECOVERY)==null?0:actor.getAttributeValue(MP_RECOVERY);
    }
}
