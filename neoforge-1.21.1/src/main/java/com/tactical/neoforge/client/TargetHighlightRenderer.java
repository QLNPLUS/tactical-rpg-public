package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Highlights the same visible cursor hit and standing cell used by command execution. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class TargetHighlightRenderer {

    private TargetHighlightRenderer() {
    }

    @SubscribeEvent
    public static void onBlockHighlight(RenderHighlightEvent.Block event) {
        if (!BattleClientState.active || BattleClientState.mode != BattleMode.MOVE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || minecraft.screen instanceof BattleAim.BattleAimScreen) {
            // Vanilla uses its own outline ray; MOVE renders the collider-based ground pick below.
            event.setCanceled(true);
        }
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!BattleClientState.active) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            CursorWorldPicker.capture(event);
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.screen != null && !(minecraft.screen instanceof BattleAim.BattleAimScreen)) {
            return;
        }
        HitResult hit = BattleClientState.mode == BattleMode.NONE ? null : BattleAim.pickAtCursor();
        // SkillOverlay owns its target label, including server failure reasons and costs.
        if (BattleClientState.mode != BattleMode.SKILL
                && minecraft.screen instanceof BattleAim.BattleAimScreen screen && screen.getLinkedDocument() != null) {
            var label = screen.getLinkedDocument().getElementById("aim-target");
            String text = "";
            if (BattleClientState.mode == BattleMode.ATTACK) {
                text = hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
                        ? entityHit.getEntity().getDisplayName().getString() : "";
            } else if (BattleClientState.mode == BattleMode.MOVE) {
                GridPos target = CursorWorldPicker.standCell(minecraft, hit);
                text = target == null ? "未指向可站立地面" : "落点 " + target.x() + ", " + target.y() + ", " + target.z()
                        + (BattleClientState.reachable.contains(target) ? " · 可到达" : " · 超出移动范围");
            } else if (BattleClientState.mode == BattleMode.PLACE && hit instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                text = "放置面 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            }
            if (label != null && !text.equals(label.getTextContent())) label.setTextContent(text);
        }
        if (hit == null) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 offset = entity.getPosition(partialTick).subtract(entity.position());
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            boolean attackable = BattleClientState.mode == BattleMode.ATTACK
                    && entity instanceof net.minecraft.world.entity.Mob;
            LevelRenderer.renderLineBox(pose, lines, entity.getBoundingBox().move(offset).inflate(0.004),
                    1.0F, attackable ? 0.75F : 0.3F, 0.3F, 1.0F);
            buffers.endBatch(RenderType.lines());
            pose.popPose();
            return;
        }
        if (!(hit instanceof BlockHitResult blockHit)) {
            pose.popPose();
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        face(matrix, quads, pos, blockHit.getDirection());
        buffers.endBatch(RenderType.debugQuads());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, new AABB(pos).inflate(0.004D), 1.0F, 0.85F, 0.3F, 1.0F);
        if (BattleClientState.mode == BattleMode.MOVE) {
            GridPos cell = CursorWorldPicker.standCell(minecraft, blockHit);
            if (cell != null) {
                AABB box = new AABB(cell.x() + 0.02D, cell.y() + 0.01D, cell.z() + 0.02D,
                        cell.x() + 0.98D, cell.y() + 0.06D, cell.z() + 0.98D);
                boolean ok = BattleClientState.reachable.contains(cell);
                LevelRenderer.renderLineBox(pose, lines, box,
                        ok ? 0.35F : 0.9F, ok ? 0.95F : 0.3F, ok ? 0.55F : 0.3F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void face(Matrix4f matrix, VertexConsumer consumer, BlockPos pos, Direction direction) {
        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();
        float e = 0.002F;
        float r = 1.0F;
        float g = 0.78F;
        float b = 0.25F;
        float a = 0.22F;
        switch (direction) {
            case UP -> quad(matrix, consumer, x, y + 1 + e, z, x + 1, y + 1 + e, z, x + 1, y + 1 + e, z + 1, x, y + 1 + e, z + 1, r, g, b, a);
            case DOWN -> quad(matrix, consumer, x, y - e, z, x, y - e, z + 1, x + 1, y - e, z + 1, x + 1, y - e, z, r, g, b, a);
            case NORTH -> quad(matrix, consumer, x, y, z - e, x, y + 1, z - e, x + 1, y + 1, z - e, x + 1, y, z - e, r, g, b, a);
            case SOUTH -> quad(matrix, consumer, x + 1, y, z + 1 + e, x + 1, y + 1, z + 1 + e, x, y + 1, z + 1 + e, x, y, z + 1 + e, r, g, b, a);
            case WEST -> quad(matrix, consumer, x - e, y, z, x - e, y + 1, z, x - e, y + 1, z + 1, x - e, y, z + 1, r, g, b, a);
            case EAST -> quad(matrix, consumer, x + 1 + e, y, z + 1, x + 1 + e, y + 1, z + 1, x + 1 + e, y + 1, z, x + 1 + e, y, z, r, g, b, a);
            default -> {
            }
        }
    }

    private static void quad(Matrix4f matrix, VertexConsumer consumer,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             float r, float g, float b, float a) {
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
        consumer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a);
    }
}
