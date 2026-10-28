package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.CreeperTelegraphClientState;
import com.tactical.neoforge.client.ClientPayloadGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Standalone registration: no edits to the shared battle network registrar. */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CreeperTelegraphNetwork {
    private CreeperTelegraphNetwork() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(CreeperTelegraphPacket.TYPE, CreeperTelegraphPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> ClientPayloadGate.apply(arrived,
                            () -> CreeperTelegraphClientState.accept(packet)));
                });
    }
}
