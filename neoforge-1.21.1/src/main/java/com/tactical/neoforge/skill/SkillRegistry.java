package com.tactical.neoforge.skill;

import com.google.gson.*;
import com.tactical.neoforge.Tactical;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;

/** Java registration plus atomic datapack/config reload. Failed reloads retain the last working set. */
@EventBusSubscriber(modid=Tactical.MOD_ID)
public final class SkillRegistry {
    private static final Map<ResourceLocation,SkillDefinition> JAVA=new TreeMap<>();
    private static volatile Map<ResourceLocation,SkillDefinition> DATA=Map.of();
    private static volatile Map<ResourceLocation,SkillDefinition> SNAPSHOT=Map.of();
    private static volatile long revision;
    static {
        register(SkillDefinition.builder(ResourceLocation.fromNamespaceAndPath("tactical","backstab")).json(JsonParser.parseString("""
            {"name":"背刺","description":"从背后发动原版近战，技能倍率随等级提高。","max_level":5,
             "aim":"entity","targets":["enemy"],"range":"1.5","ap_cost":"15","mp_cost":"10","cooldown":"1",
             "conditions":["tactical:melee_weapon","tactical:rear"],
             "damage":{"mode":"weapon","multiplier":"1+0.25*level"},
             "enchantments":[{"id":"tactical:backstab","slots":["mainhand"]}]}
            """).getAsJsonObject()).build());
    }
    private SkillRegistry() { }
    public static synchronized void register(SkillDefinition definition) {
        if(JAVA.putIfAbsent(definition.id(),definition)!=null)throw new IllegalArgumentException("Duplicate skill "+definition.id());
        publish(DATA);
    }
    public static SkillDefinition get(ResourceLocation id) {return SNAPSHOT.get(id);}
    public static List<SkillDefinition> all() {return SNAPSHOT.values().stream().toList();}
    public static long revision() {return revision;}
    private static synchronized void publish(Map<ResourceLocation,SkillDefinition> data) {
        var merged=new TreeMap<>(JAVA);merged.putAll(data);
        if(merged.size()>256)throw new IllegalArgumentException("Skill definition limit256 exceeded");
        DATA=Map.copyOf(data);SNAPSHOT=Collections.unmodifiableMap(merged);revision++;
    }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(),"tactical_skills") {
            @Override protected void apply(Map<ResourceLocation,JsonElement> resources,ResourceManager manager,ProfilerFiller profiler) {
                try {
                    Map<ResourceLocation,SkillDefinition> definitions=new TreeMap<>();
                    for(var entry:resources.entrySet())definitions.put(entry.getKey(),SkillDefinition.parse(entry.getKey(),entry.getValue().getAsJsonObject()));
                    Path directory=FMLPaths.CONFIGDIR.get().resolve("tactical/skills");
                    if(Files.isDirectory(directory))try(var paths=Files.list(directory)) {
                        for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                            if(Files.size(path)>262144)throw new IllegalArgumentException("Skill JSON exceeds256KiB "+path.getFileName());
                            JsonObject json=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                            String name=path.getFileName().toString().replaceFirst("\\.json$","");
                            ResourceLocation id=json.has("id")?ResourceLocation.parse(json.get("id").getAsString()):ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,name);
                            definitions.put(id,SkillDefinition.parse(id,json));
                        }
                    }
                    publish(definitions);
                    Tactical.LOGGER.info("[Tactical] loaded {} skill definitions, revision {}",SNAPSHOT.size(),revision);
                } catch(RuntimeException|IOException failure) {
                    Tactical.LOGGER.error("[Tactical] invalid skill reload; retaining previous working definitions",failure);
                }
            }
        });
    }
}
