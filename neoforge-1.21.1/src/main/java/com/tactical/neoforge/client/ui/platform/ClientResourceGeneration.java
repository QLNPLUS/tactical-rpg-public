package com.tactical.neoforge.client.ui.platform;

import com.tactical.neoforge.Tactical;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many times the client's resources have finished reloading.
 *
 * <p>A cached layout depends on text metrics, and those can change when a resource pack or the
 * language changes - including changes that keep the same {@code Font} object, so comparing the font
 * instance is not enough. The installed NeoForge version fires
 * {@link RegisterClientReloadListenersEvent} on the mod bus, which lets a mod join the client resource
 * reload; this listener does no work of its own and only counts the reloads it is applied in, which is
 * exactly the completion signal a layout needs. Reading the counter is one volatile load, so nothing
 * is scanned per frame.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientResourceGeneration {

    private static final AtomicLong GENERATION = new AtomicLong(1L);

    private ClientResourceGeneration() {
    }

    /** Starts at 1 and moves once per completed client resource reload or pack/language change. */
    public static long current() {
        return GENERATION.get();
    }

    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager manager, ProfilerFiller profiler) {
                // Applying on the client thread is the moment the new resources are in use.
                GENERATION.incrementAndGet();
            }
        });
    }
}
