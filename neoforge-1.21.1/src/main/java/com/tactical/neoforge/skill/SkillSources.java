package com.tactical.neoforge.skill;

import com.tactical.neoforge.Tactical;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiConsumer;

/** Highest-level merging across persistent player data, equipped components and native enchantments. */
@EventBusSubscriber(modid=Tactical.MOD_ID)
public final class SkillSources {
    public record Grant(ResourceLocation skill,int level) { }
    @FunctionalInterface public interface Provider {void collect(LivingEntity actor,BiConsumer<ResourceLocation,Integer> grants);}
    private static final Map<ResourceLocation,Provider> PROVIDERS=new ConcurrentSkipListMap<>();
    private static final Set<ResourceLocation> WARNED=new HashSet<>();
    private static final EquipmentSlot[] SLOTS={EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
    private SkillSources() { }
    public static void register(ResourceLocation id,Provider provider) {
        if(PROVIDERS.putIfAbsent(Objects.requireNonNull(id),Objects.requireNonNull(provider))!=null)
            throw new IllegalArgumentException("Duplicate skill source "+id);
    }
    public static Map<ResourceLocation,Integer> resolve(LivingEntity actor) {
        Map<ResourceLocation,Integer> result=new TreeMap<>();
        BiConsumer<ResourceLocation,Integer> add=(id,level)->{
            var definition=SkillRegistry.get(id);
            if(definition!=null&&level!=null&&level>0)result.merge(id,Math.min(level,definition.maxLevel()),Math::max);
        };
        nbt(actor.getPersistentData(),add);
        for(EquipmentSlot slot:SLOTS) {
            ItemStack stack=actor.getItemBySlot(slot);if(stack.isEmpty())continue;
            var custom=stack.get(DataComponents.CUSTOM_DATA);
            if(custom!=null)nbt(custom.copyTag(),add);
            var registry=actor.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            for(var definition:SkillRegistry.all())for(var grant:definition.enchantments()) {
                if(!grant.slots().contains(slot))continue;
                registry.getHolder(ResourceKey.create(Registries.ENCHANTMENT,grant.id())).ifPresent(holder->{
                    int level=EnchantmentHelper.getItemEnchantmentLevel(holder,stack);
                    if(level>0)add.accept(definition.id(),level);
                });
            }
        }
        PROVIDERS.forEach((id,provider)->{
            try {
                Map<ResourceLocation,Integer> pending=new TreeMap<>();
                provider.collect(actor,(skill,level)->{if(skill!=null&&level!=null&&level>0)pending.merge(skill,level,Math::max);});
                pending.forEach(add);
            }catch(RuntimeException failure) {
                if(WARNED.add(id))Tactical.LOGGER.error("Skill source {} failed; its grants are ignored for this refresh",id,failure);
            }
        });
        return Map.copyOf(result);
    }
    private static void nbt(CompoundTag data,BiConsumer<ResourceLocation,Integer> add) {
        read(data.getCompound("tactical").getList("skills",Tag.TAG_COMPOUND),add);
        read(data.getList("tactical:skills",Tag.TAG_COMPOUND),add);
    }
    private static void read(net.minecraft.nbt.ListTag list,BiConsumer<ResourceLocation,Integer> add) {
        for(int i=0;i<Math.min(256,list.size());i++) {
            CompoundTag grant=list.getCompound(i);ResourceLocation id=ResourceLocation.tryParse(grant.getString("id"));
            if(id!=null)add.accept(id,grant.contains("level",Tag.TAG_ANY_NUMERIC)?grant.getInt("level"):1);
        }
    }
    /** Permanent learned skills survive a player clone; equipment grants still follow actual equipment. */
    @SubscribeEvent public static void clonePlayer(PlayerEvent.Clone event) {
        if(event.getEntity().level().isClientSide)return;
        var from=event.getOriginal().getPersistentData();var to=event.getEntity().getPersistentData();
        if(from.contains("tactical",Tag.TAG_COMPOUND))to.put("tactical",from.getCompound("tactical").copy());
        if(from.contains("tactical:skills",Tag.TAG_LIST))to.put("tactical:skills",from.getList("tactical:skills",Tag.TAG_COMPOUND).copy());
    }
}
