package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tactical.core.grid.GridPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Draws reachable cells and the player cell as a world-space overlay. */
public final class GridRenderer {

    private static final float CELL_INSET = 0.09F;
    private static final float RED = 0x91 / 255.0F;
    private static final float GREEN = 0xaf / 255.0F;
    private static final float BLUE = 0xd9 / 255.0F;

    private GridRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!BattleClientState.active || BattleClientState.mode==BattleMode.ATTACK
                || "SKILL".equals(BattleClientState.mode.name())
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        if (!BattleClientState.reachable.isEmpty()) {
            VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
            for (GridPos cell : BattleClientState.reachable) {
                float y = cell.y() + 0.02F;
                addQuad(quads, matrix, cell.x() + CELL_INSET, y, cell.z() + CELL_INSET,
                        1.0F - CELL_INSET * 2, RED, GREEN, BLUE, 0.30F);
            }
            buffers.endBatch(RenderType.debugQuads());

            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (GridPos cell : BattleClientState.reachable) {
                LevelRenderer.renderLineBox(pose, lines, cellBox(cell, CELL_INSET, 0.02F),
                        RED, GREEN, BLUE, 0.9F);
            }
            LevelRenderer.renderLineBox(pose, lines, cellBox(BattleClientState.playerCell(), 0.02F, 0.05F),
                    1.0F, 0.85F, 0.3F, 1.0F);
            buffers.endBatch(RenderType.lines());
        }

        pose.popPose();
    }

    private static AABB cellBox(GridPos cell, float inset, float height) {
        return new AABB(
                cell.x() + inset, cell.y() + 0.01D, cell.z() + inset,
                cell.x() + 1.0D - inset, cell.y() + height, cell.z() + 1.0D - inset);
    }

    private static void addQuad(VertexConsumer consumer, Matrix4f matrix,
                                float x, float y, float z, float size,
                                float red, float green, float blue, float alpha) {
        float x2 = x + size;
        float z2 = z + size;
        consumer.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, x2, y, z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, x2, y, z2).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, x, y, z2).setColor(red, green, blue, alpha);
    }
}
