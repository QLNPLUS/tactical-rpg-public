package com.tactical.neoforge.client;

import com.tactical.core.grid.GridTrajectory;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.PlayerStepPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Pure trajectory interpolation for the owning player; no camera smoothing or UI. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class PlayerStepAnimation {
    private static GridTrajectory trajectory;
    private static ClientLevel level;
    private static LocalPlayer actor;
    private static int elapsed;
    private static int previousElapsed;
    private static Float facing;
    private static Float idleYaw;

    private PlayerStepAnimation() { }

    public static void accept(PlayerStepPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!BattleClientState.active || mc.player == null || !mc.player.isAlive() || mc.level == null) return;
        level = mc.level;
        actor = mc.player;
        if (packet.path().isEmpty()) {
            trajectory = null;
            facing = packet.yaw();
            idleYaw = packet.yaw();
            applyFacing(actor);
            // End the motion trajectory; idleYaw separately preserves the committed
            // battle facing. The facing screen explicitly overrides that pose.
            facing = null;
            return;
        }
        trajectory = new GridTrajectory(packet.start(), packet.path(), packet.stepTicks(), packet.drop());
        elapsed = previousElapsed = 0;
        facing = trajectory.yaw(0);
        applyPosition(actor, trajectory.sample(0), trajectory.sample(0));
        applyFacing(actor);
    }

    /** World-space feet, identical to vanilla entity interpolation; null when inactive. */
    public static Vec3 interpolatedPosition(float partialTick) {
        if (!valid() || trajectory == null) return null;
        return vector(trajectory.sample(previousElapsed + (elapsed - previousElapsed) * partialTick));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        if (!valid()) return;
        if (trajectory != null && elapsed >= trajectory.duration() && !BattleClientState.moving) {
            trajectory = null;
            facing = null;
        }
        if (trajectory != null) {
            actor.setDeltaMovement(Vec3.ZERO);
            actor.setOnGround(true);
        }
        applyFacing(actor);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ClientTickEvent.Post event) {
        if (!valid()) return;
        if (trajectory != null) {
            GridTrajectory.Position previous = trajectory.sample(elapsed);
            previousElapsed = elapsed;
            elapsed = Math.min(trajectory.duration(), elapsed + 1);
            applyPosition(actor, previous, trajectory.sample(elapsed));
            facing = trajectory.yaw(Math.max(0, elapsed - 1));
            idleYaw = facing;
            actor.setDeltaMovement(Vec3.ZERO);
            actor.setOnGround(true);
            actor.fallDistance = 0;
        }
        applyFacing(actor);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beforeRender(RenderFrameEvent.Pre event) {
        if (valid()) applyFacing(actor);
    }

    public static void reset() {
        trajectory = null;
        actor = null;
        level = null;
        facing = idleYaw = null;
        elapsed = previousElapsed = 0;
    }

    private static boolean valid() {
        Minecraft mc = Minecraft.getInstance();
        if (!BattleClientState.active || mc.player == null || !mc.player.isAlive() || mc.level == null
                || (actor != null && (mc.player != actor || mc.level != level))) {
            trajectory = null;
            actor = null;
            level = null;
            facing = null;
            idleYaw = null;
            return false;
        }
        if (actor == null) {
            actor = mc.player;
            level = mc.level;
            idleYaw = actor.getYRot();
        }
        return true;
    }

    private static void applyPosition(LocalPlayer player, GridTrajectory.Position previous,
                                      GridTrajectory.Position current) {
        player.setPos(current.x(), current.y(), current.z());
        player.xo = player.xOld = previous.x();
        player.yo = player.yOld = previous.y();
        player.zo = player.zOld = previous.z();
    }

    private static void applyFacing(LocalPlayer player) {
        if (BattleFacing.isOpen()) return; // Preview owns current + previous pose while its screen is open.
        Float yaw = facing != null ? facing : idleYaw;
        if (yaw == null) return;
        // Dungeons turns the player toward the cursor even with a Screen open.
        // Keep the last authoritative facing for defence/enemy turns, not its mouse-look pose.
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.yRotO = player.yHeadRotO = player.yBodyRotO = yaw;
    }

    private static Vec3 vector(GridTrajectory.Position position) {
        return new Vec3(position.x(), position.y(), position.z());
    }
}
