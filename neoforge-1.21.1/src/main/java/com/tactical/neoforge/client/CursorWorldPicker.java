package com.tactical.neoforge.client;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.LevelGridWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Optional;

/** Unprojects the actual world-render matrices; never reconstructs a player sight ray. */
public final class CursorWorldPicker {
    public record Pick(HitResult hit, GridPos standCell) {}

    private static final Pick EMPTY = new Pick(null, null);
    private static ClientLevel level;
    private static Matrix4f inverse;
    private static Vec3 camera;
    private static float partialTick;
    private static int screenWidth;
    private static int screenHeight;
    private static Pick cached = EMPTY;
    private static double cachedX = Double.NaN;
    private static double cachedY = Double.NaN;
    private static ClipContext.Block cachedBlockShape;
    private static BattleMode cachedMode;

    private CursorWorldPicker() {}

    /** Called once per frame by the already registered highlight renderer. */
    public static void capture(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        level = minecraft.level;
        // Includes Dungeons' renderLevel-only orthographic replacement and vanilla view effects.
        Matrix4f combined = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        inverse = Math.abs(combined.determinant()) > 1.0E-12F ? combined.invert() : null;
        camera = event.getCamera().getPosition();
        partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        screenWidth = minecraft.getWindow().getScreenWidth();
        screenHeight = minecraft.getWindow().getScreenHeight();
        cached = EMPTY;
        cachedX = Double.NaN;
        cachedY = Double.NaN;
    }

    public static Pick atCursor(Minecraft minecraft) {
        // MouseHandler uses GLFW window coordinates, not framebuffer pixels or AUI units.
        return atWindow(minecraft, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
    }

    public static Pick atGui(Minecraft minecraft, double mouseX, double mouseY) {
        return atWindow(minecraft,
                mouseX * minecraft.getWindow().getScreenWidth() / minecraft.getWindow().getGuiScaledWidth(),
                mouseY * minecraft.getWindow().getScreenHeight() / minecraft.getWindow().getGuiScaledHeight());
    }

    private static Pick atWindow(Minecraft minecraft, double mouseX, double mouseY) {
        if (minecraft.player == null || minecraft.level == null || minecraft.level != level || inverse == null
                || screenWidth <= 0 || screenHeight <= 0
                || screenWidth != minecraft.getWindow().getScreenWidth()
                || screenHeight != minecraft.getWindow().getScreenHeight()
                || !Double.isFinite(mouseX) || !Double.isFinite(mouseY)
                || mouseX < 0 || mouseY < 0 || mouseX > screenWidth || mouseY > screenHeight) {
            return EMPTY;
        }
        // Commands targeting actors/ground ignore empty collision shapes; placement keeps native outlines.
        ClipContext.Block blockShape = CursorPickPolicy.usesCollider(BattleClientState.mode)
                ? ClipContext.Block.COLLIDER : ClipContext.Block.OUTLINE;
        if (mouseX == cachedX && mouseY == cachedY && blockShape == cachedBlockShape && cachedMode == BattleClientState.mode) {
            return cached;
        }
        cachedX = mouseX;
        cachedY = mouseY;
        cachedBlockShape = blockShape;
        cachedMode = BattleClientState.mode;
        cached = cast(minecraft, (float) (2.0 * mouseX / screenWidth - 1.0),
                (float) (1.0 - 2.0 * mouseY / screenHeight), blockShape);
        return cached;
    }

    private static Pick cast(Minecraft minecraft, float ndcX, float ndcY, ClipContext.Block blockShape) {
        Vector4f near = inverse.transform(new Vector4f(ndcX, ndcY, -1, 1));
        Vector4f far = inverse.transform(new Vector4f(ndcX, ndcY, 1, 1));
        if (Math.abs(near.w) < 1.0E-8F || Math.abs(far.w) < 1.0E-8F) {
            return EMPTY;
        }
        near.div(near.w);
        far.div(far.w);
        if (!near.isFinite() || !far.isFinite()) {
            return EMPTY;
        }
        // Orthographic rays have different origins and parallel directions.
        Vec3 from = camera.add(near.x, near.y, near.z);
        Vec3 to = camera.add(far.x, far.y, far.z);
        BlockHitResult block = level.clip(new ClipContext(from, to,
                blockShape, ClipContext.Fluid.NONE, minecraft.player));
        HitResult nearest = block.getType() == HitResult.Type.BLOCK ? block : null;
        Vec3 visibleEnd = nearest == null ? to : block.getLocation();
        double distance = from.distanceToSqr(visibleEnd);
        // All pickable entities occlude enemies behind them; no arbitrary 0.35-block inflation.
        Entity excluded = BattleClientState.mode == BattleMode.SKILL ? null : minecraft.player;
        for (Entity entity : level.getEntities(excluded, new AABB(from, visibleEnd).inflate(1.0),
                entity -> entity.isAlive() && entity.isPickable() && !entity.isSpectator())) {
            Vec3 renderOffset = entity.getPosition(partialTick).subtract(entity.position());
            AABB bounds = entity.getBoundingBox().move(renderOffset).inflate(entity.getPickRadius());
            Optional<Vec3> intersection = bounds.contains(from) ? Optional.of(from) : bounds.clip(from, to);
            if (intersection.isPresent()) {
                double candidate = from.distanceToSqr(intersection.get());
                if (candidate < distance) {
                    distance = candidate;
                    nearest = new EntityHitResult(entity, intersection.get());
                }
            }
        }
        return new Pick(nearest, nearest instanceof BlockHitResult hit ? resolveStandCell(minecraft, hit) : null);
    }

    public static GridPos standCell(Minecraft minecraft, HitResult hit) {
        if (hit == cached.hit()) {
            return cached.standCell();
        }
        return hit instanceof BlockHitResult block ? resolveStandCell(minecraft, block) : null;
    }

    private static GridPos resolveStandCell(Minecraft minecraft, BlockHitResult hit) {
        if (hit.getType() != HitResult.Type.BLOCK || minecraft.level == null) {
            return null;
        }
        BlockPos support = hit.getBlockPos();
        if (hit.getDirection() == Direction.UP) {
            // Do not silently select another floor through a ceiling.
            BlockPos feet = support.above();
            return LevelGridWorld.isStandable(minecraft.level, feet.getX(), feet.getY(), feet.getZ())
                    ? new GridPos(feet.getX(), feet.getY(), feet.getZ()) : null;
        }
        if (hit.getDirection() == Direction.DOWN) {
            return null;
        }
        BlockPos adjacent = support.relative(hit.getDirection());
        int y = (int) Math.floor(hit.getLocation().y + 1.0E-6);
        return LevelGridWorld.standCellAt(minecraft.level, adjacent.getX(), adjacent.getZ(), y);
    }
}
