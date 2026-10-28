package com.tactical.neoforge.skill;

import com.tactical.core.grid.GridPos;
import com.tactical.core.skill.SkillArea;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Server-authoritative skill planning, resource reservation, cooldowns and native execution. */
@EventBusSubscriber(modid=Tactical.MOD_ID)
public final class TacticalSkills {
    private static final int MAX_VALUE=1_000_000;
    private static final Map<ServerBattle,State> STATES=new WeakHashMap<>();
    private static final ThreadLocal<DamageScope> DAMAGE_SCOPE=new ThreadLocal<>();
    private static final class State {
        final Map<UUID,Integer> turns=new HashMap<>();
        final Map<UUID,Map<ResourceLocation,Integer>> cooldowns=new HashMap<>();
    }
    private record DamageScope(LivingEntity caster,Set<UUID> targets,double multiplier) { }
    private record PreparedEffect(Holder<MobEffect> effect,int amplifier,int ticks) { }
    private record TargetAction(LivingEntity actor,DamageSource damage,float amount,List<PreparedEffect> effects) { }
    private record Plan(SkillCastContext context,int ap,double mp,int cooldown,Vec3 anchor,
                        SkillArea region,double low,double high,double multiplier,List<TargetAction> actions) { }
    private TacticalSkills() { }
    private static State state(ServerBattle battle) {return STATES.computeIfAbsent(battle,ignored->new State());}
    public static void onTurnStart(ServerBattle battle,LivingEntity actor) {
        State state=state(battle);UUID id=actor.getUUID();
        Integer before=state.turns.get(id);
        state.turns.put(id,before==null?1:Math.addExact(before,1));
        ManaResources.current(actor);
        if(before!=null)ManaResources.add(actor,ManaResources.recovery(actor));
        var cooldowns=state.cooldowns.get(id);
        if(cooldowns!=null)cooldowns.entrySet().removeIf(e->e.getValue()<=state.turns.get(id));
    }
    public static void onBattleEnd(ServerBattle battle) {
        STATES.remove(battle);
        changed(battle,false);
    }
    private static void changed(ServerBattle battle,boolean active) {
        NeoForge.EVENT_BUS.post(new SkillStateChangedEvent(battle,active));
    }
    public static int cooldown(ServerBattle battle,LivingEntity actor,ResourceLocation id) {
        State s=state(battle);
        return Math.max(0,s.cooldowns.getOrDefault(actor.getUUID(),Map.of()).getOrDefault(id,0)-s.turns.getOrDefault(actor.getUUID(),0));
    }
    public static List<SkillAvailability> available(ServerBattle battle,LivingEntity actor) {
        if(battle.finished()||!battle.isParticipant(actor)||!actor.isAlive())return List.of();
        List<SkillAvailability> results=new ArrayList<>();
        for(var grant:new TreeMap<>(SkillSources.resolve(actor)).entrySet()) {
            SkillDefinition definition=SkillRegistry.get(grant.getKey());if(definition==null)continue;
            int level=grant.getValue(),ap=0,remaining=cooldown(battle,actor,definition.id());double mp=0;String reason="";
            try {
                var values=SkillBindings.values(battle,actor,null,level,definition.attributes());
                boolean needsTarget=dependsOnTarget(definition.apCost(),definition)||dependsOnTarget(definition.mpCost(),definition);
                if(!needsTarget) {
                    ap=battle.quoteActorActionCost(actor,integer(definition.apCost().evaluate(values),"AP"),definition.apRamp());
                    mp=positive(definition.mpCost().evaluate(values),"MP");
                }
                if(!battle.isActorTurn(actor))reason="尚未轮到该单位";
                else if(battle.isBusy())reason="等待当前动作结算";
                else if(remaining>0)reason="冷却中："+remaining+" 回合";
                else if(!needsTarget&&battle.actorAp(actor)<ap)reason="AP 不足";
                else if(!needsTarget&&ManaResources.current(actor)+1.0E-7<mp)reason="MP 不足";
                else if(!dependsOnTarget(definition.requirement(),definition)&&definition.requirement().evaluate(values)==0)reason="不满足释放条件";
                else reason=SkillConditions.failure(new SkillCastContext(battle,actor,definition,level,SkillTarget.self(actor),List.of()),false);
            } catch(RuntimeException failure) {reason="技能公式或条件无效";}
            results.add(new SkillAvailability(definition.id(),level,reason.isEmpty(),reason,ap,mp,remaining));
        }
        return List.copyOf(results);
    }
    private static boolean dependsOnTarget(SkillFormula.Compiled formula,SkillDefinition definition) {
        for(String name:formula.variables()) {
            if(name.startsWith("target_")||name.equals("distance")||name.equals("height_difference")||name.equals("direction_multiplier"))return true;
            var attribute=definition.attributes().get(name);if(attribute!=null&&attribute.target())return true;
        }
        return false;
    }
    public static SkillPreview preview(ServerBattle battle,LivingEntity caster,ResourceLocation id,SkillTarget target) {
        try {
            Plan plan=plan(battle,caster,id,target);
            return new SkillPreview(true,"",plan.ap,plan.mp,tiles(plan),plan.context.affected().stream().mapToInt(LivingEntity::getId).toArray());
        } catch(RuntimeException failure) {return SkillPreview.rejected(message(failure));}
    }
    public static boolean cast(ServerBattle battle,LivingEntity caster,ResourceLocation id,SkillTarget requested) {
        Plan plan;
        try {plan=plan(battle,caster,id,requested);}catch(RuntimeException failure) {reject(battle,message(failure));return false;}
        var before=new SkillCastEvent.Before(plan.context,plan.ap,plan.mp);
        NeoForge.EVENT_BUS.post(before);
        if(before.isCanceled()){reject(battle,"技能释放已取消");return false;}
        SkillDefinition definition=plan.context.definition();
        // A listener may modify equipment/resources. Build a fresh plan before reserving anything.
        try {plan=plan(battle,caster,id,requested);}catch(RuntimeException failure){reject(battle,message(failure));return false;}
        if(!battle.spendActorAction(caster,plan.ap,definition.apRamp()))return false;
        if(!ManaResources.spend(caster,plan.mp)){battle.refundActorAction(caster,plan.ap,definition.apRamp());return false;}
        State state=state(battle);
        Map<ResourceLocation,Integer> cooldowns=state.cooldowns.computeIfAbsent(caster.getUUID(),ignored->new HashMap<>());
        Integer previous=cooldowns.put(id,state.turns.getOrDefault(caster.getUUID(),0)+plan.cooldown);
        boolean committed=false;
        DamageScope old=DAMAGE_SCOPE.get();
        DAMAGE_SCOPE.set(new DamageScope(caster,plan.context.affected().stream().map(LivingEntity::getUUID).collect(java.util.stream.Collectors.toUnmodifiableSet()),plan.multiplier));
        try {
            if(definition.damage().mode()==SkillDefinition.DamageMode.WEAPON) {
                LivingEntity target=plan.context.affected().getFirst();
                battle.faceActorTowards(caster,new GridPos(target.blockPosition().getX(),target.blockPosition().getY(),target.blockPosition().getZ()));
                caster.swing(InteractionHand.MAIN_HAND);
                boolean hit;
                try {
                    hit=caster instanceof ServerPlayer player?NativeAttackReceipt.run(caster,target,()->player.attack(target))
                            :caster instanceof Mob mob&&mob.doHurtTarget(target);
                } catch(RuntimeException failure) {committed=true;throw failure;}
                if(!hit) {
                    refund(battle,caster,plan,cooldowns,previous);
                    reject(battle,"攻击未生效，已退还技能资源");return false;
                }
                committed=true;
            }
            for(TargetAction action:plan.actions) {
                if(!action.actor.isAlive())continue;
                if(definition.damage().mode()==SkillDefinition.DamageMode.FORMULA&&action.amount>0) {
                    committed=true;action.actor.hurt(action.damage,action.amount);
                }
                for(PreparedEffect effect:action.effects) {
                    committed=true;
                    if(effect.effect.value().isInstantenous())effect.effect.value().applyInstantenousEffect(caster,caster,action.actor,effect.amplifier,1);
                    else if(effect.ticks>0)action.actor.addEffect(new MobEffectInstance(effect.effect,effect.ticks,effect.amplifier),caster);
                }
            }
            if(definition.action()!=null){committed=true;definition.action().apply(plan.context);}
            committed=true;
            battle.journal().record("skill",caster.getDisplayName().getString()+" 释放 "+definition.name()+" Lv."+plan.context.level()+" · AP "+plan.ap+" · MP "+format(plan.mp));
            NeoForge.EVENT_BUS.post(new SkillCastEvent.After(plan.context));
            if(!battle.finished()){battle.resolveExternalAction(caster);changed(battle,true);}
            return true;
        } catch(RuntimeException failure) {
            if(!committed)refund(battle,caster,plan,cooldowns,previous);
            Tactical.LOGGER.error("[Tactical] skill {} native action failed (committed={})",id,committed,failure);
            reject(battle,committed?"技能执行异常，已完成的效果保留":"技能未执行，资源已退还");
            if(!battle.finished()){battle.resolveExternalAction(caster);changed(battle,true);}
            return committed;
        } finally {if(old==null)DAMAGE_SCOPE.remove();else DAMAGE_SCOPE.set(old);}
    }
    private static void refund(ServerBattle battle,LivingEntity caster,Plan plan,Map<ResourceLocation,Integer> cooldowns,Integer previous) {
        battle.refundActorAction(caster,plan.ap,plan.context.definition().apRamp());ManaResources.add(caster,plan.mp);
        if(previous==null)cooldowns.remove(plan.context.definition().id());else cooldowns.put(plan.context.definition().id(),previous);
        changed(battle,!battle.finished());
    }
    private static Plan plan(ServerBattle battle,LivingEntity caster,ResourceLocation id,SkillTarget requested) {
        if(battle.finished()||!caster.isAlive()||!battle.isParticipant(caster))throw invalid("施法者不在活动战斗中");
        if(!battle.isActorTurn(caster))throw invalid("尚未轮到该单位");
        if(battle.isBusy())throw invalid("等待当前动作结算");
        SkillDefinition definition=SkillRegistry.get(id);if(definition==null)throw invalid("未知技能");
        Integer level=SkillSources.resolve(caster).get(id);if(level==null)throw invalid("当前装备或角色未提供该技能");
        if(cooldown(battle,caster,id)>0)throw invalid("技能冷却中");
        LivingEntity selected=requested.entity()==null?null:battle.participants().stream().filter(a->a.getUUID().equals(requested.entity())).findFirst().orElse(null);
        if(definition.aim()==SkillDefinition.Aim.SELF) {
            if(selected!=caster)throw invalid("该技能只能以自身为目标");
        } else if(definition.aim()==SkillDefinition.Aim.ENTITY) {
            if(selected==null||!selected.isAlive())throw invalid("需要有效参战实体目标");
        } else if(requested.ground()==null)throw invalid("需要地面目标");
        if(selected!=null&&!allowed(battle,caster,selected,definition.targets()))throw invalid("目标阵营不符合技能要求");
        Vec3 target=selected==null?new Vec3(requested.ground().x()+.5,requested.ground().y(),requested.ground().z()+.5):selected.position();
        GridPos targetCell=new GridPos((int)Math.floor(target.x),(int)Math.floor(target.y),(int)Math.floor(target.z));
        if(!battle.zone().contains(targetCell))throw invalid("目标超出战场");
        var values=SkillBindings.values(battle,caster,selected,level,definition.attributes());
        double range=positive(definition.range().evaluate(values),"施法距离"),min=positive(definition.minRange().evaluate(values),"最小距离");
        if(range>64||min>range)throw invalid("施法距离无效");
        double distance=Math.hypot(target.x-caster.getX(),target.z-caster.getZ());
        if(distance>range+1.0E-7||distance+1.0E-7<min||Math.abs(target.y-caster.getY())>64)throw invalid("目标不在技能施法距离内");
        ServerLevel world=(ServerLevel)caster.level();
        Vec3 targetSight=selected==null?target.add(0,.1,0):selected.getEyePosition();
        if(definition.lineOfSight()&&!visible(world,caster,targetSight))throw invalid("目标被障碍物遮挡");
        if(definition.requirement().evaluate(values)==0)throw invalid("不满足释放条件");
        Vec3 anchor=definition.anchor()==SkillDefinition.Anchor.CASTER?caster.position():target;
        float yaw=requested.ground()!=null?requested.yaw():yaw(caster,target);
        SkillArea region=definition.region().resolve(values).transformed(0,0,yaw);
        double low=definition.verticalMin().evaluate(values),high=definition.verticalMax().evaluate(values);
        if(!Double.isFinite(low)||!Double.isFinite(high)||low>high||low< -64||high>64)throw invalid("技能高度范围无效");
        List<LivingEntity> affected;
        if(definition.area()) {
            affected=battle.participants().stream().filter(LivingEntity::isAlive)
                    .filter(a->allowed(battle,caster,a,definition.targets()))
                    .filter(a->a.getY()>=anchor.y+low-1.0E-7&&a.getY()<=anchor.y+high+1.0E-7)
                    .filter(a->region.contains(a.getX()-anchor.x,a.getZ()-anchor.z))
                    .filter(a->!definition.lineOfSight()||visible(world,caster,a.getEyePosition()))
                    .distinct().sorted(Comparator.comparingInt(LivingEntity::getId)).toList();
        } else {
            if(selected==null)throw invalid("非范围技能需要实体目标");
            affected=List.of(selected);
        }
        if(affected.size()>256)throw invalid("技能影响目标过多");
        if(definition.damage().mode()==SkillDefinition.DamageMode.WEAPON) {
            if(caster.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem)
                throw invalid("原版武器技能需要近战武器");
            if(definition.area()||selected==null||affected.size()!=1)throw invalid("原版武器技能必须为单体");
            String failure=battle.previewWeaponAttack(caster,selected);if(!failure.isEmpty())throw invalid(failure);
        }
        var context=new SkillCastContext(battle,caster,definition,level,requested,affected);
        String failure=SkillConditions.failure(context);if(!failure.isEmpty())throw invalid(failure);
        if(definition.action()!=null) {String custom=definition.action().validate(context);if(custom!=null&&!custom.isBlank())throw invalid(custom);}
        int baseAp=integer(definition.apCost().evaluate(values),"AP"),ap=battle.quoteActorActionCost(caster,baseAp,definition.apRamp());
        double mp=positive(definition.mpCost().evaluate(values),"MP");int cooldown=integer(definition.cooldown().evaluate(values),"冷却");
        if(battle.actorAp(caster)<ap)throw invalid("AP 不足");
        if(ManaResources.current(caster)+1.0E-7<mp)throw invalid("MP 不足");
        double multiplier=definition.damage().mode()==SkillDefinition.DamageMode.WEAPON?positive(definition.damage().amount().evaluate(values),"技能倍率"):1;
        List<TargetAction> actions=new ArrayList<>();
        for(LivingEntity actor:affected) {
            var perTarget=SkillBindings.values(battle,caster,actor,level,definition.attributes());
            DamageSource source=null;float amount=0;
            if(definition.damage().mode()==SkillDefinition.DamageMode.FORMULA) {
                var type=world.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(ResourceKey.create(Registries.DAMAGE_TYPE,definition.damage().type()));
                if(type.isEmpty())throw invalid("未知伤害类型 "+definition.damage().type());
                source=new DamageSource(type.get(),caster);
                amount=(float)positive(definition.damage().amount().evaluate(perTarget),"伤害");
            }
            List<PreparedEffect> effects=new ArrayList<>();
            for(var effect:definition.effects()) {
                var type=world.registryAccess().registryOrThrow(Registries.MOB_EFFECT).getHolder(ResourceKey.create(Registries.MOB_EFFECT,effect.id()));
                if(type.isEmpty())throw invalid("未知效果 "+effect.id());
                int effectLevel=integer(effect.level().evaluate(perTarget),"效果等级");
                if(effectLevel<1||effectLevel>256)throw invalid("效果等级须为1..256");
                double duration=positive(effect.duration().evaluate(perTarget),"效果时长");
                if(effect.unit()==SkillDefinition.DurationUnit.TURNS)duration*=200;
                if(duration>MAX_VALUE)throw invalid("效果持续时间超出限制");
                effects.add(new PreparedEffect(type.get(),effectLevel-1,(int)Math.ceil(duration)));
            }
            actions.add(new TargetAction(actor,source,amount,List.copyOf(effects)));
        }
        return new Plan(context,ap,mp,cooldown,anchor,region,low,high,multiplier,List.copyOf(actions));
    }
    private static boolean allowed(ServerBattle battle,LivingEntity caster,LivingEntity target,Set<SkillDefinition.Relation> allowed) {
        if(target==caster)return allowed.contains(SkillDefinition.Relation.SELF);
        if(battle.sideOf(caster)==null||battle.sideOf(target)==null)return false;
        // One relation strategy decides ally/enemy so skill targeting cannot diverge from attacks.
        var relation=battle.relationOf(caster,target);
        if(relation==com.tactical.neoforge.battle.multi.BattleRelationPolicy.Relation.UNRELATED)return false;
        return allowed.contains(relation==com.tactical.neoforge.battle.multi.BattleRelationPolicy.Relation.ALLY
                ?SkillDefinition.Relation.ALLY:SkillDefinition.Relation.ENEMY);
    }
    private static boolean visible(ServerLevel world,LivingEntity actor,Vec3 target) {
        return world.clip(new ClipContext(actor.getEyePosition(),target,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()==HitResult.Type.MISS;
    }
    private static float yaw(LivingEntity actor,Vec3 target) {
        double x=target.x-actor.getX(),z=target.z-actor.getZ();
        return x*x+z*z<1.0E-12?actor.yBodyRot:(float)Math.toDegrees(Math.atan2(-x,z));
    }
    private static int[] tiles(Plan plan) {
        if(!plan.context.definition().area())return new int[0];
        var bounds=plan.region.bounds();List<Integer> tiles=new ArrayList<>();
        if(bounds.isEmpty())return new int[0];
        int y=(int)Math.floor(plan.anchor.y);
        for(int x=(int)Math.floor(plan.anchor.x+bounds.minX());x<=(int)Math.ceil(plan.anchor.x+bounds.maxX());x++)
            for(int z=(int)Math.floor(plan.anchor.z+bounds.minZ());z<=(int)Math.ceil(plan.anchor.z+bounds.maxZ());z++) {
                if(!plan.region.contains(x+.5-plan.anchor.x,z+.5-plan.anchor.z))continue;
                if(!plan.context.battle().zone().contains(new GridPos(x,y,z)))continue;
                if(tiles.size()>=4096*3)throw invalid("范围预览过大");
                tiles.add(x);tiles.add(y);tiles.add(z);
            }
        return tiles.stream().mapToInt(Integer::intValue).toArray();
    }
    private static int integer(double value,String name) {return (int)Math.ceil(positive(value,name));}
    private static double positive(double value,String name) {
        if(!Double.isFinite(value)||value<0||value>MAX_VALUE)throw invalid(name+"公式须为0.."+MAX_VALUE+"的有限值");
        return value;
    }
    private static IllegalArgumentException invalid(String reason) {return new IllegalArgumentException(reason);}
    private static String message(RuntimeException failure) {return failure.getMessage()==null?"技能参数无效":failure.getMessage();}
    private static String format(double value) {return String.format(Locale.ROOT,"%.1f",value);}
    private static void reject(ServerBattle battle,String reason) {if(!battle.finished())battle.journal().record("reject",reason);}
    /** Only explicit, validated targets in the active native skill scope can bypass allied damage protection. */
    public static boolean permitsFriendlyDamage(LivingEntity attacker,LivingEntity target) {
        DamageScope scope=DAMAGE_SCOPE.get();return scope!=null&&scope.caster==attacker&&scope.targets.contains(target.getUUID());
    }
    @SubscribeEvent public static void onDamage(TacticalDamageEvent event) {
        DamageScope scope=DAMAGE_SCOPE.get();
        if(scope!=null&&scope.caster==event.context().attacker()&&scope.targets.contains(event.context().target().getUUID()))
            event.multiply(TacticalDamageMultipliers.Stage.SKILL,scope.multiplier);
    }
    /** A non-area weapon skill does not leak vanilla sweeping damage onto unselected actors. */
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void incoming(LivingIncomingDamageEvent event) {
        DamageScope scope=DAMAGE_SCOPE.get();
        if(scope!=null&&event.getSource().getEntity()==scope.caster&&event.getSource().is(DamageTypes.PLAYER_ATTACK)
                &&!scope.targets.contains(event.getEntity().getUUID()))event.setCanceled(true);
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {STATES.clear();DAMAGE_SCOPE.remove();}
}
