package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Compact, camera-facing health bars anchored to the rendered battle participants. */
public final class HealthBarRenderer {
    private static final int OUTLINE = 0x090D12;
    private static final int FRAME = 0x53616B;
    private static final int TRACK = 0x192229;
    private static final int ENEMY = 0xD96555;
    private static final int OWNER = 0x51B7A3;
    private static final int FOCUS = 0xFFD445;
    private static final float TRACK_WIDTH = 1.0F;
    private static final float TRACK_HEIGHT = 0.10F;
    private static final float TRACK_LEFT = -TRACK_WIDTH / 2.0F;
    private static final float TRACK_BOTTOM = -TRACK_HEIGHT / 2.0F;

    private HealthBarRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || !BattleClientState.active || BattleClientState.participantHealth.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive()
                || minecraft.player.isSpectator() || minecraft.player.level() != minecraft.level) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        Quaternionf rotation = event.getCamera().rotation();
        int hoveredId = BattleEntityLocator.hoveredId();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        // One shared batch for all bars; never flush unrelated world render types.
        VertexConsumer quads = null;

        for (var entry : BattleClientState.participantHealth.entrySet()) {
            Entity entity = minecraft.level.getEntity(entry.getKey());
            int[] health = entry.getValue();
            if (entity == null || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()
                    || entity.level() != minecraft.level || health == null || health.length < 2) {
                continue;
            }
            // Match LevelRenderer's entity-specific delta, including /tick freeze and pause.
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(
                    !minecraft.level.tickRateManager().isEntityFrozen(entity));
            Vec3 position = renderedPosition(minecraft, entity, partialTick);
            float fraction = health[1] <= 0 ? 0.0F : Mth.clamp(health[0] / (float) health[1], 0.0F, 1.0F);
            boolean hovered = entity.getId() == hoveredId;
            boolean current = entity.getId() == BattleClientState.actorEntityId;
            if (quads == null) {
                quads = buffers.getBuffer(RenderType.debugQuads());
            }

            pose.pushPose();
            try {
                // Subtract the camera in double precision exactly once, before billboard rotation.
                pose.translate(position.x - camera.x, position.y - camera.y + entity.getBbHeight() + 0.28D,
                        position.z - camera.z);
                pose.mulPose(rotation);
                drawBar(pose.last().pose(), quads, fraction, entity == minecraft.player, current, hovered);
            } finally {
                pose.popPose();
            }
        }

        if (quads != null) {
            buffers.endBatch(RenderType.debugQuads());
        }
    }

    private static Vec3 renderedPosition(Minecraft minecraft, Entity entity, float partialTick) {
        // LevelRenderer.renderEntity uses xOld/yOld/zOld; Entity.getPosition uses xo/yo/zo.
        // PlayerStepAnimation.applyPosition writes the previous trajectory sample into
        // xOld too, so this also follows the owning player's actual model. Re-sampling
        // its curved trajectory here would disagree with the model's between-tick lerp.
        // Mob network interpolation has already updated the current tick's position.
        Vec3 feet = entity.tickCount == 0 ? entity.position() : new Vec3(
                Mth.lerp((double) partialTick, entity.xOld, entity.getX()),
                Mth.lerp((double) partialTick, entity.yOld, entity.getY()),
                Mth.lerp((double) partialTick, entity.zOld, entity.getZ()));
        // EntityRenderDispatcher adds this after world-position interpolation (e.g. crouching).
        return feet.add(minecraft.getEntityRenderDispatcher().getRenderer(entity).getRenderOffset(entity, partialTick));
    }

    private static void drawBar(Matrix4f matrix, VertexConsumer quads, float fraction,
                                boolean owner, boolean current, boolean hovered) {
        // Local +Z faces the camera. Distinct depths prevent coplanar fill/frame z-fighting.
        quad(matrix, quads, -0.54F, -0.09F, 1.08F, 0.18F, 0.000F, OUTLINE, 0.96F);
        quad(matrix, quads, -0.52F, -0.07F, 1.04F, 0.14F, 0.003F,
                hovered || current ? FOCUS : FRAME, hovered ? 1.0F : current ? 0.82F : 0.70F);
        quad(matrix, quads, TRACK_LEFT, TRACK_BOTTOM, TRACK_WIDTH, TRACK_HEIGHT, 0.006F, TRACK, 1.0F);

        float fillWidth = TRACK_WIDTH * fraction;
        if (fillWidth > 0.0F) {
            quad(matrix, quads, TRACK_LEFT, TRACK_BOTTOM, fillWidth, TRACK_HEIGHT, 0.009F,
                    owner ? OWNER : ENEMY, 1.0F);
        }
    }

    private static void quad(Matrix4f matrix, VertexConsumer consumer,
                             float x, float y, float width, float height, float z, int rgb, float alpha) {
        float red = ((rgb >> 16) & 255) / 255.0F;
        float green = ((rgb >> 8) & 255) / 255.0F;
        float blue = (rgb & 255) / 255.0F;
        consumer.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, x + width, y, z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, x + width, y + height, z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, x, y + height, z).setColor(red, green, blue, alpha);
    }
}
