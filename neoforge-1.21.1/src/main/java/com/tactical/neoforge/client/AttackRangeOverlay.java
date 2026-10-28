package com.tactical.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.AttackRangeRequestPacket;
import com.tactical.neoforge.network.AttackRangeSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Fresh ATTACK-only geometric terrain tint, with separately validated participant outlines and legend. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class AttackRangeOverlay {
    private static final AttackRangeRefresh refresh = new AttackRangeRefresh();
    private static ClientLevel level;
    private static AimKey currentKey;
    private static AimKey pendingKey;
    private static AimKey snapshotKey;
    private static AttackRangeSnapshotPacket snapshot;
    private static Map<Integer, UUID> targets = Map.of();

    private record ParticipantStamp(int id, UUID uuid, double x, double y, double z,
                                    boolean alive, int health) { }
    private record AimKey(GridPos origin, int kind, int range, int selected, int actionCount, int ap,
                          List<ParticipantStamp> roster, ItemStack weapon) {
        boolean matches(AimKey other) {
            return other != null && origin.equals(other.origin) && kind == other.kind && range == other.range
                    && selected == other.selected && actionCount == other.actionCount && ap == other.ap
                    && roster.equals(other.roster) && ItemStack.matches(weapon, other.weapon);
        }
    }

    private AttackRangeOverlay() { }

    private static boolean aiming(Minecraft mc) {
        return BattleClientState.active && BattleClientState.mode == BattleMode.ATTACK
                && BattleClientState.isPlayerTurn() && !BattleClientState.moving
                && mc.level != null && mc.player != null && mc.player.isAlive() && !mc.player.isRemoved()
                && (mc.screen == null || mc.screen instanceof BattleAim.BattleAimScreen);
    }

    private static AimKey key(Minecraft mc) {
        List<ParticipantStamp> roster = new ArrayList<>();
        for (var entry : BattleClientState.participantHealth.entrySet()) {
            Entity entity = mc.level.getEntity(entry.getKey());
            roster.add(entity == null ? new ParticipantStamp(entry.getKey(), null, 0, 0, 0, false, entry.getValue()[0])
                    : new ParticipantStamp(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                    entity.isAlive() && !entity.isRemoved(), entry.getValue()[0]));
        }
        return new AimKey(BattleClientState.playerCell(), BattleClientState.weaponKind, BattleClientState.weaponRange,
                mc.player.getInventory().selected, BattleClientState.actionCount, BattleClientState.ap,
                List.copyOf(roster), mc.player.getMainHandItem().copy());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!aiming(mc)) {
            clear();
            return;
        }
        if (level != mc.level) clear();
        level = mc.level;
        AimKey next = key(mc);
        if (!next.matches(currentKey)) {
            discardSnapshot();
            pendingKey = null;
            refresh.invalidate();
        }
        currentKey = next;
        long now = level.getGameTime();
        int requestId = refresh.request(now);
        if (requestId != 0) {
            pendingKey = currentKey;
            var stamp=BattleClientState.stamp();
            if (stamp != null) PacketDistributor.sendToServer(new AttackRangeRequestPacket(stamp, requestId));
        }
        if (snapshot != null && !fresh(mc)) discardSnapshot();
    }

    public static void accept(AttackRangeSnapshotPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!BattleMembership.matches(packet.membershipServerEpoch(), packet.membershipGeneration(),
                packet.battleId())) return;
        if (!aiming(mc) || level != mc.level
                || pendingKey == null || !pendingKey.matches(key(mc))
                || !packet.dimension().equals(level.dimension().location())
                || !packet.origin().equals(BattleClientState.playerCell())
                || packet.weaponKind() != BattleClientState.weaponKind
                || !refresh.accept(packet.requestId(), level.getGameTime())) return;
        if (!packet.active()) {
            discardSnapshot();
            return;
        }
        // Client/server world clocks need not agree. Lifetime is bounded from the local request tick.
        snapshot = packet;
        snapshotKey = pendingKey;
        targets = packet.targets().stream().collect(Collectors.toUnmodifiableMap(
                AttackRangeSnapshotPacket.Target::entityId, AttackRangeSnapshotPacket.Target::uuid, (first, second) -> first));
    }

    private static boolean fresh(Minecraft mc) {
        if (snapshot == null || !aiming(mc) || mc.level != level || !snapshotKey.matches(currentKey)
                || !ItemStack.matches(snapshotKey.weapon(), mc.player.getMainHandItem())
                || !snapshot.origin().equals(BattleClientState.playerCell())
                || snapshotKey.actionCount() != BattleClientState.actionCount || snapshotKey.ap() != BattleClientState.ap) return false;
        return refresh.fresh(level.getGameTime());
    }

    /** Current server validation only; consumers must never infer eligibility from terrain tiles. */
    public static boolean validatedTarget(Entity entity) {
        return entity != null && entity.isAlive() && !entity.isRemoved() && fresh(Minecraft.getInstance())
                && entity.getUUID().equals(targets.get(entity.getId()));
    }

    /** Exposes the fresh immutable snapshot for integrations; stale/inactive previews return null. */
    public static AttackRangeSnapshotPacket snapshot() {
        return fresh(Minecraft.getInstance()) ? snapshot : null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !fresh(mc)) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (var tile : snapshot.tiles()) {
            float x = tile.cell().x() + 0.1F;
            float z = tile.cell().z() + 0.1F;
            float y = (float) (tile.surfaceY() + 0.025D);
            quads.addVertex(matrix, x, y, z).setColor(1.0F, 0.47F, 0.12F, 0.27F);
            quads.addVertex(matrix, x + 0.8F, y, z).setColor(1.0F, 0.47F, 0.12F, 0.27F);
            quads.addVertex(matrix, x + 0.8F, y, z + 0.8F).setColor(1.0F, 0.47F, 0.12F, 0.27F);
            quads.addVertex(matrix, x, y, z + 0.8F).setColor(1.0F, 0.47F, 0.12F, 0.27F);
        }
        buffers.endBatch(RenderType.debugQuads());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (var tile : snapshot.tiles()) {
            GridPos cell = tile.cell();
            LevelRenderer.renderLineBox(pose, lines,
                    new AABB(cell.x() + 0.1D, tile.surfaceY() + 0.02D, cell.z() + 0.1D,
                            cell.x() + 0.9D, tile.surfaceY() + 0.045D, cell.z() + 0.9D),
                    1.0F, 0.52F, 0.16F, 0.8F);
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (var target : snapshot.targets()) {
            Entity entity = level.getEntity(target.entityId());
            if (entity == null || !entity.getUUID().equals(target.uuid()) || !entity.isAlive() || entity.isRemoved()) continue;
            Vec3 offset = entity.getPosition(partialTick).subtract(entity.position());
            LevelRenderer.renderLineBox(pose, lines, entity.getBoundingBox().move(offset).inflate(0.025D),
                    0.4F, 1.0F, 0.45F, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && !mc.options.hideGui && UiZoom.showRangeLegend()) drawLegend(event.getGuiGraphics());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreen(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getScreen() instanceof BattleAim.BattleAimScreen && !mc.options.hideGui && UiZoom.showRangeLegend())
            drawLegend(event.getGuiGraphics());
    }

    private static void drawLegend(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (!aiming(mc) || mc.options.hideGui || !UiZoom.showRangeLegend()) return;
        boolean ready = fresh(mc);
        String status = ready ? "绿色实体框：已通过服务器射程与弹道校验（" + targets.size() + "）"
                : "正在更新射程与目标校验…";
        if (ready && snapshot.truncated()) status += " · 预览已达显示边界";
        String hover = "攻击仍需满足AP、弹药及服务器当次校验";
        if (ready && BattleAim.pickAtCursor() instanceof EntityHitResult hit) {
            hover = hit.getEntity().getDisplayName().getString() + (validatedTarget(hit.getEntity())
                    ? " · 已通过射程与弹道校验" : " · 未通过射程/遮挡/弹道或阵营校验");
        }
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        int width = Math.max(40, Math.min(360, mc.getWindow().getGuiScaledWidth() - 24));
        for (String text : List.of("橙色格子：武器几何射程，不代表穿墙或弹道可达", status, hover))
            lines.addAll(mc.font.split(Component.literal(text), width));
        int x = 10;
        int y = Math.max(8, Math.min(mc.getWindow().getGuiScaledHeight() / 2 + 18,
                mc.getWindow().getGuiScaledHeight() - lines.size() * 11 - 12));
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 600);
        graphics.fill(x - 4, y - 4, x + width + 4, y + lines.size() * 11 + 2, 0xD0191C24);
        for (int index = 0; index < lines.size(); index++)
            graphics.drawString(mc.font, lines.get(index), x, y + index * 11, index == 0 ? 0xFFFFB26B : 0xFFE4E9EF, true);
        graphics.pose().popPose();
    }

    private static void discardSnapshot() {
        snapshot = null;
        snapshotKey = null;
        targets = Map.of();
    }

    public static void clear() {
        level = null;
        currentKey = pendingKey = null;
        refresh.invalidate();
        discardSnapshot();
    }

    @SubscribeEvent public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { clear(); }
    @SubscribeEvent public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    @SubscribeEvent public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == level) clear();
    }
}
