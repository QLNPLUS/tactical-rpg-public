package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.AiContext;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Disposable native fixture: -Dtactical.selftest=ap. Uses native actions, not simulated AP debits. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ApSelfTest {
    private static boolean enabled, cancelAttack, cancelUse, cancelShot;
    private static int ticks, stage, stageAt;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Zombie melee;
    private static Skeleton ranged;
    private static ServerBattle battle;
    private static Wire wire;
    private static final List<Mob> mobs=new ArrayList<>();
    private static final List<BlockPos> forced=new ArrayList<>();
    private ApSelfTest() { }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        enabled="ap".equals(System.getProperty("tactical.selftest"));
        ticks=stage=stageAt=0;
    }
    @SubscribeEvent public static void attack(AttackEntityEvent event) {
        if(enabled && cancelAttack && event.getEntity()==owner) event.setCanceled(true);
    }
    @SubscribeEvent public static void use(LivingEntityUseItemEvent.Start event) {
        if(enabled && cancelUse && event.getEntity()==owner) event.setCanceled(true);
    }
    @SubscribeEvent public static void loose(ArrowLooseEvent event) {
        if(enabled && cancelShot && event.getEntity()==owner) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!enabled || ++ticks<20)return;
        try {
            check(ticks<900,"fixture deadline");
            if(stage==0) { setup(event.getServer());stage=1;return; }
            if(stage==1) {
                if(ticks<100 || !level.getEntitiesOfClass(Skeleton.class,owner.getBoundingBox().inflate(12)).contains(ranged))return;
                initialAndSharedActions();stage=2;stageAt=ticks;
            } else if(stage==2) {
                if(!battle.isPlayerTurnNow() || ticks-stageAt<25)return;
                check(battle.successfulPaidActions()==4,"projectile resolution does not reset the shared own-turn count");
                ItemStack crossbow=new ItemStack(Items.CROSSBOW);
                owner.setItemInHand(InteractionHand.MAIN_HAND,crossbow);
                ((CrossbowItem)crossbow.getItem()).releaseUsing(crossbow,level,owner,0);
                check(CrossbowItem.isCharged(crossbow),"real crossbow loaded");
                int price=battle.currentAttackCost(),before=battle.currentAp();
                check(price==40,"crossbow uses shared fifth-action price");
                battle.tryAttack(ranged.getUUID());
                check(battle.pendingProjectiles()>0 && battle.currentAp()==before-price
                        && battle.successfulPaidActions()==5,"native crossbow commits exact shared cost once");
                stage=3;stageAt=ticks;
            } else if(stage==3) {
                if(!battle.isPlayerTurnNow() || ticks-stageAt<25)return;
                lifecycleAndActors(event.getServer());
                Tactical.LOGGER.info("[Tactical] AP SELFTEST PASSED: native melee/item/place/bow/crossbow shared ramp, cancelled attack/use/loose and LOS/noammo/rejected actions preserve count/AP, initial100 all actors, first-own skip, carryover recovery/cap, native dynamic recovery sync, independent actors/reset, reinforcements and low cap");
                finish(event.getServer());
            }
        } catch(Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] AP SELFTEST FAILED stage={} tick={} ap={} count={}",stage,ticks,
                    battle==null?-1:battle.currentAp(),battle==null?-1:battle.successfulPaidActions(),failure);
            Tactical.LOGGER.error("[Tactical] AP DIAGNOSTIC owner={} melee={} ranged={} ammo={} attackCost={} pending={} journal={}",
                    owner==null?null:owner.position(),melee==null?null:melee.position(),ranged==null?null:ranged.position(),
                    owner==null?null:owner.getOffhandItem(),battle==null?-1:battle.currentAttackCost(),
                    battle==null?-1:battle.pendingProjectiles(),wire==null?null:wire.log);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    private static void initialAndSharedActions() throws Exception {
        for(Mob other:level.getEntitiesOfClass(Mob.class,owner.getBoundingBox().inflate(24)))
            if(!mobs.contains(other) && other instanceof net.minecraft.world.entity.monster.Enemy)other.discard();
        check(BattleManager.start(owner,true),"native battle starts");
        battle=BattleManager.current();
        check(battle.isPlayerTurnNow() && battle.currentMaxAp()==150 && battle.currentAp()==100,"owner first turn initial100 without recovery");
        for(Mob mob:mobs)check(integer(state(mob),"ap")==100 && integer(state(mob),"maxAp")==150,"all native mob types initial100 cap150");
        check(battle.currentMeleeCost()==10 && battle.currentBowCost()==15 && battle.currentCrossbowCost()==20
                && battle.currentItemCost()==8 && battle.currentPlaceCost()==10,"all configured base quotes");
        int before=battle.currentAp();
        for(int i=0;i<10;i++){battle.currentAttackCost();battle.currentItemCost();battle.currentPlaceCost();}
        check(battle.currentAp()==before && battle.successfulPaidActions()==0,"quotes have no mutation");
        cancelAttack=true;battle.tryAttack(melee.getUUID());cancelAttack=false;
        unchanged(before,0,"cancelled native AttackEntityEvent");
        level.setBlock(melee.blockPosition().above(),Blocks.STONE.defaultBlockState(),3);
        battle.tryAttack(melee.getUUID());
        unchanged(before,0,"blocked melee LOS");
        level.setBlock(melee.blockPosition().above(),Blocks.AIR.defaultBlockState(),3);
        melee.invulnerableTime=0;
        float health=melee.getHealth();
        battle.tryAttack(melee.getUUID());
        check(melee.getHealth()<health && battle.currentAp()==90 && battle.successfulPaidActions()==1,"actual native melee commits10 once despite attackSpeed100");
        check(battle.currentMeleeCost()==13 && battle.currentItemCost()==10 && battle.currentPlaceCost()==13,"shared count advances every category");

        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK));
        battle.useItem(owner.getInventory().selected);unchanged(90,1,"native rejected item");
        owner.getFoodData().setFoodLevel(8);
        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,2));
        cancelUse=true;battle.useItem(owner.getInventory().selected);cancelUse=false;
        unchanged(90,1,"cancelled native use Start despite consume return");
        check(owner.getMainHandItem().getCount()==2,"cancelled food remains");
        battle.useItem(owner.getInventory().selected);
        check(owner.getFoodData().getFoodLevel()>8 && owner.getMainHandItem().getCount()==1
                && battle.currentAp()==80 && battle.successfulPaidActions()==2,"actual native food commits escalated10");

        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE,4));
        BlockPos ground=origin.offset(0,-1,2);
        battle.placeBlock(owner.getInventory().selected,origin.offset(100,0,0),Direction.UP,Vec3.atCenterOf(origin.offset(100,0,0)));
        unchanged(80,2,"rejected placement");
        battle.placeBlock(owner.getInventory().selected,ground,Direction.UP,new Vec3(ground.getX()+.5,ground.getY()+1,ground.getZ()+.5));
        check(level.getBlockState(ground.above()).is(Blocks.STONE) && owner.getMainHandItem().getCount()==3
                && battle.currentAp()==65 && battle.successfulPaidActions()==3,"native placement commits escalated15");

        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BOW));
        battle.tryAttack(ranged.getUUID());unchanged(65,3,"no native ammunition");
        owner.getInventory().offhand.set(0,new ItemStack(Items.ARROW,8));
        check(BallisticShot.plan(level,owner,battle.playerCell(),ranged,3F,true,0)!=null,"fixture bow lane is physically clear before native cancellation tests");
        cancelShot=true;battle.tryAttack(ranged.getUUID());cancelShot=false;
        unchanged(65,3,"cancelled native ArrowLooseEvent");
        check(owner.getOffhandItem().getCount()==8,"cancelled loose preserves ammo");
        sealed(true);battle.tryAttack(ranged.getUUID());unchanged(65,3,"blocked ballistic LOS");sealed(false);
        check(battle.currentAttackCost()==27,"bow third-success escalation rounds26.25 up27");
        battle.tryAttack(ranged.getUUID());
        check(battle.pendingProjectiles()>0 && owner.getOffhandItem().getCount()==7 && battle.currentAp()==38
                && battle.successfulPaidActions()==4,"native bow commits escalated27 only on created projectile");
        battle.adjustAp(10000);
        check(battle.currentAp()==150 && battle.successfulPaidActions()==4,"admin AP adjustment leaves count intact");
    }

    private static void lifecycleAndActors(MinecraftServer server) throws Exception {
        battle.adjustAp(30-battle.currentAp());
        begin(owner);
        check(battle.currentAp()==80 && battle.successfulPaidActions()==0,"next own turn carries30 plus50 instead of full reset and resets count");
        check(integer(state(melee),"ap")==100 && !bool(state(melee),"hasTakenTurn"),"another actor's turns do not recover waiting enemy");
        begin(melee);
        check(integer(state(melee),"ap")==100,"enemy first own turn skips recovery");
        owner.invulnerableTime=0;
        // Reposition the native actor and its authoritative cell together after projectile reconciliation.
        GridPos adjacent=battle.playerCell().offset(1,0,0);
        melee.setPos(adjacent.x()+.5,adjacent.y(),adjacent.z()+.5);
        field(state(melee),"cell").set(state(melee),adjacent);
        AiContext context=context(melee),other=context(ranged);
        context.attackPlayer();
        check(integer(state(melee),"ap")==90 && integer(state(melee),"paidActions")==1 && context.meleeCost()==13,
                "ground mob native melee pays and escalates through same logic");
        check(integer(state(ranged),"ap")==100 && other.meleeCost()==10 && other.rangedCost()==15,
                "actor counters and AP are independent");
        begin(owner);
        check(integer(state(melee),"paidActions")==1,"other actor's turn does not reset enemy count");
        begin(melee);
        check(integer(state(melee),"ap")==140 && integer(state(melee),"paidActions")==0,"enemy carry90 plus50 capped without full reset");
        begin(melee);check(integer(state(melee),"ap")==150,"enemy recovery caps150");

        begin(owner);
        battle.adjustAp(20-battle.currentAp());
        server.getCommands().getDispatcher().execute("attribute @s tactical:ap_recovery base set 30",owner.createCommandSourceStack().withPermission(2));
        int revision=battle.stateRevision();battle.tick();
        check(battle.stateRevision()>revision && wire.state.apRecovery()==30 && battle.currentAp()==20,
                "recovery-only native command updates S2C immediately without refilling AP");
        begin(owner);check(battle.currentAp()==50,"native absolute recovery30 applied to actual remainder20");
        owner.getAttribute(TacticalAttributes.AP_RECOVERY).setBaseValue(0);
        begin(owner);check(battle.currentAp()==50,"native zero recovery preserves carryover");
        owner.getAttribute(TacticalAttributes.AP_RECOVERY).setBaseValue(50);
        battle.adjustAp(10000);
        owner.getAttribute(TacticalAttributes.MAX_AP).setBaseValue(50);battle.tick();
        check(battle.currentMaxAp()==75 && battle.currentAp()==75,"lower native percentage cap clamps current AP");
        owner.getAttribute(TacticalAttributes.MAX_AP).setBaseValue(200);battle.tick();
        check(battle.currentMaxAp()==300 && battle.currentAp()==75,"raise native cap does not refill");

        Zombie join=mob(EntityType.ZOMBIE,-4,0);
        join.getAttribute(TacticalAttributes.AP_RECOVERY).setBaseValue(500);
        check(battle.tryAbsorb(join) && integer(state(join),"ap")==100,"late reinforcement starts100");
        begin(join);check(integer(state(join),"ap")==100,"late first own turn skips even modified500 recovery");
        begin(join);check(integer(state(join),"ap")==150,"late subsequent recovery caps correctly");
        Zombie small=mob(EntityType.ZOMBIE,-4,3);
        small.getAttribute(TacticalAttributes.MAX_AP).setBaseValue(40);
        check(battle.tryAbsorb(small) && integer(state(small),"maxAp")==60 && integer(state(small),"ap")==60,"reinforcement initial bounded by lower native cap");
        begin(small);check(integer(state(small),"ap")==60,"low cap first turn remains bounded");
        phantomReservationRefund();
        begin(owner);
    }

    private static void phantomReservationRefund() throws Exception {
        var phantom=mob(EntityType.PHANTOM,4,4);
        check(battle.tryAbsorb(phantom),"native phantom joins");
        begin(phantom);
        GridPos strike=battle.playerCell().offset(1,1,0);
        phantom.setPos(strike.x()+.5,strike.y(),strike.z()+.5);
        Object state=state(phantom);
        field(state,"cell").set(state,strike);
        field(state,"airOffset").set(state,Vec3.ZERO);
        owner.invulnerableTime=0;
        context(phantom).attackPlayer();
        check(integer(state,"paidActions")==1 && context(phantom).meleeCost()==13,"native phantom prior successful melee produces escalated13 quote");
        GridPos home=battle.playerCell().offset(4,4,4);
        phantom.setPos(home.x()+.5,home.y(),home.z()+.5);
        field(state,"cell").set(state,home);
        var plan=new com.tactical.neoforge.battle.ai.PhantomTacticalAi().planTurn(context(phantom));
        check(plan.kind()==com.tactical.neoforge.battle.ai.AiAction.Kind.DIVE
                && com.tactical.neoforge.battle.ai.BattleAiSession.validDive(context(phantom),plan),"escalated native phantom plan budgets complete paid return");
        Object queue=field(battle,"queue").get(battle);
        field(queue,"current").set(queue,phantom.getUUID().toString());
        int before=integer(state,"ap");
        var resolve=ServerBattle.class.getDeclaredMethod("resolveAiAction",state.getClass(),com.tactical.neoforge.battle.ai.AiAction.class);
        resolve.setAccessible(true);resolve.invoke(battle,state,plan);
        check(integer(battle,"enemyDiveReservedCost")==13
                && integer(state,"ap")==before-plan.path().size()*integer(state,"moveCost")-13,
                "dive prepays exact escalated13 reservation without committing the strike count");
        GridPos next=plan.path().get(0);BlockPos obstacle=new BlockPos(next.x(),next.y(),next.z());
        var prior=level.getBlockState(obstacle);level.setBlock(obstacle,Blocks.STONE.defaultBlockState(),3);
        var step=ServerBattle.class.getDeclaredMethod("stepEnemy");step.setAccessible(true);
        for(int i=0;i<8 && bool(battle,"enemyMoving");i++)step.invoke(battle);
        level.setBlock(obstacle,prior,3);
        check(!bool(battle,"enemyMoving") && integer(state,"ap")==before && integer(state,"paidActions")==1
                && integer(battle,"enemyDiveReservedCost")==0,"blocked pre-strike native dive refunds every unexecuted step and exact13, adds no count");
    }

    private static void sealed(boolean seal) {
        BlockPos cell=ranged.blockPosition();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++)
            if(Math.abs(x)==1 || Math.abs(z)==1 || y==3)
                level.setBlock(cell.offset(x,y,z),(seal?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
    }
    private static void unchanged(int ap,int count,String label) {
        check(battle.currentAp()==ap && battle.successfulPaidActions()==count && battle.pendingProjectiles()==0,label+" preserves AP/count and creates no projectile");
    }
    private static void begin(net.minecraft.world.entity.LivingEntity actor) throws Exception {
        var method=ServerBattle.class.getDeclaredMethod("beginTurn",String.class);method.setAccessible(true);
        method.invoke(battle,actor.getUUID().toString());
    }
    private static Object state(Mob actor) throws Exception { return ((Map<?,?>)field(battle,"enemies").get(battle)).get(actor.getUUID()); }
    private static Field field(Object target,String name) throws Exception { var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f; }
    private static int integer(Object target,String name) throws Exception { return field(target,name).getInt(target); }
    private static boolean bool(Object target,String name) throws Exception { return field(target,name).getBoolean(target); }
    private static AiContext context(Mob actor) throws Exception {
        Object state=state(actor);var method=ServerBattle.class.getDeclaredMethod("aiContext",state.getClass());method.setAccessible(true);
        return (AiContext)method.invoke(battle,state);
    }
    private static void setup(MinecraftServer server) {
        BattleManager.setAutoTrigger(false);level=server.overworld();level.setDayTime(18000);
        BlockPos spawn=level.getSharedSpawnPos();origin=new BlockPos(spawn.getX(),Math.min(level.getMaxBuildHeight()-10,spawn.getY()+20),spawn.getZ());
        for(int x=(origin.getX()-20)>>4;x<=(origin.getX()+20)>>4;x++)for(int z=(origin.getZ()-20)>>4;z<=(origin.getZ()+20)>>4;z++)
            if(level.setChunkForced(x,z,true))forced.add(new BlockPos(x,0,z));
        for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++) {
            level.setBlock(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
            for(int y=0;y<6;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        GameProfile profile=new GameProfile(UUID.nameUUIDFromBytes("tactical-ap-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"ApOwner");
        owner=new ServerPlayer(server,level,profile,ClientInformation.createDefault());wire=new Wire();wire.channel=new EmbeddedChannel(wire);
        for(String id:List.of("battle_state","battle_log","player_step","creeper_telegraph","move_choice_offer"))
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,id));
        new ServerGamePacketListenerImpl(server,wire,owner,CommonListenerCookie.createInitial(profile,false));
        owner.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);owner.setHealth(1000);
        level.addNewPlayer(owner);
        melee=mob(EntityType.ZOMBIE,1,0);ranged=mob(EntityType.SKELETON,4,8);
    }
    private static <T extends Mob>T mob(EntityType<T> type,int dx,int dz) {
        T mob=type.create(level);check(mob!=null,"native actor created");mob.setPos(origin.getX()+dx+.5,origin.getY(),origin.getZ()+dz+.5);
        mob.setNoAi(true);mob.setPersistenceRequired();mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);mob.setHealth(1000);
        level.addFreshEntity(mob);mobs.add(mob);return mob;
    }
    private static void check(boolean condition,String label) { if(!condition)throw new AssertionError(label); }
    private static void finish(MinecraftServer server) {
        enabled=false;cancelAttack=cancelUse=cancelShot=false;BattleManager.reset();
        for(Mob mob:mobs)mob.discard();mobs.clear();
        if(owner!=null && !owner.isRemoved())level.removePlayerImmediately(owner,Entity.RemovalReason.DISCARDED);
        if(wire!=null && wire.channel!=null)wire.channel.finishAndReleaseAll();
        if(level!=null)for(BlockPos chunk:forced)level.setChunkForced(chunk.getX(),chunk.getZ(),false);
        forced.clear();BattleEffects.clear();server.halt(false);
    }
    private static final class Wire extends Connection {
        EmbeddedChannel channel;BattleStatePacket state;
        com.tactical.neoforge.network.BattleLogPacket log;
        Wire(){super(PacketFlow.SERVERBOUND);}
        @Override public void send(Packet<?> packet,PacketSendListener listener,boolean flush) {
            if(packet instanceof ClientboundCustomPayloadPacket custom) {
                if(custom.payload() instanceof BattleStatePacket next)state=next;
                if(custom.payload() instanceof com.tactical.neoforge.network.BattleLogPacket next)log=next;
            }
        }
    }
}
