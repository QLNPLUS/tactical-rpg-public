package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.core.zone.BattleZone;
import com.tactical.core.zone.BoundedBattleZone;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Register a zone factory during mod startup; rendering never defines authoritative bounds. */
public final class BattleZones {
    @FunctionalInterface public interface Factory { BattleZone create(GridPos center, int radius, int halfHeight); }
    private static final Map<ResourceLocation, Factory> FACTORIES = new ConcurrentHashMap<>();
    static {
        register(ResourceLocation.parse("tactical:cylinder"), (c,r,h)->new BoundedBattleZone(c,r,h,false));
        register(ResourceLocation.parse("tactical:box"), (c,r,h)->new BoundedBattleZone(c,r,h,true));
    }
    private BattleZones() {}
    public static void register(ResourceLocation id, Factory factory) {
        if (id == null || factory == null || FACTORIES.putIfAbsent(id, factory) != null)
            throw new IllegalArgumentException("Duplicate/invalid battle zone " + id);
    }
    public static BattleZone create(String id, GridPos center, int radius, int halfHeight) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Factory factory = key == null ? null : FACTORIES.get(key);
        if (factory == null) throw new IllegalArgumentException("Unknown battle zone: " + id);
        return java.util.Objects.requireNonNull(factory.create(center,radius,halfHeight));
    }
}
