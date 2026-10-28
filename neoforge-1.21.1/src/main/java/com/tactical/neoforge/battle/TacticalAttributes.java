package com.tactical.neoforge.battle;

import com.tactical.core.combat.ApCalculator;
import com.tactical.neoforge.Tactical;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Synced native attributes. max_ap retains its percentage semantics across the 0.20.0 migration. */
public final class TacticalAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, Tactical.MOD_ID);
    public static final DeferredHolder<Attribute, Attribute> MAX_AP = ATTRIBUTES.register("max_ap",
            () -> new RangedAttribute("attribute.tactical.max_ap", 100.0, 1.0, 10000.0).setSyncable(true));
    public static final DeferredHolder<Attribute, Attribute> AP_RECOVERY = ATTRIBUTES.register("ap_recovery",
            () -> new RangedAttribute("attribute.tactical.ap_recovery", 50.0, 0.0, 1_000_000.0).setSyncable(true));
    public static final DeferredHolder<Attribute, Attribute> TURN_CHARGE_SPEED = ATTRIBUTES.register("turn_charge_speed",
            () -> new RangedAttribute("attribute.tactical.turn_charge_speed", 1.0, 0.01, 100.0).setSyncable(true));

    private TacticalAttributes() {}

    /** Must be called from the mod constructor on both physical distributions. */
    public static void register(IEventBus modBus) {
        ATTRIBUTES.register(modBus);
        modBus.addListener(TacticalAttributes::addToLivingTypes);
    }

    private static void addToLivingTypes(EntityAttributeModificationEvent event) {
        // Includes PLAYER and every modded living type with an attribute supplier.
        for (var type : event.getTypes()) {
            event.add(type, MAX_AP);
            event.add(type, AP_RECOVERY);
            event.add(type, TURN_CHARGE_SPEED);
        }
    }

    /** Saved max_ap=100 continues to mean 100%; the baseline is now 150, independent of speed. */
    public static int maxAp(LivingEntity entity) {
        double scale = entity.getAttribute(MAX_AP) == null ? 100.0 : entity.getAttributeValue(MAX_AP);
        return Math.max(1, (int) Math.round(ApCalculator.defaultMaxAp() * (scale / 100.0)));
    }

    /** Absolute AP restored only at subsequent own turns, including native modifiers. */
    public static int apRecovery(LivingEntity entity) {
        return Math.max(0, (int) Math.round(entity.getAttribute(AP_RECOVERY) == null
                ? ApCalculator.AP_RECOVERY : entity.getAttributeValue(AP_RECOVERY)));
    }

    public static double chargeMultiplier(LivingEntity entity) {
        return entity.getAttribute(TURN_CHARGE_SPEED) == null ? 1.0 : entity.getAttributeValue(TURN_CHARGE_SPEED);
    }

    /** baseInitiative is the original attack/movement initiative including its fixed battle roll. */
    public static double chargeRate(LivingEntity entity, double baseInitiative) {
        return Math.max(1.0, baseInitiative) * chargeMultiplier(entity);
    }
}
