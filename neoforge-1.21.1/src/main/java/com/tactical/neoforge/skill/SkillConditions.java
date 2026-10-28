package com.tactical.neoforge.skill;

import com.tactical.core.combat.PositionalDamage;
import com.tactical.neoforge.TacticalConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/** Extensible side-effect-free cast requirements; empty string means satisfied. */
public final class SkillConditions {
    @FunctionalInterface public interface Condition {String failure(SkillCastContext context);}
    private static final Map<ResourceLocation,Condition> CONDITIONS=new ConcurrentSkipListMap<>();
    private static final Set<ResourceLocation> GLOBAL=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static {
        registerGlobal(ResourceLocation.fromNamespaceAndPath("tactical","melee_weapon"),ctx->{
            var stack=ctx.caster().getMainHandItem();
            return stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES)||stack.is(Items.MACE)||stack.is(Items.TRIDENT)?"":"需要近战武器";
        });
        register(ResourceLocation.fromNamespaceAndPath("tactical","rear"),ctx->{
            var selected=ctx.affected().stream().filter(e->e.getUUID().equals(ctx.requested().entity())).findFirst().orElse(null);
            if(selected==null)return "需要敌方实体目标";
            double front=45,rear=45;
            try {front=TacticalConfig.INSTANCE.damageFrontHalfAngle.get();rear=TacticalConfig.INSTANCE.damageRearHalfAngle.get();}catch(IllegalStateException ignored) { }
            var sector=PositionalDamage.sector(selected.yBodyRot,ctx.caster().getX()-selected.getX(),ctx.caster().getZ()-selected.getZ(),front,rear);
            return sector==PositionalDamage.Sector.REAR?"":"必须从目标背后发动";
        });
    }
    private SkillConditions() { }
    public static void register(ResourceLocation id,Condition condition) {
        if(CONDITIONS.putIfAbsent(Objects.requireNonNull(id),Objects.requireNonNull(condition))!=null)
            throw new IllegalArgumentException("Duplicate skill condition "+id);
    }
    public static void registerGlobal(ResourceLocation id,Condition condition) {register(id,condition);GLOBAL.add(id);}
    public static String failure(SkillCastContext context) {return failure(context,true);}
    public static String failure(SkillCastContext context,boolean includeTargetConditions) {
        for(var id:context.definition().conditions()) {
            if(!includeTargetConditions&&!GLOBAL.contains(id))continue;
            Condition condition=CONDITIONS.get(id);
            if(condition==null)throw new IllegalArgumentException("Unknown skill condition "+id);
            String failure=condition.failure(context);
            if(failure!=null&&!failure.isBlank())return failure;
        }
        return "";
    }
}
