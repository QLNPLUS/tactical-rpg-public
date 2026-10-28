package com.tactical.neoforge.battle.ai;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Global factories only. Mutable adapter/entity state is owned by BattleAiSession. */
public final class TacticalAiRegistry {
    private record Registration(EntityType<?> type, Function<Mob, TacticalMobAi> factory) { }
    private static final Map<ResourceLocation, Registration> ADAPTERS = new HashMap<>();

    static {
        register(ResourceLocation.withDefaultNamespace("creeper"), EntityType.CREEPER,
                ignored -> new CreeperTacticalAi());
        register(ResourceLocation.withDefaultNamespace("skeleton"), EntityType.SKELETON,
                SkeletonTacticalAi::new);
        register(ResourceLocation.withDefaultNamespace("phantom"), EntityType.PHANTOM,
                ignored -> new PhantomTacticalAi());
    }

    private TacticalAiRegistry() { }

    /** Register during common setup. An id must name exactly the supplied vanilla/mod entity type. */
    public static synchronized void register(ResourceLocation id, EntityType<?> type,
                                             Function<Mob, TacticalMobAi> factory) {
        Objects.requireNonNull(id, "entity type id");
        Objects.requireNonNull(type, "entity type");
        Objects.requireNonNull(factory, "adapter factory");
        EntityType<?> registered = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity type: " + id));
        if (registered != type) throw new IllegalArgumentException("Entity type/id mismatch: " + id);
        if (ADAPTERS.putIfAbsent(id, new Registration(type, factory)) != null) {
            throw new IllegalStateException("Duplicate tactical AI adapter: " + id);
        }
    }

    public static synchronized TacticalMobAi create(Mob actor) {
        Objects.requireNonNull(actor, "actor");
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType());
        Registration registration = ADAPTERS.get(id);
        if (registration == null) return new GenericMeleeAi();
        if (registration.type != actor.getType()) throw new IllegalStateException("Unregistered actor type: " + id);
        return Objects.requireNonNull(registration.factory.apply(actor), "Adapter factory returned null: " + id);
    }
}
