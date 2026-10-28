package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Render-only camera anchor: neither actor switches nor tracking change the player's view entity. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class CameraDirector {
    private static final CameraFocusTransition FOCUS = new CameraFocusTransition(0.36);
    private static BattleCameraAnchor anchor;
    private static Entity scopedCamera;
    private static boolean cameraScoped;
    private static long previousFrameNanos;

    private CameraDirector() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleClientState.active || minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        if (anchor != null && anchor.level() != minecraft.level) {
            reset();
        }
        if (anchor == null) {
            Entity initial = minecraft.getCameraEntity() != null ? minecraft.getCameraEntity() : minecraft.player;
            Vec3 eye = initial.getEyePosition(1.0F);
            FOCUS.seed(point(eye));
            anchor = new BattleCameraAnchor(minecraft.level);
            anchor.place(eye, initial, 1.0F);
            previousFrameNanos = 0;
        }
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel && (anchor == null || anchor.level() == event.getLevel())) {
            reset();
        }
    }

    /** Explicit logout/level-unload hook; safe even after the player has gone. */
    public static void reset() {
        restoreRenderScope();
        anchor = null;
        previousFrameNanos = 0;
        FOCUS.reset();
    }

    /**
     * Called around GameRenderer's Camera.setup invocation, before collision and culling.
     * The try/finally restores the view/config even if another camera mod throws.
     * Keeping Minecraft.cameraEntity unchanged outside this synchronous render scope is
     * essential: LocalPlayer.sendPosition skips yaw/position packets for non-player cameras.
     */
    public static void setupCamera(Camera camera, BlockGetter level, Entity viewSource,
                                   boolean detached, boolean mirrored, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleClientState.active || minecraft.player == null || minecraft.level == null) {
            reset();
            camera.setup(level, viewSource, detached, mirrored, partialTick);
            return;
        }
        tick();
        Entity target = minecraft.player;
        if (BattleClientState.actorEntityId >= 0) {
            Entity actor = minecraft.level.getEntity(BattleClientState.actorEntityId);
            if (actor != null && !actor.isRemoved()) {
                target = actor;
            }
        }
        Entity hovered=BattleEntityLocator.target();
        if(hovered!=null)target=hovered;
        Vec3 eye = target.getEyePosition(partialTick);
        if (target == minecraft.player) {
            // Interpolate authoritative player movement exactly once, not with a second follow lerp.
            Vec3 feet = PlayerStepAnimation.interpolatedPosition(partialTick);
            if (feet != null) {
                eye = feet.add(0, target.getEyeHeight(), 0);
            }
        }
        long now = System.nanoTime();
        double seconds = previousFrameNanos == 0 || minecraft.isPaused()
                ? 0 : Math.max(0, (now - previousFrameNanos) * 1.0e-9);
        previousFrameNanos = now;
        CameraFocusTransition.Point focus = FOCUS.update(target, point(eye), seconds);
        // Copy the view direction; never write player yaw, pitch, position, or a network packet.
        anchor.place(new Vec3(focus.x(), focus.y(), focus.z()), viewSource, partialTick);
        scopedCamera = minecraft.getCameraEntity();
        cameraScoped = true;
        try {
            // Field assignment deliberately avoids setCameraEntity's shader/post-effect changes.
            minecraft.cameraEntity = anchor;
            DungeonsIsoCompat.beginCameraSetup();
            camera.setup(level, anchor, detached, mirrored, partialTick);
        } finally {
            try {
                // First-person rendering hides the real camera entity's head/body. Leaving the
                // proxy here would render the local player's face over a self-focused camera.
                // Restore identity, not position: the smoothed render focus remains untouched.
                ((CameraViewEntityAccess) camera).tactical$setViewEntity(viewSource);
            } finally {
                restoreRenderScope();
            }
        }
    }

    /** The proxy stores eye coordinates; ignore vanilla's cached eye-height easing for it. */
    public static Vec3 cameraAnchorPosition(Entity cameraEntity) {
        return cameraEntity == anchor && anchor != null ? anchor.position() : null;
    }

    private static void restoreRenderScope() {
        DungeonsIsoCompat.restore();
        if (cameraScoped) {
            Minecraft minecraft = Minecraft.getInstance();
            // Never resurrect an entity from a previous dimension/disconnected client level.
            minecraft.cameraEntity = scopedCamera != null && !scopedCamera.isRemoved()
                    && scopedCamera.level() == minecraft.level ? scopedCamera : minecraft.player;
            scopedCamera = null;
            cameraScoped = false;
        }
    }

    private static CameraFocusTransition.Point point(Vec3 position) {
        return new CameraFocusTransition.Point(position.x, position.y, position.z);
    }
}
