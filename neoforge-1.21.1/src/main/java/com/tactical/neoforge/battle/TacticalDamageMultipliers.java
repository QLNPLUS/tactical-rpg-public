package com.tactical.neoforge.battle;

import com.tactical.core.combat.PositionalDamage;
import com.tactical.neoforge.TacticalConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Server damage extension point. Caller filters battle melee/arrows and applies defense separately. */
public final class TacticalDamageMultipliers {
    public enum Stage { DIRECTION, HEIGHT, SKILL }

    /** Geometry is snapshotted by the caller. Source XZ points target -> attack/launch origin.
     * For mobs use physical body yaw. Arrow feet Y is captured at launch, never at impact. */
    public record Context(LivingEntity attacker, LivingEntity target, DamageSource damageSource,
                          double targetYaw, double sourceDirectionX, double sourceDirectionZ,
                          double sourceFeetY, double targetFeetY, boolean originKnown) { }

    /** Independent buckets; total excludes armor/defense and any vanilla damage processing. */
    public record Breakdown(PositionalDamage.Sector sector, double direction, double height, double skill) {
        public Breakdown {
            Objects.requireNonNull(sector, "sector");
            if (!PositionalDamage.validFactor(direction) || !PositionalDamage.validFactor(height)
                    || !PositionalDamage.validFactor(skill) || direction > PositionalDamage.MAX_FACTOR
                    || height > PositionalDamage.MAX_FACTOR || skill > PositionalDamage.MAX_FACTOR) {
                throw new IllegalArgumentException("Invalid damage breakdown");
            }
        }
        public double total() { return direction * height * skill; }
    }

    @FunctionalInterface public interface Modifier {
        void modify(Context context, TacticalDamageEvent event);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(TacticalDamageMultipliers.class);
    private static final Map<ResourceLocation, Modifier> MODIFIERS = new ConcurrentSkipListMap<>();
    private TacticalDamageMultipliers() { }

    /** Register during mod startup; identifiers define deterministic execution order. */
    public static void register(ResourceLocation id, Modifier modifier) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        if (MODIFIERS.putIfAbsent(id, modifier) != null) {
            throw new IllegalArgumentException("Duplicate tactical damage modifier " + id);
        }
    }

    /** Removes only the modifier owned by this identifier, useful for fixture cleanup or reload. */
    public static boolean unregister(ResourceLocation id) {
        return id != null && MODIFIERS.remove(id) != null;
    }

    /** Computes base buckets, invokes registered modifiers, then posts exactly one NeoForge event. */
    public static Breakdown evaluate(Context context) {
        Objects.requireNonNull(context, "context");
        TacticalConfig c = TacticalConfig.INSTANCE;
        double min = c.damageHeightMin.get(), max = c.damageHeightMax.get();
        PositionalDamage.Rules rules = new PositionalDamage.Rules(c.damageDirectionFront.get(),
                c.damageDirectionSide.get(), c.damageDirectionRear.get(), c.damageFrontHalfAngle.get(),
                c.damageRearHalfAngle.get(), c.damageHeightPerLevel.get(), Math.min(min, max), Math.max(min, max));
        return evaluate(context, rules);
    }

    /** Explicit rules overload supports native tests without depending on a loaded config file. */
    public static Breakdown evaluate(Context context, PositionalDamage.Rules rules) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rules, "rules");
        PositionalDamage.Sector sector = context.originKnown()
                ? PositionalDamage.sector(context.targetYaw(), context.sourceDirectionX(), context.sourceDirectionZ(),
                    rules.frontHalfAngle(), rules.rearHalfAngle()) : PositionalDamage.Sector.UNKNOWN;
        Breakdown base = new Breakdown(sector, PositionalDamage.direction(sector, rules),
                context.originKnown() ? PositionalDamage.height(context.sourceFeetY(), context.targetFeetY(), rules) : 1, 1);
        TacticalDamageEvent event = new TacticalDamageEvent(context, base);
        MODIFIERS.forEach((id, modifier) -> {
            Breakdown before = event.breakdown();
            try {
                modifier.modify(context, event);
            } catch (RuntimeException exception) {
                event.restore(before);
                LOGGER.error("Tactical damage modifier {} failed; its changes were discarded", id, exception);
            }
        });
        try {
            NeoForge.EVENT_BUS.post(event);
        } catch (RuntimeException exception) {
            LOGGER.error("Tactical damage event listener failed; retaining validated factors", exception);
        }
        return event.breakdown();
    }
}
