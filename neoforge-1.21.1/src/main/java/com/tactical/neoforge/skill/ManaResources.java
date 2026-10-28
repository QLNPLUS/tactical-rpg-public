package com.tactical.neoforge.skill;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.BattleManager;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.Map;
import java.util.WeakHashMap;

/** Persistent authoritative MP. Battle entry never initializes an existing balance again. */
@EventBusSubscriber(modid=Tactical.MOD_ID)
public final class ManaResources {
    public static final String DATA_KEY="tactical:mp";
    private static final Map<LivingEntity,Long> CLOCK=new WeakHashMap<>();
    private static long ticks;
    private ManaResources() { }
    public static double maximum(LivingEntity actor) {return bounded(ManaAttributes.maximum(actor));}
    public static double recovery(LivingEntity actor) {return bounded(ManaAttributes.recovery(actor));}
    private static double bounded(double value) {return Double.isFinite(value)?Math.max(0,Math.min(1_000_000,value)):0;}
    public static double current(LivingEntity actor) {
        if(actor.level().isClientSide)throw new IllegalStateException("MP balance is server-owned");
        var data=actor.getPersistentData();
        double value=data.contains(DATA_KEY,Tag.TAG_ANY_NUMERIC)?data.getDouble(DATA_KEY):initial();
        if(!Double.isFinite(value))value=0;
        value=Math.max(0,Math.min(maximum(actor),value));
        data.putDouble(DATA_KEY,value);
        CLOCK.putIfAbsent(actor,ticks);
        return value;
    }
    public static boolean spend(LivingEntity actor,double amount) {
        if(!Double.isFinite(amount)||amount<0)return false;
        double before=current(actor);
        if(before+1.0E-7<amount)return false;
        actor.getPersistentData().putDouble(DATA_KEY,Math.max(0,before-amount));
        return true;
    }
    public static double add(LivingEntity actor,double amount) {
        if(!Double.isFinite(amount))return current(actor);
        double value=Math.max(0,Math.min(maximum(actor),current(actor)+amount));
        actor.getPersistentData().putDouble(DATA_KEY,value);
        return value;
    }
    public static void set(LivingEntity actor,double value) {
        if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite MP");
        current(actor);
        actor.getPersistentData().putDouble(DATA_KEY,Math.max(0,Math.min(maximum(actor),value)));
    }
    private static double initial() {
        try{return TacticalConfig.INSTANCE.initialMp.get();}catch(IllegalStateException e){return 100;}
    }
    private static int interval() {
        try{return TacticalConfig.INSTANCE.outsideMpInterval.get();}catch(IllegalStateException e){return 200;}
    }
    private static double outsideRecovery() {
        try{return TacticalConfig.INSTANCE.outsideMpRecovery.get();}catch(IllegalStateException e){return 20;}
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        ticks++;
        if(ticks%20!=0)return;
        MinecraftServer server=event.getServer();
        for(ServerPlayer player:server.getPlayerList().getPlayers())current(player);
        // A copy permits initialization by callbacks without mutating the iteration.
        var snapshot=new java.util.ArrayList<>(CLOCK.entrySet());
        for(var entry:snapshot) {
            LivingEntity actor=entry.getKey();
            if(actor.isRemoved()||!actor.isAlive()||actor.getServer()!=server){CLOCK.remove(actor);continue;}
            // Outside recovery stays frozen for a fighting actor, whichever encounter owns it.
            if(BattleManager.isParticipant(actor)){CLOCK.put(actor,ticks);continue;}
            if(ticks-entry.getValue()>=interval()) {
                add(actor,outsideRecovery());
                CLOCK.put(actor,ticks);
            }
        }
    }
    @SubscribeEvent public static void clonePlayer(PlayerEvent.Clone event) {
        if(event.getEntity().level().isClientSide)return;
        var previous=event.getOriginal().getPersistentData();
        if(previous.contains(DATA_KEY,Tag.TAG_ANY_NUMERIC)) {
            event.getEntity().getPersistentData().putDouble(DATA_KEY,previous.getDouble(DATA_KEY));
            CLOCK.put(event.getEntity(),ticks);
        }
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {CLOCK.clear();ticks=0;}
}
