package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.AttackRangeService;
import com.tactical.neoforge.client.AttackRangeOverlay;
import com.tactical.neoforge.client.ClientPayloadGate;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Independent versioned preview channel; leaves the shared battle intent registrar untouched. */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class AttackRangeNetwork {
    private AttackRangeNetwork() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(AttackRangeRequestPacket.TYPE, AttackRangeRequestPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) AttackRangeService.request(player, packet);
                }));
        registrar.playToClient(AttackRangeSnapshotPacket.TYPE, AttackRangeSnapshotPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> ClientPayloadGate.apply(arrived, () -> AttackRangeOverlay.accept(packet)));
                });
    }
}
