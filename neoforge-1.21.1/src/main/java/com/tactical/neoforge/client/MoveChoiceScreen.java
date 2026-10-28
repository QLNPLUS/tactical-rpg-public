package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.tactical.neoforge.network.MoveChoiceOfferPacket;
import com.tactical.neoforge.network.MoveChoicePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** Both viable routes are explicit choices; the server revalidates the nonce and collision. */
public final class MoveChoiceScreen extends TacticalScreen {
 public static final String PAGE="tactical/screens/battle_move_choice.html";
 private final MoveChoiceOfferPacket offer;
 private long generation=-1;
 private MoveChoiceScreen(MoveChoiceOfferPacket offer){super(PAGE,()->cancel(offer));this.offer=offer;}
 public static void open(MoveChoiceOfferPacket offer){
  if(!BattleClientState.isPlayerTurn()||BattleClientState.moving) return;
  var screen=new MoveChoiceScreen(offer);screen.setPauseGame(false).setShowDefaultBackground(false);
  Minecraft.getInstance().setScreen(screen);
 }
 public static void tickOpenScreen(){if(Minecraft.getInstance().screen instanceof MoveChoiceScreen s)s.bind();}
 @Override protected void init(){super.init();bind();}
 private void bind(){
  if(offer==null)return;
  Document d=getLinkedDocument();if(d==null||generation==d.getRefreshGeneration())return;
  generation=d.getRefreshGeneration();
  d.getElementById("route-target").setTextContent("落点 "+offer.target()+" · 下降 "+offer.dropHeight()+" 格");
  d.getElementById("route-walk").setTextContent("绕路行走 · "+offer.walkCost()+" AP");
  d.getElementById("route-drop").setTextContent("直接跳下 · "+offer.dropCost()+" AP");
  d.getElementById("route-warning").setTextContent(offer.dropHeight()>3?"跳落可能造成摔落伤害，请留意剩余生命值。":"跳落沿途必须没有方块或单位阻挡。");
  wire(d,"route-walk",0);wire(d,"route-drop",1);wire(d,"route-cancel",-1);
 }
 private void wire(Document d,String id,int mode){d.getElementById(id).addEventListener("click",e->{
  e.preventDefault();e.stopPropagation();Minecraft.getInstance().tell(()->{
   if(mode==-1){cancel(offer);return;}
   var stamp=BattleClientState.stamp();
   if(stamp==null)return;
   PacketDistributor.sendToServer(new MoveChoicePacket(stamp,offer.token(),mode));
   Minecraft.getInstance().setScreen(null);
  });
 });}
 private static void cancel(MoveChoiceOfferPacket offer){
  var stamp=BattleClientState.stamp();
  if(stamp!=null)PacketDistributor.sendToServer(new MoveChoicePacket(stamp,offer.token(),-1));
  Minecraft.getInstance().setScreen(null);BattleClientState.mode=BattleMode.MOVE;BattleAim.open();
 }
}
