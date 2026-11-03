package com.tactical.neoforge.skill;

import com.tactical.core.grid.GridPos;
import com.tactical.core.skill.SkillArea;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
            int level=grant.getValue(),ap=0,remaining=cooldown(battle,actor,definition.id());double mp=0;
            Component reason=SkillText.none();
            try {
                var values=SkillBindings.values(battle,actor,null,level,definition.attributes());
                boolean needsTarget=dependsOnTarget(definition.apCost(),definition)||dependsOnTarget(definition.mpCost(),definition);
                if(!needsTarget) {
                    ap=battle.quoteActorActionCost(actor,integer(definition.apCost().evaluate(values),SkillText.KEY_FIELD_AP),definition.apRamp());
                    mp=positive(definition.mpCost().evaluate(values),SkillText.KEY_FIELD_MP);
                }
                if(!battle.isActorTurn(actor))reason=SkillText.translatable(SkillText.KEY_REASON_NOT_MY_TURN);
                else if(battle.isBusy())reason=SkillText.translatable(SkillText.KEY_REASON_BUSY);
                else if(remaining>0)reason=SkillText.translatable(SkillText.KEY_REASON_COOLDOWN,Integer.toString(remaining));
                else if(!needsTarget&&battle.actorAp(actor)<ap)reason=SkillText.translatable(SkillText.KEY_REASON_AP);
                else if(!needsTarget&&ManaResources.current(actor)+1.0E-7<mp)reason=SkillText.translatable(SkillText.KEY_REASON_MP);
                else if(!dependsOnTarget(definition.requirement(),definition)&&definition.requirement().evaluate(values)==0)reason=SkillText.translatable(SkillText.KEY_REASON_REQUIREMENT);
                else reason=SkillConditions.failureComponent(new SkillCastContext(battle,actor,definition,level,SkillTarget.self(actor),List.of()),false);
            } catch(RuntimeException failure) {reason=reason(failure,definition.id(),"available");}
            // Readiness is this producer's own outcome, never "the reason text resolved to blank".
            results.add(new SkillAvailability(definition.id(),level,SkillText.isEmpty(reason),reason,ap,mp,remaining));
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
            return new SkillPreview(true,SkillText.none(),plan.ap,plan.mp,tiles(plan),plan.context.affected().stream().mapToInt(LivingEntity::getId).toArray());
        } catch(RuntimeException failure) {return SkillPreview.rejected(reason(failure,id,"preview"));}
    }
    public static boolean cast(ServerBattle battle,LivingEntity caster,ResourceLocation id,SkillTarget requested) {
        Plan plan;
        try {plan=plan(battle,caster,id,requested);}catch(RuntimeException failure) {reject(battle,reason(failure,id,"cast"));return false;}
        var before=new SkillCastEvent.Before(plan.context,plan.ap,plan.mp);
        NeoForge.EVENT_BUS.post(before);
        if(before.isCanceled()){reject(battle,SkillText.translatable(SkillText.KEY_CAST_CANCELLED));return false;}
        SkillDefinition definition=plan.context.definition();
        // A listener may modify equipment/resources. Build a fresh plan before reserving anything.
        try {plan=plan(battle,caster,id,requested);}catch(RuntimeException failure){reject(battle,reason(failure,id,"cast"));return false;}
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
                    reject(battle,SkillText.translatable(SkillText.KEY_CAST_NO_EFFECT));return false;
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
            // The caster keeps the already-accepted name policy: a player-given name stays text, an
            // unnamed vanilla mob keeps its translation key, and the skill name is the definition's own
            // display structure.
            battle.journal().record("skill",SkillText.translatable(SkillText.KEY_CAST_LOG,BattleText.name(caster),
                    definition.displayName(),Integer.toString(plan.context.level()),Integer.toString(plan.ap),format(plan.mp)));
            NeoForge.EVENT_BUS.post(new SkillCastEvent.After(plan.context));
            if(!battle.finished()){battle.resolveExternalAction(caster);changed(battle,true);}
            return true;
        } catch(RuntimeException failure) {
            if(!committed)refund(battle,caster,plan,cooldowns,previous);
            // The real cause belongs to the server log; the player reads a stable, localised outcome.
            Tactical.LOGGER.error("[Tactical] skill {} native action failed (committed={})",id,committed,failure);
            reject(battle,SkillText.translatable(committed?SkillText.KEY_CAST_FAILED_COMMITTED:SkillText.KEY_CAST_FAILED_REFUNDED));
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
        if(battle.finished()||!caster.isAlive()||!battle.isParticipant(caster))throw key(SkillText.KEY_REJECT_NOT_IN_BATTLE);
        if(!battle.isActorTurn(caster))throw key(SkillText.KEY_REASON_NOT_MY_TURN);
        if(battle.isBusy())throw key(SkillText.KEY_REASON_BUSY);
        SkillDefinition definition=SkillRegistry.get(id);if(definition==null)throw key(SkillText.KEY_REJECT_UNKNOWN_SKILL);
        Integer level=SkillSources.resolve(caster).get(id);if(level==null)throw key(SkillText.KEY_REJECT_NOT_GRANTED);
        if(cooldown(battle,caster,id)>0)throw key(SkillText.KEY_REJECT_COOLDOWN);
        LivingEntity selected=requested.entity()==null?null:battle.participants().stream().filter(a->a.getUUID().equals(requested.entity())).findFirst().orElse(null);
        if(definition.aim()==SkillDefinition.Aim.SELF) {
            if(selected!=caster)throw key(SkillText.KEY_REJECT_SELF_TARGET);
        } else if(definition.aim()==SkillDefinition.Aim.ENTITY) {
            if(selected==null||!selected.isAlive())throw key(SkillText.KEY_REJECT_ENTITY_TARGET);
        } else if(requested.ground()==null)throw key(SkillText.KEY_REJECT_GROUND_TARGET);
        if(selected!=null&&!allowed(battle,caster,selected,definition.targets()))throw key(SkillText.KEY_REJECT_RELATION);
        Vec3 target=selected==null?new Vec3(requested.ground().x()+.5,requested.ground().y(),requested.ground().z()+.5):selected.position();
        GridPos targetCell=new GridPos((int)Math.floor(target.x),(int)Math.floor(target.y),(int)Math.floor(target.z));
        if(!battle.zone().contains(targetCell))throw key(SkillText.KEY_REJECT_OUTSIDE_ZONE);
        var values=SkillBindings.values(battle,caster,selected,level,definition.attributes());
        double range=positive(definition.range().evaluate(values),SkillText.KEY_FIELD_RANGE),min=positive(definition.minRange().evaluate(values),SkillText.KEY_FIELD_MIN_RANGE);
        if(range>64||min>range)throw key(SkillText.KEY_REJECT_RANGE);
        double distance=Math.hypot(target.x-caster.getX(),target.z-caster.getZ());
        if(distance>range+1.0E-7||distance+1.0E-7<min||Math.abs(target.y-caster.getY())>64)throw key(SkillText.KEY_REJECT_DISTANCE);
        ServerLevel world=(ServerLevel)caster.level();
        Vec3 targetSight=selected==null?target.add(0,.1,0):selected.getEyePosition();
        if(definition.lineOfSight()&&!visible(world,caster,targetSight))throw key(SkillText.KEY_REJECT_LINE_OF_SIGHT);
        if(definition.requirement().evaluate(values)==0)throw key(SkillText.KEY_REASON_REQUIREMENT);
        Vec3 anchor=definition.anchor()==SkillDefinition.Anchor.CASTER?caster.position():target;
        float yaw=requested.ground()!=null?requested.yaw():yaw(caster,target);
        SkillArea region=definition.region().resolve(values).transformed(0,0,yaw);
        double low=definition.verticalMin().evaluate(values),high=definition.verticalMax().evaluate(values);
        if(!Double.isFinite(low)||!Double.isFinite(high)||low>high||low< -64||high>64)throw key(SkillText.KEY_REJECT_VERTICAL);
        List<LivingEntity> affected;
        if(definition.area()) {
            affected=battle.participants().stream().filter(LivingEntity::isAlive)
                    .filter(a->allowed(battle,caster,a,definition.targets()))
                    .filter(a->a.getY()>=anchor.y+low-1.0E-7&&a.getY()<=anchor.y+high+1.0E-7)
                    .filter(a->region.contains(a.getX()-anchor.x,a.getZ()-anchor.z))
                    .filter(a->!definition.lineOfSight()||visible(world,caster,a.getEyePosition()))
                    .distinct().sorted(Comparator.comparingInt(LivingEntity::getId)).toList();
        } else {
            if(selected==null)throw key(SkillText.KEY_REJECT_NON_AREA_ENTITY);
            affected=List.of(selected);
        }
        if(affected.size()>256)throw key(SkillText.KEY_REJECT_TOO_MANY_TARGETS);
        if(definition.damage().mode()==SkillDefinition.DamageMode.WEAPON) {
            if(caster.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem)
                throw key(SkillText.KEY_REJECT_WEAPON_RANGED);
            if(definition.area()||selected==null||affected.size()!=1)throw key(SkillText.KEY_REJECT_WEAPON_SINGLE);
            Component failure=battle.previewWeaponAttack(caster,selected);if(!SkillText.isEmpty(failure))throw invalid(failure);
        }
        var context=new SkillCastContext(battle,caster,definition,level,requested,affected);
        Component conditionFailure=SkillConditions.failureComponent(context,true);if(!SkillText.isEmpty(conditionFailure))throw invalid(conditionFailure);
        if(definition.action()!=null) {
            // A custom validator answers with finished text; it is displayed literally, never looked up.
            String custom=definition.action().validate(context);
            if(custom!=null&&!custom.isBlank())throw invalid(SkillText.literalText(custom));
        }
        int baseAp=integer(definition.apCost().evaluate(values),SkillText.KEY_FIELD_AP),ap=battle.quoteActorActionCost(caster,baseAp,definition.apRamp());
        double mp=positive(definition.mpCost().evaluate(values),SkillText.KEY_FIELD_MP);int cooldown=integer(definition.cooldown().evaluate(values),SkillText.KEY_FIELD_COOLDOWN);
        if(battle.actorAp(caster)<ap)throw key(SkillText.KEY_REASON_AP);
        if(ManaResources.current(caster)+1.0E-7<mp)throw key(SkillText.KEY_REASON_MP);
        // Character growth reaches core skill damage exactly once, here, and nowhere else: the same factor is
        // applied to the weapon multiplier below and to each formula amount further down, so a preview and the
        // resolved hit cannot disagree, and a caster who invested nothing keeps factor exactly 1 (old results).
        double skillPower=grow(caster,"plan");
        double multiplier=definition.damage().mode()==SkillDefinition.DamageMode.WEAPON?positive(definition.damage().amount().evaluate(values),SkillText.KEY_FIELD_MULTIPLIER):1;
        // A weapon skill's own multiplier and the character factor are carried together into the single skill
        // stage of the damage event, so the native hit is scaled once.
        if(definition.damage().mode()==SkillDefinition.DamageMode.WEAPON)multiplier=positive(multiplier*skillPower,SkillText.KEY_FIELD_MULTIPLIER);
        List<TargetAction> actions=new ArrayList<>();
        for(LivingEntity actor:affected) {
            var perTarget=SkillBindings.values(battle,caster,actor,level,definition.attributes());
            DamageSource source=null;float amount=0;
            if(definition.damage().mode()==SkillDefinition.DamageMode.FORMULA) {
                var type=world.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(ResourceKey.create(Registries.DAMAGE_TYPE,definition.damage().type()));
                if(type.isEmpty())throw key(SkillText.KEY_REJECT_UNKNOWN_DAMAGE_TYPE,definition.damage().type().toString());
                source=new DamageSource(type.get(),caster);
                amount=(float)positive(positive(definition.damage().amount().evaluate(perTarget),SkillText.KEY_FIELD_DAMAGE)*skillPower,SkillText.KEY_FIELD_DAMAGE);
            }
            List<PreparedEffect> effects=new ArrayList<>();
            for(var effect:definition.effects()) {
                var type=world.registryAccess().registryOrThrow(Registries.MOB_EFFECT).getHolder(ResourceKey.create(Registries.MOB_EFFECT,effect.id()));
                if(type.isEmpty())throw key(SkillText.KEY_REJECT_UNKNOWN_EFFECT,effect.id().toString());
                int effectLevel=integer(effect.level().evaluate(perTarget),SkillText.KEY_FIELD_EFFECT_LEVEL);
                if(effectLevel<1||effectLevel>256)throw key(SkillText.KEY_REJECT_EFFECT_LEVEL);
                double duration=positive(effect.duration().evaluate(perTarget),SkillText.KEY_FIELD_EFFECT_DURATION);
                if(effect.unit()==SkillDefinition.DurationUnit.TURNS)duration*=200;
                if(duration>MAX_VALUE)throw key(SkillText.KEY_REJECT_EFFECT_DURATION);
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
                if(tiles.size()>=4096*3)throw key(SkillText.KEY_REJECT_PREVIEW_TOO_LARGE);
                tiles.add(x);tiles.add(y);tiles.add(z);
            }
        return tiles.stream().mapToInt(Integer::intValue).toArray();
    }
    private static int integer(double value,String fieldKey) {return (int)Math.ceil(positive(value,fieldKey));}
    /**
     * The character skill-damage factor of one caster, or exactly 1 when character growth is unavailable.
     *
     * <p>It is read once per plan so the preview and the resolution of one cast use one number. A broken or
     * absent attribute falls back to 1 instead of refusing a skill: growth is an amplifier, never a
     * precondition of casting.</p>
     */
    private static double grow(LivingEntity caster,String phase) {
        try {
            return com.tactical.neoforge.progression.ProgressionEffects.skillPower(caster);
        } catch(RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] skill power unreadable during {}; using 1",phase,failure);
            return 1.0D;
        }
    }
    private static double positive(double value,String fieldKey) {
        if(!Double.isFinite(value)||value<0||value>MAX_VALUE)
            throw new Rejection(SkillText.translatable(SkillText.KEY_REJECT_FORMULA,
                    SkillText.translatable(fieldKey),Integer.toString(MAX_VALUE)));
        return value;
    }

    /** A refusal this class itself decided; its text is already display structure. */
    private static final class Rejection extends IllegalArgumentException {
        private final Component text;
        private Rejection(Component text) {super("skill refused");this.text=text;}

    }
    private static Rejection invalid(Component text) {return new Rejection(text);}
    private static Rejection key(String key,Object... args) {
        return new Rejection(args.length==0?SkillText.translatable(key):SkillText.translatable(key,args));
    }

    /**
     * The player-facing reason for a failure, and the only place where an unexpected exception is turned
     * into wording.
     *
     * <p>An expected refusal carries its own structure. Anything else - a broken third-party condition,
     * an exhausted native lookup - is a defect of the server side, so the player reads a stable generic
     * sentence and the real exception goes to the server log, bounded per registry revision so a
     * high-frequency preview cannot flood it.</p>
     */
    private static Component reason(RuntimeException failure,ResourceLocation id,String phase) {
        if(failure instanceof Rejection rejection)return rejection.text;
        reportUnexpected(id,phase,failure);
        return SkillText.translatable(SkillText.KEY_REJECT_UNEXPECTED);
    }
    private static final Map<String,Long> REPORTED=new HashMap<>();
    private static void reportUnexpected(ResourceLocation id,String phase,RuntimeException failure) {
        long revision=SkillRegistry.revision();
        String report=revision+"|"+phase+"|"+(id==null?"?":id.toString());
        boolean first;
        synchronized(REPORTED) {
            // Bounded: one line per (registry revision, skill, phase), and never an unbounded map.
            if(REPORTED.size()>1024)REPORTED.clear();
            first=REPORTED.putIfAbsent(report,revision)==null;
        }
        if(first)Tactical.LOGGER.error("[Tactical] skill {} failed unexpectedly during {} (registry revision {}); "
                +"identical failures stay suppressed for this revision",id,phase,revision,failure);
    }
    private static String format(double value) {return String.format(Locale.ROOT,"%.1f",value);}
    private static void reject(ServerBattle battle,Component reason) {if(!battle.finished())battle.journal().record("reject",reason);}
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
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {STATES.clear();DAMAGE_SCOPE.remove();synchronized(REPORTED){REPORTED.clear();}}
}
