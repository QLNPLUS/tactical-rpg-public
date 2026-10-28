package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.PlayerStepAnimation;
import com.tactical.neoforge.client.ClientPayloadGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Independent registration; no changes to the shared battle intent registrar. */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class PlayerMovementNetwork {
    private PlayerMovementNetwork() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(PlayerStepPacket.TYPE, PlayerStepPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> ClientPayloadGate.apply(arrived, () -> PlayerStepAnimation.accept(packet)));
                });
    }
}
