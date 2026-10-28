package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.tactical.neoforge.Tactical;

/** Hover focus is render-only and cannot change turn, player pose or server ownership. */
@EventBusSubscriber(modid=Tactical.MOD_ID,value=Dist.CLIENT)
public final class BattleEntityLocator {
 private static final java.util.LinkedHashMap<String,Integer> hovers=new java.util.LinkedHashMap<>();
 private static final String LEGACY="legacy";
 private static int hovered=-1;
 private BattleEntityLocator() { }
 public static void hover(int id) {hover(LEGACY,id);}
 public static void hover(String source,int id) {
  if(!BattleClientState.active||!BattleClientState.participantHealth.containsKey(id))return;
  // Repeated polling must not steal priority from a newer surface enter.
  if(java.util.Objects.equals(hovers.get(source),id))return;
  hovers.remove(source);hovers.put(source,id);resolveHover();
 }
 public static void clearHover(int id) {clear(LEGACY,id);}
 public static void clear(String source,int id) {
  if(java.util.Objects.equals(hovers.get(source),id)){hovers.remove(source);resolveHover();}
 }
 public static void clear(String source) {hovers.remove(source);resolveHover();}
 private static void resolveHover(){hovered=-1;for(int id:hovers.values())hovered=id;}
 public static void clear(){hovers.clear();hovered=-1;}
 public static Entity target() {
  var mc=Minecraft.getInstance();
  if(!BattleClientState.active||mc.level==null||mc.player==null||!mc.player.isAlive()
          ||mc.screen!=null&&!(mc.screen instanceof TacticalScreen)||!BattleClientState.participantHealth.containsKey(hovered)) {clear();return null;}
  Entity entity=mc.level.getEntity(hovered);
  if(entity==null||!entity.isAlive()||entity.isRemoved()){clear();return null;}
  return entity;
 }
 public static int hoveredId(){return target()==null?-1:hovered;}
 @SubscribeEvent public static void tick(ClientTickEvent.Post event){target();}
 @SubscribeEvent public static void render(RenderLevelStageEvent event) {
  Entity entity=target();if(entity==null||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_ENTITIES)return;
  var mc=Minecraft.getInstance();PoseStack pose=event.getPoseStack();var camera=event.getCamera().getPosition();
  pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
  var buffers=mc.renderBuffers().bufferSource();var lines=buffers.getBuffer(RenderType.lines());
  var offset=entity.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false)).subtract(entity.position());
  LevelRenderer.renderLineBox(pose,lines,entity.getBoundingBox().move(offset).inflate(.06),1f,.83f,.22f,1f);
  buffers.endBatch(RenderType.lines());pose.popPose();
 }
}
