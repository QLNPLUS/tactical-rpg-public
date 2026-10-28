package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tactical.core.combat.BattleRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Draws the battle zone boundary as a ring on the ground. */
public final class BarrierRenderer {

    private static final int SEGMENTS = 96;

    private BarrierRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!BattleClientState.active) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
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
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        double centerX = BattleClientState.centerX + 0.5D;
        double centerZ = BattleClientState.centerZ + 0.5D;
        double radius = BattleClientState.zoneRadius + 0.5D;
        float y = BattleClientState.centerY + 0.05F;

        for (int i = 0; i < SEGMENTS; i++) {
            double a1 = i * 2.0D * Math.PI / SEGMENTS;
            double a2 = (i + 1) * 2.0D * Math.PI / SEGMENTS;
            float x1 = (float) (centerX + Math.cos(a1) * radius);
            float z1 = (float) (centerZ + Math.sin(a1) * radius);
            float x2 = (float) (centerX + Math.cos(a2) * radius);
            float z2 = (float) (centerZ + Math.sin(a2) * radius);
            lines.addVertex(matrix, x1, y, z1).setColor(0.35F, 0.75F, 1.0F, 0.55F).setNormal(0.0F, 1.0F, 0.0F);
            lines.addVertex(matrix, x2, y, z2).setColor(0.35F, 0.75F, 1.0F, 0.55F).setNormal(0.0F, 1.0F, 0.0F);
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
