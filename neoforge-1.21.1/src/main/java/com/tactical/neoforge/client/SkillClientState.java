package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.SkillListRequestPacket;
import com.tactical.neoforge.network.SkillStatePacket;
import com.tactical.neoforge.network.SkillView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Server-owned skill grants/resources. No client NBT parsing, formula execution, spending or cooldown math. */
@EventBusSubscriber(modid=Tactical.MOD_ID,value=Dist.CLIENT)
public final class SkillClientState {
    private static ClientLevel level;
    private static SkillStatePacket state;
    private static ResourceLocation selected;
    private static long version,lastRequest=Long.MIN_VALUE;
    private SkillClientState() { }
    public static void accept(SkillStatePacket packet) {
        // Only the membership this client currently shows may drive its skill state.
        if(!BattleMembership.matches(packet.membershipServerEpoch(),packet.membershipGeneration(),packet.session()))return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||!packet.dimension().equals(mc.level.dimension().location()))return;
        if(state!=null&&state.session().equals(packet.session())&&packet.revision()<state.revision())return;
        if(!packet.active()){if(state==null||state.session().equals(packet.session()))clear();return;}
        if(level!=mc.level||state!=null&&!state.session().equals(packet.session()))clear();
        level=mc.level;state=packet;version++;
        BattleClientState.mp=packet.mp();BattleClientState.maxMp=packet.maximum();BattleClientState.mpRecovery=packet.recovery();
        BattleClientState.dirty=true;
        SkillOverlay.clearPreview();
        if(selected!=null&&view(selected)==null) {
            cancelSelection();
            if(BattleClientState.isPlayerTurn()&&mc.screen instanceof BattleAim.BattleAimScreen)SkillMenu.open();
        }
        BattleAim.refreshLabels();
    }
    public static List<SkillView> skills(){return state==null?List.of():state.skills();}
    public static SkillView view(ResourceLocation id){return id==null?null:skills().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);}
    public static SkillView selected(){return view(selected);}
    public static ResourceLocation selectedId(){return selected;}
    public static void castConfirmed() { selected=null; SkillOverlay.clearPreview(); BattleClientState.mode=BattleMode.NONE; }
    public static UUID session(){return state==null?null:state.session();}
    public static long revision(){return state==null?-1:state.revision();}
    public static long version(){return version;}
    public static boolean friendly(int entityId){return state!=null&&state.friendlyIds().contains(entityId);}
    public static void select(ResourceLocation id) {
        SkillView view=view(id);
        if(view==null||!view.ready()||!BattleClientState.isPlayerTurn()||BattleClientState.moving)return;
        selected=id;SkillOverlay.clearPreview();BattleClientState.mode=BattleMode.SKILL;BattleAim.open();
    }
    public static void cancelSelection() {
        selected=null;SkillOverlay.clearPreview();
        if(BattleClientState.mode==BattleMode.SKILL)BattleClientState.mode=BattleMode.NONE;
    }
    public static void request() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.getConnection()==null||!mc.getConnection().hasChannel(SkillListRequestPacket.TYPE))return;
        long now=mc.level.getGameTime();if(lastRequest==now)return;
        var stamp=BattleClientState.stamp();if(stamp==null)return;
        lastRequest=now;PacketDistributor.sendToServer(new SkillListRequestPacket(stamp));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        if(!BattleClientState.active||mc.level==null||mc.player==null||!mc.player.isAlive()||(level!=null&&level!=mc.level)) {
            if(state!=null||selected!=null)clear();return;
        }
        long now=mc.level.getGameTime();
        if(state==null&&(lastRequest==Long.MIN_VALUE||now<lastRequest||now-lastRequest>=20))request();
        SkillMenu.tick();
    }
    public static void clear() {
        level=null;state=null;selected=null;lastRequest=Long.MIN_VALUE;version++;
        BattleClientState.mp=BattleClientState.maxMp=BattleClientState.mpRecovery=0;
        BattleClientState.dirty=true;
        if(BattleClientState.mode==BattleMode.SKILL)BattleClientState.mode=BattleMode.NONE;
        SkillOverlay.clearPreview();
    }
    public static String number(double value) {
        return value==Math.rint(value)?Long.toString((long)value):String.format(Locale.ROOT,"%.1f",value);
    }
    public static String mpText(){return number(BattleClientState.mp)+" / "+number(BattleClientState.maxMp);}
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event){clear();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel()==level)clear();}
}
