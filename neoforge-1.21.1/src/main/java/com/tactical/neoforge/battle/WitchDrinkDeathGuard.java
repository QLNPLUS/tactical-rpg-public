package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Give a witch's borrowed hand item back before vanilla's death pass reads it.
 *
 * <p>A presented drink borrows the main hand. If she dies while drinking, the temporary bottle would be what
 * vanilla drops and the real item would be gone. This hook runs at the lowest priority, i.e. after every
 * cancellation chance and before the drop pass inside {@code LivingEntity#die}, and asks only the encounter that
 * owns her: it settles no dose, heals nothing and moves nothing else.</p>
 *
 * <p>Business stays here rather than in the journal: a refused death still reaches the journal through its own
 * listeners, and this one must never depend on a log being written.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class WitchDrinkDeathGuard {
    private WitchDrinkDeathGuard() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (event.getEntity().level().isClientSide) return;
        LivingEntity dying = event.getEntity();
        ServerBattle battle = BattleManager.containing(dying);
        if (battle == null) return;
        if (battle.restoreWitchDrinkBeforeDeath(dying)) {
            Tactical.LOGGER.info("[Tactical] witch drink pose ended by death for {}; the borrowed hand item was "
                    + "put back before the death drop", dying.getUUID());
        }
    }
}
