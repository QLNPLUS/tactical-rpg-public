package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.combat.TurnScheduler;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.ai.*;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.lang.reflect.Field;
import java.util.*;

/** Real loaded-server fixture: -Dtactical.selftest=teams. No mocked entities or interface-only assertions. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class TeamSelfTest {
    private static boolean enabled, configured, oldFriendlyFire;
    private static int ticks, stage, oldCap, stageAt;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Zombie first, second;
    private static Skeleton archer;
    private static ServerBattle battle;
    private static Wire wire;
    private static final List<Mob> mobs = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();
    private static final List<Mob> friends = new ArrayList<>();
    private TeamSelfTest() { }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        enabled = "teams".equals(System.getProperty("tactical.selftest"))
                || "ai_skills".equals(System.getProperty("tactical.selftest"));
        ticks = stage = 0;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            check(++ticks < 500, "native fixture deadline");
            if (ticks == 20) setup(event.getServer());
            if (ticks < 50) return;
            if (stage == 0) {
                if (!level.getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(16)).containsAll(List.of(first, second))) return;
                participationAndMelee();
                stage = 1; stageAt = ticks;
            } else if (stage == 1) {
                if (battle.isBusy() || ticks - stageAt < 20) return;
                rangedAndFlight();
                stage = 2; stageAt = ticks;
            } else if (stage == 2) {
                if (battle.isBusy() || ticks - stageAt < 20) return;
                if ("ai_skills".equals(System.getProperty("tactical.selftest"))) {
                    var caster=mob(EntityType.COW,-5,0,-4,true);
                    check(battle.tryAddFriendly(caster),"registered native skill actor recruited"); friends.add(caster);
                    AiSkillsSelfTest.run(battle,friends.getFirst(),caster,first);
                    Tactical.LOGGER.info("[Tactical] AI SKILLS SELFTEST PASSED: native registered adapter, real skill definitions/NBT source/targets, read-only availability, rejected unknown/ally/outsider/off-turn/low-MP intents, one authoritative AP/MP/effect commit, replay guard, cooldown own-turn isolation and recovery");
                }
                capsAndCleanup();
                Tactical.LOGGER.info("[Tactical] TEAM SELFTEST PASSED: native explicit friend admission, safe deployment, owner/hostile sides, immutable roster, independent AP/queue, melee/bow/Phantom opponent isolation, allied owner damage/target prevention, hostile-only caps/refill/victory, friendly16, original NoAi restore and cleanup");
                finish(event.getServer());
            }
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] TEAM SELFTEST FAILED stage={} tick={}", stage, ticks, failure);
            try {
                finish(event.getServer());
            } catch (Throwable ignored) {
                // A broken teardown must not hide the original failure.
            }
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset(); BattleManager.setAutoTrigger(false);
        if ("ai_skills".equals(System.getProperty("tactical.selftest"))) AiSkillsSelfTest.install();
        oldCap = TacticalConfig.INSTANCE.participantCap.get();
        oldFriendlyFire = TacticalConfig.INSTANCE.friendlyFire.get(); configured = true;
        TacticalConfig.INSTANCE.participantCap.set(2);
        TacticalConfig.INSTANCE.friendlyFire.set(false);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight()-12, spawn.getY()+20), spawn.getZ());
        for (int x=(origin.getX()-24)>>4; x<=(origin.getX()+24)>>4; x++)
            for (int z=(origin.getZ()-24)>>4; z<=(origin.getZ()+24)>>4; z++)
                if (level.setChunkForced(x,z,true)) chunks.add(new BlockPos(x,0,z));
        for(int x=-24;x<=24;x++) for(int z=-24;z<=24;z++) {
            level.setBlock(origin.offset(x,-1,z), Blocks.STONE.defaultBlockState(),3);
            for(int y=0;y<9;y++) level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TeamOwner");
        owner = new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        wire = new Wire(); wire.channel = new EmbeddedChannel(wire);
        for(String id:List.of("battle_state","battle_log","player_step","creeper_telegraph","move_choice_offer","skill_state"))
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,id));
        new ServerGamePacketListenerImpl(server,wire,owner,CommonListenerCookie.createInitial(profile,false));
        owner.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        owner.setInvulnerable(true); level.addNewPlayer(owner);
        first = mob(EntityType.ZOMBIE,5,0,0,true);
        second = mob(EntityType.ZOMBIE,10,0,0,false);
    }

    private static void participationAndMelee() throws Exception {
        check(BattleManager.start(owner,true),"real encounter starts"); battle = BattleManager.current();
        check(battle.sideOf(owner)==BattleSide.PLAYER && battle.sideOf(first)==BattleSide.HOSTILE,"native side assignment");
        Zombie ally = mob(EntityType.ZOMBIE,5,0,0,false);
        check(BattleParticipation.tryAddFriendly(ally),"public API recruits native hostile-type mob as friendly even at hostile cap");
        check(!cell(ally).equals(cell(first)) && !cell(ally).equals(battle.playerCell())
                && BattleDeployment.isStandable(level, ally, ally.position()),"friendly deployment resolves an occupied cell to safe native space");
        friends.add(ally);
        check(!battle.tryAddFriendly(ally) && !battle.tryAbsorb(ally),"duplicate admission cannot duplicate queue or flip side");
        check(battle.enemyCount()==2 && battle.participants().size()==4 && battle.friendlyParticipants().equals(List.of(ally)),"friend counted only in total roster");
        try { battle.participants().clear(); throw new AssertionError("mutable roster"); } catch(UnsupportedOperationException expected) { }
        LivingEntity allyOpponent = battle.primaryOpponent(ally);
        LivingEntity firstOpponent = battle.primaryOpponent(first);
        check(allyOpponent==first && firstOpponent==owner,
                "native selected opponents isolate sides: ally@" + cell(ally) + "->" + describe(allyOpponent)
                + " first@" + cell(first) + "->" + describe(firstOpponent)
                + " second@" + cell(second) + " owner@" + battle.playerCell()
                + " allySide=" + battle.sideOf(ally) + " firstSide=" + battle.sideOf(first));
        AiContext context = context(ally);
        check(context.target()==first && context.targetCell().equals(context.playerCell()),"legacy target-cell alias points at hostile");
        check(context.pathToPlayer().stream().noneMatch(battle.playerCell()::equals),"friendly native path avoids owner's occupied cell");
        check(context.availableSkills().isEmpty(),"default native mob receives no unowned magic skills");
        begin(ally);
        int before=battle.actorAp(ally), ownerAp=battle.actorAp(owner);
        first.setInvulnerable(false); first.invulnerableTime=0;
        float hp=first.getHealth(), ownerHp=owner.getHealth();
        context(ally).attackPlayer();
        check(first.getHealth()<hp && owner.getHealth()==ownerHp && battle.actorAp(ally)<before,"friendly native melee damages and charges only hostile target");
        check(battle.actorAp(owner)==ownerAp,"friendly AP is independent of owner AP");
        check(!battle.spendActorAction(owner,1,true),"off-turn public spend rejected");
        int quote=battle.quoteActorActionCost(ally,10,true);
        check(battle.spendActorAction(ally,quote,true),"active friendly spends quoted action");
        battle.refundActorAction(ally,quote,true);
        check(battle.quoteActorActionCost(ally,10,true)==quote,"cancelled action refunds one shared ramp count");
        int paid=battle.actorAp(ally); battle.refundActorAction(ally,Integer.MAX_VALUE,false);
        check(battle.actorAp(ally)==TacticalAttributes.maxAp(ally) && paid<=battle.actorAp(ally),"refund caps without overflow");
        ally.setTarget(owner);
        check(ally.getTarget()!=owner,"native target event refuses allied owner");
        owner.setInvulnerable(false); owner.invulnerableTime=0; ownerHp=owner.getHealth();
        ally.doHurtTarget(owner);
        check(owner.getHealth()==ownerHp,"actual native friendly attack cannot hurt owner");
        TacticalConfig.INSTANCE.friendlyFire.set(true); owner.setInvulnerable(false); owner.invulnerableTime=0; owner.hurtTime=0; owner.hurtDuration=0;
        clearSpawnInvulnerability(owner);
        owner.hurt(ally.damageSources().mobAttack(ally), 3.0F);
        check(owner.getHealth()<ownerHp,"configured friendly fire allows native allied damage");
        TacticalConfig.INSTANCE.friendlyFire.set(false); owner.setHealth(ownerHp); owner.setInvulnerable(true);
        begin(owner);
        int ap=battle.currentAp(); battle.tryAttack(ally.getUUID());
        check(battle.currentAp()==ap && !battle.previewWeaponAttack(owner,ally).isEmpty(),"owner native intent and preview reject friend without cost");
        int[] ids=wire.last().turnEntityIds();
        check(Arrays.stream(ids).filter(id->id==ally.getId()).count()==1,"friendly appears in actual native turn snapshot exactly once");
        archer = mob(EntityType.SKELETON,-4,0,3,true);
        archer.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BOW));
        check(battle.tryAddFriendly(archer),"native bow friendly admitted"); friends.add(archer);
        begin(archer);
        check(context(archer).target()==first && context(archer).canRangedAttack(context(archer).actorCell()),"friendly ballistics validate hostile opponent");
        int bowAp=battle.actorAp(archer), bowCost=context(archer).rangedCost(); context(archer).rangedAttackPlayer();
        check(battle.pendingProjectiles()>0 && battle.actorAp(archer)==bowAp-bowCost,
                "native friendly bow fires and pays its quoted first-action price once");
        // The capture's real projectile lane must carry a hostile target, not the allied owner.
        check(archer.getTarget()==first,"native ranged hook targets hostile");
    }

    private static void rangedAndFlight() throws Exception {
        Phantom phantom=mob(EntityType.PHANTOM,5,4,3,true); phantom.setNoGravity(true);
        phantom.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.3);
        check(battle.tryAddFriendly(phantom),"native aerial friendly admitted"); friends.add(phantom);
        begin(phantom);
        AiContext context=context(phantom);
        AiAction action=new PhantomTacticalAi().planTurn(context);
        check(context.target()==first && context.targetCell().equals(cell(first)),"native phantom targets closest hostile cell");
        check(action.kind()==AiAction.Kind.DIVE && BattleAiSession.validDive(context,action),"native friendly Phantom validates complete hostile dive");
        GridPos strike=action.path().get(action.attackIndex());
        check(context.inMeleeRange(strike,cell(first)) && !context.inMeleeRange(strike,battle.playerCell()),"Phantom attack marker is near hostile and away from owner");
        Zombie dead=mob(EntityType.ZOMBIE,-6,0,-5,false);
        check(battle.tryAddFriendly(dead),"extra native friend recruited");
        dead.setHealth(0); dead.discard(); battle.tick();
        check(!battle.isParticipant(dead) && !dead.isNoAi() && !battle.finished(),"dead friendly releases original AI and never triggers victory");
        begin(owner);
    }

    private static void capsAndCleanup() throws Exception {
        for(int i=friends.size();i<BattleParticipation.MAX_FRIENDLIES;i++) {
            Zombie ally=mob(EntityType.ZOMBIE,-12+i%6*2,0,-12+i/6*2,i%2==0);
            check(battle.tryAddFriendly(ally),"friendly slot "+i+" independent of full enemy cap"); friends.add(ally);
        }
        Zombie overflow=mob(EntityType.ZOMBIE,-16,0,-16,true);
        Vec3 position=overflow.position();
        check(!battle.tryAddFriendly(overflow) && overflow.position().equals(position) && overflow.isNoAi(),"friendly17 rejected without changing native entity");
        check(battle.enemyCount()==2 && battle.friendlyParticipants().size()==16,"hostile and friendly limits independent");
        Zombie reinforcement=mob(EntityType.ZOMBIE,14,0,2,false);
        check(!battle.tryAbsorb(reinforcement),"friend slots cannot evade hostile cap");
        first.setHealth(0); first.discard(); battle.tick();
        check(!battle.finished() && battle.tryAbsorb(reinforcement) && battle.enemyCount()==2,"hostile vacancy admits reinforcement despite16 friends");
        second.setHealth(0); second.discard(); reinforcement.setHealth(0); reinforcement.discard();
        battle.tick();
        check(battle.finished() && battle.participants().isEmpty() && battle.friendlyParticipants().isEmpty(),"last hostile death wins despite16 surviving friends and clears roster");
        for(Mob ally:friends) {
            boolean expected=(boolean)ally.getPersistentData().getBoolean("team_fixture_original_no_ai");
            check(ally.isNoAi()==expected && ally.getTarget()==null && !battle.isParticipant(ally),"finish restores each original native NoAi and releases target");
        }
        int exits=wire.inactiveCount(); battle.finish("duplicate");
        check(wire.inactiveCount()==exits,"finish is idempotent");
    }

    private static <T extends Mob> T mob(EntityType<T> type,int x,int y,int z,boolean noAi) {
        T mob=type.create(level); check(mob!=null,"actual mob created");
        mob.setPos(origin.getX()+x+.5,origin.getY()+y,origin.getZ()+z+.5);
        mob.setNoAi(noAi); mob.getPersistentData().putBoolean("team_fixture_original_no_ai",noAi);
        mob.setPersistenceRequired(); mob.setInvulnerable(true);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); mob.setHealth(1000);
        if(mob.getAttribute(Attributes.MOVEMENT_SPEED)!=null)mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        check(level.addFreshEntity(mob),"actual mob registered"); mobs.add(mob); return mob;
    }
    private static GridPos cell(Entity entity) { return new GridPos(entity.getBlockX(),entity.getBlockY(),entity.getBlockZ()); }

    private static String describe(Entity entity) {
        return entity == null ? "null" : entity.getUUID() + "@" + cell(entity);
    }
    private static Field field(Object object,String name) throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f; }
    private static Object state(Mob actor) throws Exception { return ((Map<?,?>)field(battle,"enemies").get(battle)).get(actor.getUUID()); }
    private static AiContext context(Mob actor) throws Exception {
        Object state=state(actor); var method=ServerBattle.class.getDeclaredMethod("aiContext",state.getClass());method.setAccessible(true);return (AiContext)method.invoke(battle,state);
    }
    private static void begin(LivingEntity actor) throws Exception {
        TurnScheduler queue=(TurnScheduler)field(battle,"queue").get(battle); field(queue,"current").set(queue,actor.getUUID().toString());
        var method=ServerBattle.class.getDeclaredMethod("beginTurn",String.class);method.setAccessible(true);method.invoke(battle,actor.getUUID().toString());
    }
    private static void clearSpawnInvulnerability(ServerPlayer player) {
        try {
            Field field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);field.setInt(player,0);
        } catch (ReflectiveOperationException failure) {throw new IllegalStateException("Cannot clear native spawn invulnerability",failure);}
    }

    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void finish(MinecraftServer server) {
        enabled=false; BattleManager.reset(); BattleManager.setAutoTrigger(true);
        if(configured) {
            TacticalConfig.INSTANCE.participantCap.set(oldCap);
            TacticalConfig.INSTANCE.friendlyFire.set(oldFriendlyFire);
        }
        configured=false;
        for(Mob mob:mobs)mob.discard(); mobs.clear();friends.clear();
        if(owner!=null&&!owner.isRemoved())level.removePlayerImmediately(owner,Entity.RemovalReason.DISCARDED);
        if(wire!=null&&wire.channel!=null)wire.channel.finishAndReleaseAll();
        if(level!=null)for(BlockPos chunk:chunks)level.setChunkForced(chunk.getX(),chunk.getZ(),false);chunks.clear();
        server.halt(false);
    }
    private static final class Wire extends Connection {
        private EmbeddedChannel channel;
        private final List<BattleStatePacket> states=new ArrayList<>();
        private Wire() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet,PacketSendListener listener,boolean flush) {
            if(packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BattleStatePacket state)states.add(state);
        }
        private BattleStatePacket last() { check(!states.isEmpty(),"native state recorded");return states.getLast(); }
        private int inactiveCount() { return (int)states.stream().filter(s->!s.active()).count(); }
    }
}
