package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.LevelGridWorld;
import com.tactical.neoforge.network.*;
import com.tactical.neoforge.skill.SkillDefinition;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.minecraft.client.renderer.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Server-planned skill area and affected actors. Hover previews never spend resources. */
@EventBusSubscriber(modid=Tactical.MOD_ID,value=Dist.CLIENT)
public final class SkillOverlay {
    private static final int REFRESH=4,MAX_AGE=6;
    private static int sequence,pendingId;
    private static long sent=Long.MIN_VALUE;
    private static UUID pendingSession;
    private static SkillTarget pendingTarget;
    private static net.minecraft.resources.ResourceLocation pendingSkill;
    private static long pendingRevision;
    private static GridPos pendingOrigin;
    private static int pendingAction,pendingAp;
    private static ItemStack pendingWeapon;
    private static SkillPreviewResponsePacket preview;
    private static List<Surface> surfaces=List.of();
    private record Surface(int x,double y,int z) { }
    private SkillOverlay() { }
    private static boolean aiming(Minecraft mc) {
        return BattleClientState.mode==BattleMode.SKILL&&BattleClientState.isPlayerTurn()&&!BattleClientState.moving
                &&mc.level!=null&&mc.player!=null&&mc.player.isAlive()&&SkillClientState.selected()!=null
                &&(mc.screen==null||mc.screen instanceof BattleAim.BattleAimScreen);
    }
    public static SkillTarget target(HitResult hit) {
        Minecraft mc=Minecraft.getInstance();SkillView view=SkillClientState.selected();
        if(view==null||mc.player==null||mc.level==null)return null;
        if(view.aim()==SkillDefinition.Aim.SELF)return SkillTarget.self(mc.player);
        if(view.aim()==SkillDefinition.Aim.ENTITY) {
            return hit instanceof EntityHitResult entityHit&&entityHit.getEntity() instanceof LivingEntity entity
                    &&entity.isAlive()&&BattleClientState.participantHealth.containsKey(entity.getId())?SkillTarget.entity(entity.getUUID()):null;
        }
        GridPos cell=hit instanceof EntityHitResult entityHit&&entityHit.getEntity() instanceof LivingEntity entity
                ?new GridPos(entity.getBlockX(),entity.getBlockY(),entity.getBlockZ()):CursorWorldPicker.standCell(mc,hit);
        if(cell==null)return null;
        GridPos origin=BattleClientState.playerCell();
        float yaw=cell.x()==origin.x()&&cell.z()==origin.z()?mc.player.getYRot():GridMovementRules.yaw(origin,cell);
        return SkillTarget.ground(cell,yaw);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        if(!aiming(mc)){clearPreview();return;}
        SkillView view=SkillClientState.selected();SkillTarget target=target(BattleAim.pickAtCursor());
        if(target==null||!view.ready()){clearPreview();updateLabel();return;}
        long now=mc.level.getGameTime();
        boolean changed=!Objects.equals(target,pendingTarget)||!view.id().equals(pendingSkill)
                ||!Objects.equals(SkillClientState.session(),pendingSession)||pendingRevision!=SkillClientState.revision()
                ||!BattleClientState.playerCell().equals(pendingOrigin)||pendingAction!=BattleClientState.actionCount
                ||pendingAp!=BattleClientState.ap||!ItemStack.matches(pendingWeapon,mc.player.getMainHandItem());
        if(changed){preview=null;surfaces=List.of();}
        if(SkillClientState.session()!=null&&mc.getConnection()!=null&&mc.getConnection().hasChannel(SkillPreviewRequestPacket.TYPE)
                &&(changed||sent==Long.MIN_VALUE||now<sent||now-sent>=REFRESH)) {
            pendingSession=SkillClientState.session();pendingSkill=view.id();pendingTarget=target;pendingRevision=SkillClientState.revision();
            pendingOrigin=BattleClientState.playerCell();pendingAction=BattleClientState.actionCount;pendingAp=BattleClientState.ap;
            pendingWeapon=mc.player.getMainHandItem().copy();pendingId=++sequence;sent=now;
            PacketDistributor.sendToServer(new SkillPreviewRequestPacket(pendingSession,BattleClientState.turnEpoch,mc.level.dimension().location(),pendingId,pendingSkill,target));
        }
        updateLabel();
    }
    public static void accept(SkillPreviewResponsePacket packet) {
        Minecraft mc=Minecraft.getInstance();
        if(!aiming(mc)||packet.requestId()!=pendingId||!packet.session().equals(SkillClientState.session())
                ||!packet.dimension().equals(mc.level.dimension().location())||packet.revision()!=SkillClientState.revision()
                ||!packet.skill().equals(SkillClientState.selected().id())||!packet.skill().equals(pendingSkill)
                ||!packet.target().equals(pendingTarget)||!packet.target().equals(target(BattleAim.pickAtCursor()))
                ||mc.level.getGameTime()-packet.serverTick()>=MAX_AGE||!ItemStack.matches(pendingWeapon,mc.player.getMainHandItem()))return;
        preview=packet;List<Surface> placed=new ArrayList<>();int[] tiles=packet.tiles();
        for(int i=0;i<tiles.length;i+=3) {
            BlockPos column=new BlockPos(tiles[i],tiles[i+1],tiles[i+2]);if(!mc.level.hasChunkAt(column))continue;
            GridPos cell=LevelGridWorld.standCellAt(mc.level,column.getX(),column.getZ(),column.getY());if(cell==null)continue;
            BlockPos support=new BlockPos(cell.x(),cell.y()-1,cell.z());
            var shape=mc.level.getBlockState(support).getCollisionShape(mc.level,support);
            if(!shape.isEmpty())placed.add(new Surface(cell.x(),support.getY()+shape.max(Direction.Axis.Y),cell.z()));
        }
        surfaces=List.copyOf(placed);updateLabel();
    }
    private static boolean fresh() {
        Minecraft mc=Minecraft.getInstance();
        return preview!=null&&aiming(mc)&&SkillClientState.selected().ready()&&preview.session().equals(SkillClientState.session())
                &&preview.revision()==SkillClientState.revision()&&preview.skill().equals(SkillClientState.selected().id())
                &&preview.target().equals(target(BattleAim.pickAtCursor()))&&pendingOrigin.equals(BattleClientState.playerCell())
                &&pendingAction==BattleClientState.actionCount&&pendingAp==BattleClientState.ap
                &&ItemStack.matches(pendingWeapon,mc.player.getMainHandItem())&&mc.level.getGameTime()-preview.serverTick()<MAX_AGE;
    }
    public static SkillPreviewResponsePacket preview(){return fresh()?preview:null;}
    public static void execute(HitResult hit) {
        Minecraft mc=Minecraft.getInstance();SkillView view=SkillClientState.selected();SkillTarget target=target(hit);
        if(!aiming(mc)||view==null||!view.ready()||target==null||!fresh()||!preview.valid()||!preview.target().equals(target))return;
        if(mc.getConnection()==null||!mc.getConnection().hasChannel(SkillCastPacket.TYPE))return;
        BattleScreenInput.syncSelectedSlot();
        PacketDistributor.sendToServer(new SkillCastPacket(SkillClientState.session(),BattleClientState.turnEpoch,mc.level.dimension().location(),view.id(),target));
        SkillClientState.cancelSelection();mc.setScreen(null);
    }
    public static void updateLabel() {
        Minecraft mc=Minecraft.getInstance();
        if(BattleClientState.mode!=BattleMode.SKILL||!(mc.screen instanceof BattleAim.BattleAimScreen screen)||screen.getLinkedDocument()==null)return;
        SkillView view=SkillClientState.selected();
        String text=view==null?"技能已不可用":!view.ready()?view.reason():!fresh()?"等待有效目标预览…"
                :!preview.valid()?preview.reason():"本次 "+preview.apCost()+" AP · "+SkillClientState.number(preview.mpCost())+" MP · 影响 "+preview.affected().length+" 个单位";
        var label=screen.getLinkedDocument().getElementById("aim-target");
        if(label!=null&&!text.equals(label.getTextContent()))label.setTextContent(text);
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS||!aiming(Minecraft.getInstance()))return;
        updateLabel();Minecraft mc=Minecraft.getInstance();var pose=event.getPoseStack();var camera=event.getCamera().getPosition();
        pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
        var buffers=mc.renderBuffers().bufferSource();boolean ready=fresh()&&preview.valid();
        if(ready) {
            VertexConsumer quads=buffers.getBuffer(RenderType.debugQuads());var matrix=pose.last().pose();
            for(Surface surface:surfaces) {
                float x=surface.x()+.08F,z=surface.z()+.08F,y=(float)(surface.y()+.03);
                quads.addVertex(matrix,x,y,z).setColor(.72F,.36F,1F,.3F);
                quads.addVertex(matrix,x+.84F,y,z).setColor(.72F,.36F,1F,.3F);
                quads.addVertex(matrix,x+.84F,y,z+.84F).setColor(.72F,.36F,1F,.3F);
                quads.addVertex(matrix,x,y,z+.84F).setColor(.72F,.36F,1F,.3F);
            }
            buffers.endBatch(RenderType.debugQuads());
            VertexConsumer lines=buffers.getBuffer(RenderType.lines());
            for(int id:preview.affected()) {
                var entity=mc.level.getEntity(id);if(entity==null||!entity.isAlive()||!BattleClientState.participantHealth.containsKey(id))continue;
                Vec3 offset=entity.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false)).subtract(entity.position());
                boolean friendly=SkillClientState.friendly(id);
                LevelRenderer.renderLineBox(pose,lines,entity.getBoundingBox().move(offset).inflate(.025),friendly?.35F:1F,friendly?1F:.45F,.75F,1F);
            }
            buffers.endBatch(RenderType.lines());
        } else if(BattleAim.pickAtCursor() instanceof EntityHitResult hit&&hit.getEntity() instanceof LivingEntity entity) {
            var lines=buffers.getBuffer(RenderType.lines());
            LevelRenderer.renderLineBox(pose,lines,entity.getBoundingBox().inflate(.02),1F,.25F,.3F,.9F);
            buffers.endBatch(RenderType.lines());
        }
        pose.popPose();
    }
    public static void clearPreview() {
        preview=null;surfaces=List.of();pendingTarget=null;pendingSkill=null;pendingSession=null;pendingOrigin=null;
        pendingWeapon=null;pendingId=0;sent=Long.MIN_VALUE;
    }
}
