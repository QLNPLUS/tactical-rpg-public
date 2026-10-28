package com.tactical.neoforge.battle;

import com.tactical.neoforge.battle.ai.*;
import com.tactical.neoforge.skill.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Native extension exercised by TeamSelfTest with -Dtactical.selftest=ai_skills. */
public final class AiSkillsSelfTest {
    private static final ResourceLocation BOLT=ResourceLocation.fromNamespaceAndPath("tactical","fixture_ai_bolt");
    private static int committed;
    private AiSkillsSelfTest() { }

    /** Registers through the same public APIs a third-party boss/personality integration uses. */
    public static void install() {
        committed=0;
        SkillRegistry.register(SkillDefinition.builder(BOLT).name("Native fixture bolt")
                .targets(SkillDefinition.Relation.ENEMY).costs("15","10").cooldown("1").range("20")
                .damage(ResourceLocation.withDefaultNamespace("magic"),"4")
                .action(context -> {
                    check(context.caster() instanceof Mob && context.affected().size()==1,"custom native action receives validated actor/target");
                    committed++;
                }).build());
        TacticalAiRegistry.register(ResourceLocation.withDefaultNamespace("cow"),EntityType.COW, ignored -> new TacticalMobAi() {
            @Override public AiAction planTurn(AiContext context) {
                return context.availableSkills().stream().anyMatch(skill -> skill.id().equals(BOLT) && skill.ready())
                        ? AiAction.skill(BOLT,SkillTarget.entity(context.target().getUUID())) : AiAction.endTurn();
            }
        });
    }

    public static void run(ServerBattle battle,Mob ordinary,Mob caster,LivingEntity hostile) throws Exception {
        grant(ordinary); grant(caster);
        begin(battle,ordinary);
        AiContext generic=context(battle,ordinary);
        check(generic.availableSkills().stream().anyMatch(s->s.id().equals(BOLT)),"native mob resolves persistent public skill source");
        check(new GenericMeleeAi().planTurn(generic).kind()!=AiAction.Kind.SKILL,"default native AI never automatically casts an owned skill");
        begin(battle,caster);
        ManaResources.add(caster,100);
        AiContext context=context(battle,caster);
        check(context.target()==hostile,"native registered adapter targets closest hostile");
        List<SkillAvailability> available=context.availableSkills();
        check(available.stream().anyMatch(s->s.id().equals(BOLT) && s.ready()),"registered boss adapter sees ready public skill");
        try { available.clear(); throw new AssertionError("mutable native skill list"); } catch(UnsupportedOperationException expected) { }
        int ap=battle.actorAp(caster); double mp=ManaResources.current(caster);
        check(!context.castSkill(ResourceLocation.fromNamespaceAndPath("tactical","missing_fixture"),SkillTarget.entity(hostile.getUUID())),"unknown skill intent rejected by parent runtime");
        check(!context.castSkill(BOLT,SkillTarget.entity(battle.player().getUUID())),"ENEMY-only skill cannot target allied owner");
        check(!context.castSkill(BOLT,SkillTarget.entity(java.util.UUID.randomUUID())),"nonparticipant target rejected");
        check(!context(battle,ordinary).castSkill(BOLT,SkillTarget.entity(hostile.getUUID())),"off-turn caster rejected");
        unchanged(battle,caster,ap,mp,0,"invalid skill intents have no AP/MP/effect cost");
        ManaResources.spend(caster,ManaResources.current(caster));
        check(!context.castSkill(BOLT,SkillTarget.entity(hostile.getUUID())) && battle.actorAp(caster)==ap && committed==0,"insufficient native mana rejects with no AP/effect commit");
        ManaResources.add(caster,mp);
        int quoted=available.stream().filter(s->s.id().equals(BOLT)).findFirst().orElseThrow().apCost();
        float hp=hostile.getHealth(),ownerHp=battle.player().getHealth();
        BattleAiSession session=(BattleAiSession)field(battle,"ai").get(battle);
        AiAction action=session.planTurn(context);
        check(action.kind()==AiAction.Kind.SKILL && action.skill().equals(BOLT)
                && action.skillTarget().entity().equals(hostile.getUUID()),"native adapter returns a deferred entity-target skill intent");
        int revision=field(battle,"actionCount").getInt(battle);
        Object state=state(battle,caster);
        var resolve=ServerBattle.class.getDeclaredMethod("resolveAiAction",state.getClass(),AiAction.class);
        resolve.setAccessible(true);resolve.invoke(battle,state,action);
        check(hostile.getHealth()<hp && battle.player().getHealth()==ownerHp,"actual native skill damages only selected hostile");
        unchanged(battle,caster,ap-quoted,mp-10,1,"skill intent resolves AP and MP exactly once");
        check(field(battle,"actionCount").getInt(battle)==revision+1,"skill resolution increments global action exactly once");
        // Replay in the same own turn without calling beginTurn: neither an adapter nor a duplicate intent can pay twice.
        select(battle,caster);
        check(!context(battle,caster).castSkill(BOLT,SkillTarget.entity(hostile.getUUID())),"one successful skill per native own turn");
        resolve.invoke(battle,state,action);
        unchanged(battle,caster,ap-quoted,mp-10,1,"replayed skill intent commits no additional resource or effect");
        check(field(battle,"actionCount").getInt(battle)==revision+1,"rejected replay does not count an action");
        check(TacticalSkills.cooldown(battle,caster,BOLT)==1,"successful native skill starts cooldown1");
        begin(battle,battle.player());
        check(TacticalSkills.cooldown(battle,caster,BOLT)==1,"another actor's beginTurn never decrements caster cooldown");
        begin(battle,caster);
        check(TacticalSkills.cooldown(battle,caster,BOLT)==0,"native own-turn callback decrements cooldown exactly once");
        context=context(battle,caster);
        check(context.availableSkills().stream().anyMatch(s->s.id().equals(BOLT)&&s.ready()),"cooldown1 becomes ready at next actual own beginTurn");
        int secondAp=battle.actorAp(caster);double secondMp=ManaResources.current(caster);
        check(context.castSkill(BOLT,SkillTarget.entity(hostile.getUUID())),"next native own turn clears one-skill guard");
        unchanged(battle,caster,secondAp-15,secondMp-10,2,"next own-turn skill uses fresh paid-action ramp and one fixed mana cost");
        begin(battle,battle.player());
    }

    private static void grant(LivingEntity actor) {
        CompoundTag skill=new CompoundTag();skill.putString("id",BOLT.toString());skill.putInt("level",1);
        ListTag skills=new ListTag();skills.add(skill);
        CompoundTag tactical=actor.getPersistentData().getCompound("tactical");tactical.put("skills",skills);
        actor.getPersistentData().put("tactical",tactical);
    }
    private static void unchanged(ServerBattle battle,Mob caster,int ap,double mp,int count,String message) {
        check(battle.actorAp(caster)==ap && Math.abs(ManaResources.current(caster)-mp)<1e-6 && committed==count,message);
    }
    private static Field field(Object object,String name) throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f; }
    private static Object state(ServerBattle battle,Mob actor) throws Exception { return ((Map<?,?>)field(battle,"enemies").get(battle)).get(actor.getUUID()); }
    private static AiContext context(ServerBattle battle,Mob actor) throws Exception {
        Object state=state(battle,actor);var method=ServerBattle.class.getDeclaredMethod("aiContext",state.getClass());method.setAccessible(true);return (AiContext)method.invoke(battle,state);
    }
    private static void select(ServerBattle battle,LivingEntity actor) throws Exception {
        Object queue=field(battle,"queue").get(battle);field(queue,"current").set(queue,actor.getUUID().toString());
    }
    private static void begin(ServerBattle battle,LivingEntity actor) throws Exception {
        select(battle,actor);var method=ServerBattle.class.getDeclaredMethod("beginTurn",String.class);method.setAccessible(true);method.invoke(battle,actor.getUUID().toString());
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
