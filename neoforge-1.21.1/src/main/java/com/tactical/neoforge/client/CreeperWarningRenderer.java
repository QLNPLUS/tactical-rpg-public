package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.BlastWarningGeometry;
import com.tactical.neoforge.network.CreeperTelegraphPacket.CreeperWarning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Red inset tops for exposed collision blocks possibly reached by any prepared creeper. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class CreeperWarningRenderer {
    private static final float INSET = 0.13F;
    private static final int BLOCK_BUDGET = 16_384;
    private record Surface(int x, double y, int z) { }
    private static List<CreeperWarning> sources = List.of();
    private static Set<Surface> surfaces = Set.of();
    private static final Set<Surface> building = new HashSet<>();
    private static final ArrayDeque<Scan> scans = new ArrayDeque<>();
    private static int cameraChunkX = Integer.MIN_VALUE;
    private static int cameraChunkZ = Integer.MIN_VALUE;
    private static int viewDistance;
    private static int refreshDelay;

    private CreeperWarningRenderer() { }

    public static void clear() {
        sources = List.of();
        surfaces = Set.of();
        building.clear();
        scans.clear();
        cameraChunkX = cameraChunkZ = Integer.MIN_VALUE;
        refreshDelay = 0;
    }

    /** Normal/charged vanilla radii scan in one tick; huge custom NBT radii cannot freeze a frame. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        List<CreeperWarning> warnings = CreeperTelegraphClientState.prepared();
        if (level == null || warnings.isEmpty()) {
            clear();
            return;
        }
        BlockPos camera = mc.gameRenderer.getMainCamera().getBlockPosition();
        int distance = mc.options.renderDistance().get();
        boolean changed = !sources.equals(warnings) || cameraChunkX != camera.getX() >> 4
                || cameraChunkZ != camera.getZ() >> 4 || viewDistance != distance;
        if (changed) {
            sources = List.copyOf(warnings);
            surfaces = Set.of();
            cameraChunkX = camera.getX() >> 4;
            cameraChunkZ = camera.getZ() >> 4;
            viewDistance = distance;
        }
        if (changed || (scans.isEmpty() && --refreshDelay <= 0)) {
            scans.clear();
            building.clear();
            for (CreeperWarning warning : sources) scans.add(new Scan(warning, level, camera, (distance + 1) * 16));
        }
        boolean scanned = !scans.isEmpty();
        int budget = BLOCK_BUDGET;
        while (budget > 0 && !scans.isEmpty()) {
            Scan scan = scans.removeFirst();
            int slice = Math.min(1024, budget);
            for (int i = 0; i < slice && !scan.done; i++) {
                scan.step(level);
                budget--;
            }
            if (!scan.done) scans.addLast(scan);
        }
        if (scanned && scans.isEmpty()) {
            surfaces = Set.copyOf(building);
            refreshDelay = 10;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Set<Surface> visible = surfaces.isEmpty() ? building : surfaces;
        if (visible.isEmpty() || Minecraft.getInstance().level == null) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (Surface surface : visible) {
            float x = surface.x + INSET;
            float y = (float) (surface.y + 0.045);
            float z = surface.z + INSET;
            float size = 1 - 2 * INSET;
            quads.addVertex(matrix, x, y, z).setColor(1.0F, 0.12F, 0.08F, 0.43F);
            quads.addVertex(matrix, x + size, y, z).setColor(1.0F, 0.12F, 0.08F, 0.43F);
            quads.addVertex(matrix, x + size, y, z + size).setColor(1.0F, 0.12F, 0.08F, 0.43F);
            quads.addVertex(matrix, x, y, z + size).setColor(1.0F, 0.12F, 0.08F, 0.43F);
        }
        buffers.endBatch(RenderType.debugQuads());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (Surface surface : visible) {
            LevelRenderer.renderLineBox(pose, lines, new AABB(surface.x + INSET, surface.y + 0.04, surface.z + INSET,
                    surface.x + 1 - INSET, surface.y + 0.06, surface.z + 1 - INSET), 1.0F, 0.18F, 0.12F, 0.95F);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static final class Scan {
        private final CreeperWarning warning;
        private final int minX, maxX, minY, maxY, maxZ;
        private int x, y, z;
        private boolean done;

        private Scan(CreeperWarning warning, ClientLevel level, BlockPos camera, int visibleRadius) {
            this.warning = warning;
            minX = Math.max((int) Math.floor(warning.x() - warning.radius()) - 1, camera.getX() - visibleRadius);
            maxX = Math.min((int) Math.floor(warning.x() + warning.radius()), camera.getX() + visibleRadius);
            x = minX;
            z = Math.max((int) Math.floor(warning.z() - warning.radius()) - 1, camera.getZ() - visibleRadius);
            maxZ = Math.min((int) Math.floor(warning.z() + warning.radius()), camera.getZ() + visibleRadius);
            minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(warning.y() - warning.radius()) - 1);
            maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.floor(warning.y() + warning.radius()));
            y = minY;
            done = x > maxX || z > maxZ || y > maxY;
        }

        private void step(ClientLevel level) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                y = maxY; // Never load a chunk for the overlay.
            } else if (BlastWarningGeometry.intersectsBlock(warning.x(), warning.y(), warning.z(), warning.radius(), x, y, z)) {
                VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
                if (!shape.isEmpty()) {
                    double top = shape.max(Direction.Axis.Y);
                    VoxelShape above = level.getBlockState(pos.above()).getCollisionShape(level, pos.above());
                    if (top < 1.0 || above.isEmpty() || above.min(Direction.Axis.Y) > 0) building.add(new Surface(x, y + top, z));
                }
            }
            if (++y > maxY) {
                y = minY;
                if (++x > maxX) {
                    x = minX;
                    if (++z > maxZ) done = true;
                }
            }
        }
    }
}
