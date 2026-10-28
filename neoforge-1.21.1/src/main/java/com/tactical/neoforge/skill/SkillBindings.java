package com.tactical.neoforge.skill;

import com.tactical.core.combat.PositionalDamage;
import com.tactical.neoforge.battle.ServerBattle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/** Numeric formula inputs. Attribute aliases bind IDs without requiring string functions in formulas. */
public final class SkillBindings {
    public record AttributeInput(ResourceLocation attribute,boolean target) { }
    @FunctionalInterface public interface Variable {double value(ServerBattle battle,LivingEntity caster,LivingEntity target,int level);}
    private record Provider(String name,Variable variable) { }
    private static final Map<ResourceLocation,Provider> PROVIDERS=new ConcurrentSkipListMap<>();
    private static final Set<String> BUILTINS=Set.of("base","level","caster_level","attack_damage","attack_speed",
            "health","max_health","armor","movement_speed","target_health","target_max_health","target_armor",
            "ap","mp","max_mp","distance","height_difference","direction_multiplier");
    private SkillBindings() { }
    public static synchronized void register(ResourceLocation id,String name,Variable variable) {
        if(!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")||allowedVariables().contains(name))throw new IllegalArgumentException("Duplicate/invalid skill variable "+name);
        if(PROVIDERS.putIfAbsent(Objects.requireNonNull(id),new Provider(name,Objects.requireNonNull(variable)))!=null)
            throw new IllegalArgumentException("Duplicate variable provider "+id);
    }
    public static Set<String> allowedVariables() {
        Set<String> names=new HashSet<>(BUILTINS);PROVIDERS.values().forEach(p->names.add(p.name));return Set.copyOf(names);
    }
    public static Set<String> allowedVariables(Map<String,AttributeInput> attributes) {
        Set<String> names=new HashSet<>(allowedVariables());
        for(String alias:attributes.keySet()) {
            if(!alias.matches("[a-zA-Z_][a-zA-Z0-9_]*")||!names.add(alias))throw new IllegalArgumentException("Duplicate/invalid attribute alias "+alias);
        }
        return Set.copyOf(names);
    }
    public static Map<String,Double> values(ServerBattle battle,LivingEntity caster,LivingEntity target,int level,Map<String,AttributeInput> attributes) {
        LivingEntity other=target==null?caster:target;
        Map<String,Double> values=new HashMap<>();
        double attack=attribute(caster,Attributes.ATTACK_DAMAGE);
        values.put("base",attack);values.put("level",(double)level);
        values.put("caster_level",caster instanceof Player p?(double)p.experienceLevel:0D);
        values.put("attack_damage",attack);values.put("attack_speed",attribute(caster,Attributes.ATTACK_SPEED));
        values.put("health",(double)caster.getHealth());values.put("max_health",(double)caster.getMaxHealth());
        values.put("armor",(double)caster.getArmorValue());values.put("movement_speed",attribute(caster,Attributes.MOVEMENT_SPEED));
        values.put("target_health",(double)other.getHealth());values.put("target_max_health",(double)other.getMaxHealth());
        values.put("target_armor",(double)other.getArmorValue());values.put("ap",(double)battle.actorAp(caster));
        values.put("mp",ManaResources.current(caster));values.put("max_mp",ManaResources.maximum(caster));
        values.put("distance",caster.position().distanceTo(other.position()));values.put("height_difference",caster.getY()-other.getY());
        var sector=PositionalDamage.sector(other.yBodyRot,caster.getX()-other.getX(),caster.getZ()-other.getZ());
        values.put("direction_multiplier",PositionalDamage.direction(sector,PositionalDamage.DEFAULTS));
        for(var entry:attributes.entrySet()) {
            var holder=BuiltInRegistries.ATTRIBUTE.getHolder(entry.getValue().attribute());
            LivingEntity source=entry.getValue().target()?other:caster;
            values.put(entry.getKey(),holder.isPresent()?attribute(source,holder.get()):0D);
        }
        for(Provider provider:PROVIDERS.values()) {
            double value=provider.variable.value(battle,caster,other,level);
            if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite variable "+provider.name);
            values.put(provider.name,value);
        }
        return Map.copyOf(values);
    }
    private static double attribute(LivingEntity actor,net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> type) {
        return actor.getAttribute(type)==null?0:actor.getAttributeValue(type);
    }
}
