package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.combat.ApCalculator;
import com.tactical.core.combat.TurnScheduler;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Disposable dedicated-server fixture: -Dtactical.selftest=attributes. Never launches a client. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class AttributesSelfTest {
    private static boolean enabled;
    private static int ticks;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner, outsider;
    private static Creeper enemy;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();

    private AttributesSelfTest() {}

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        enabled = "attributes".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            if (++ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            check(ticks < 200, "chunks loaded before deadline");
            if (!level.getEntitiesOfClass(Creeper.class, owner.getBoundingBox().inflate(10)).contains(enemy)) return;
            run(event.getServer());
            Tactical.LOGGER.info("[Tactical] ATTRIBUTES SELFTEST PASSED: all living defaults, native attribute commands/modifiers, dynamic cap/rate and gauge, AP command permission/bounds/clamp/owner and immediate S2C");
            finish(event.getServer());
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] ATTRIBUTES SELFTEST FAILED", failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.setAutoTrigger(false);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight()-8, spawn.getY()+16), spawn.getZ());
        for (int x=(origin.getX()-12)>>4; x<=(origin.getX()+12)>>4; x++)
            for (int z=(origin.getZ()-12)>>4; z<=(origin.getZ()+12)>>4; z++)
                if (level.setChunkForced(x,z,true)) chunks.add(new BlockPos(x,0,z));
        for (int x=-12;x<=12;x++) for (int z=-12;z<=12;z++) {
            level.setBlock(origin.offset(x,-1,z), Blocks.STONE.defaultBlockState(),3);
            for(int y=0;y<4;y++) level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        owner = player(server,"AttrOwner",0);
        outsider = player(server,"AttrOutsider",-3);
        enemy = EntityType.CREEPER.create(level);
        check(enemy != null,"enemy created");
        enemy.setPos(origin.getX()+3.5,origin.getY(),origin.getZ()+.5);
        enemy.setPersistenceRequired();
        enemy.setNoAi(true);
        level.addFreshEntity(enemy);
    }

    private static ServerPlayer player(MinecraftServer server,String name,int dx) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
        ServerPlayer player = new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        RecordingConnection wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        for(String id:List.of("battle_state","battle_log","player_step","creeper_telegraph","move_choice_offer"))
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,id));
        new ServerGamePacketListenerImpl(server,wire,player,CommonListenerCookie.createInitial(profile,false));
        player.setPos(origin.getX()+dx+.5,origin.getY(),origin.getZ()+.5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.2);
        level.addNewPlayer(player);
        players.add(player); wires.add(wire);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static void run(MinecraftServer server) throws Exception {
        check(TacticalAttributes.MAX_AP.value().isClientSyncable() && TacticalAttributes.AP_RECOVERY.value().isClientSyncable() && TacticalAttributes.TURN_CHARGE_SPEED.value().isClientSyncable(),"all tactical attributes synced");
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (!DefaultAttributes.hasSupplier(type)) continue;
            var supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            check(supplier.hasAttribute(TacticalAttributes.MAX_AP) && supplier.hasAttribute(TacticalAttributes.AP_RECOVERY) && supplier.hasAttribute(TacticalAttributes.TURN_CHARGE_SPEED),"attributes installed for " + type);
        }
        for(LivingEntity living:List.of(owner,outsider,enemy)) {
            check(living.getAttributeValue(TacticalAttributes.MAX_AP)==100,"default max AP");
            check(living.getAttributeValue(TacticalAttributes.TURN_CHARGE_SPEED)==1,"default charge");
            check(TacticalAttributes.apRecovery(living)==50,"default absolute recovery");
            check(TacticalAttributes.maxAp(living)==150,"saved percentage100 now uses fixed baseline150 for every speed");
        }
        check(BattleManager.start(owner,true),"owner battle starts");
        ServerBattle battle = BattleManager.current();
        var field = ServerBattle.class.getDeclaredField("queue"); field.setAccessible(true);
        TurnScheduler queue = (TurnScheduler)field.get(battle);
        String id=owner.getUUID().toString();
        double baseline=queue.rateOf(id), gauge=queue.gaugeOf(id);
        command(server,owner,"attribute @s tactical:max_ap base set 200");
        owner.getAttribute(TacticalAttributes.MAX_AP).addTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"test_ap"),100,AttributeModifier.Operation.ADD_VALUE));
        owner.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).addTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"test_charge"),1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        check(TacticalAttributes.maxAp(owner)==450,"native ADD_VALUE changes max AP");
        battle.tick();
        check(battle.currentAp()==100,"raising cap never refills initial100 AP");
        check(Math.abs(queue.rateOf(id)-baseline*2)<1e-6,"actual battle scheduler speed updated");
        check(queue.gaugeOf(id)==gauge,"live rate update preserves gauge");
        command(server,owner,"attribute @s tactical:turn_charge_speed base set 0.25");
        battle.tick();
        check(Math.abs(queue.rateOf(id)-baseline*.5)<1e-6,"native charge command changes actual scheduler");
        owner.getAttribute(TacticalAttributes.MAX_AP).addTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"test_ap_multiply"),1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        check(TacticalAttributes.maxAp(owner)==900,"native multiply combines with additive cap modifier");
        owner.getAttribute(TacticalAttributes.MAX_AP).removeModifier(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"test_ap_multiply"));
        check(command(server,owner,"tactical ap add @s 10000")==1,"AP add succeeds");
        check(battle.currentAp()==450 && wires.get(0).last().ap()==450,"AP add capped and sent immediately");
        check(command(server,owner,"tactical ap remove @s 10000")==1 && battle.currentAp()==0,"AP remove clamps zero");
        check(command(server,owner,"tactical ap add @s 20")==1 && battle.currentAp()==20,"AP add exact delta");
        int count=wires.get(0).states.size();
        check(command(server,outsider,"tactical ap add @s 100")==0 && battle.currentAp()==20,"outsider cannot adjust owner");
        check(wires.get(0).states.size()==count,"outsider sends no owner state");
        for(String amount:List.of("0","-1","10001")) {
            try { command(server,owner,"tactical ap add @s "+amount); throw new AssertionError("invalid amount accepted"); }
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
        }
        try {
            server.getCommands().getDispatcher().execute("tactical ap add @s 1",owner.createCommandSourceStack().withPermission(0));
            throw new AssertionError("non-admin accepted");
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
        command(server,owner,"attribute @s tactical:max_ap base set 1");
        owner.getAttribute(TacticalAttributes.MAX_AP).removeModifier(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"test_ap"));
        battle.tick();
        check(battle.currentAp()==TacticalAttributes.maxAp(owner),"dynamic cap decrease clamps current AP");
    }

    private static int command(MinecraftServer server,ServerPlayer player,String command) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return server.getCommands().getDispatcher().execute(command,player.createCommandSourceStack().withPermission(2));
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void finish(MinecraftServer server) {
        enabled=false; BattleManager.reset();
        if(enemy!=null)enemy.discard();
        for(ServerPlayer player:players) if(!player.isRemoved())level.removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);
        for(RecordingConnection wire:wires)wire.channel.finishAndReleaseAll();
        if(level!=null)for(BlockPos chunk:chunks)level.setChunkForced(chunk.getX(),chunk.getZ(),false);
        players.clear(); wires.clear(); chunks.clear(); BattleEffects.clear(); server.halt(false);
    }
    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private final List<BattleStatePacket> states=new ArrayList<>();
        private RecordingConnection(){super(PacketFlow.SERVERBOUND);}
        @Override public void send(Packet<?> packet,PacketSendListener listener,boolean flush) {
            if(packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BattleStatePacket state)states.add(state);
        }
        private BattleStatePacket last(){check(!states.isEmpty(),"state recorded");return states.get(states.size()-1);}
    }
}
