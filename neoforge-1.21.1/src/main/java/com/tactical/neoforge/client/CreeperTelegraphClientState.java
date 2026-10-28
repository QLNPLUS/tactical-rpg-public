package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.CreeperTelegraphPacket;
import com.tactical.neoforge.network.CreeperTelegraphPacket.CreeperWarning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.List;
import java.util.UUID;

/** Main-thread full snapshot mirror. Entity swelling uses vanilla synced entity data, not this cache. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class CreeperTelegraphClientState {
    private static ClientLevel level;
    private static UUID battleId;
    private static List<CreeperWarning> prepared = List.of();

    private CreeperTelegraphClientState() { }

    public static void accept(CreeperTelegraphPacket packet) {
        // A warning - or a clear - of any other membership must never overwrite this one.
        if (!BattleMembership.matches(packet.membershipServerEpoch(), packet.membershipGeneration(),
                packet.battleId())) return;
        Minecraft mc = Minecraft.getInstance();
        if (!packet.active() || mc.level == null || mc.player == null
                || !mc.level.dimension().location().equals(packet.dimension())) {
            // Only this encounter's clear removes this encounter's warning.
            if (BattleMembership.matches(packet.membershipServerEpoch(), packet.membershipGeneration(),
                    packet.battleId())) clear();
            return;
        }
        if (level != mc.level || !packet.battleId().equals(battleId)) clear();
        level = mc.level;
        battleId = packet.battleId();
        prepared = packet.creepers().stream().filter(CreeperWarning::preparing).toList();
    }

    public static List<CreeperWarning> prepared() { return prepared; }

    public static void clear() {
        level = null;
        battleId = null;
        prepared = List.of();
        CreeperWarningRenderer.clear();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (level != null && (mc.level != level || mc.player == null || !mc.player.isAlive())) {
            clear();
            return;
        }
        if (level == null) return;
        prepared = prepared.stream().filter(warning -> {
            Entity entity = level.getEntity(warning.entityId());
            // Keep warnings for entities not currently tracked; never reuse an entity id's old warning.
            return entity == null || (entity.getUUID().equals(warning.uuid()) && entity.isAlive() && !entity.isRemoved());
        }).toList();
        CreeperWarningRenderer.tick();
    }

    @SubscribeEvent public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { clear(); }
    @SubscribeEvent public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    @SubscribeEvent public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == level) clear();
    }
}
