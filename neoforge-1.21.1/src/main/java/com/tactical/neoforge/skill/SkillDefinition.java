package com.tactical.neoforge.skill;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.*;

/** Immutable compiled skill definition. Java builders and JSON resources share this schema. */
public record SkillDefinition(ResourceLocation id,String name,String description,int maxLevel,Aim aim,
        Set<Relation> targets,Anchor anchor,boolean area,SkillAreas.Compiled region,
        SkillFormula.Compiled range,SkillFormula.Compiled minRange,SkillFormula.Compiled verticalMin,
        SkillFormula.Compiled verticalMax,boolean lineOfSight,boolean apRamp,SkillFormula.Compiled apCost,
        SkillFormula.Compiled mpCost,SkillFormula.Compiled cooldown,SkillFormula.Compiled requirement,
        Damage damage,List<Effect> effects,List<ResourceLocation> conditions,
        Map<String,SkillBindings.AttributeInput> attributes,List<EnchantmentGrant> enchantments,Action action) {
    public enum Aim { ENTITY, GROUND, SELF }
    public enum Relation { ENEMY, ALLY, SELF }
    public enum Anchor { CASTER, TARGET }
    public enum DamageMode { NONE, FORMULA, WEAPON }
    public enum DurationUnit { TURNS, TICKS }
    public record Damage(DamageMode mode,ResourceLocation type,SkillFormula.Compiled amount) { }
    public record Effect(ResourceLocation id,SkillFormula.Compiled level,SkillFormula.Compiled duration,DurationUnit unit) { }
    public record EnchantmentGrant(ResourceLocation id,Set<EquipmentSlot> slots) {
        public EnchantmentGrant {slots=Set.copyOf(slots);}
    }
    @FunctionalInterface public interface Action {
        /** Custom validators must be side-effect free; an empty string allows reservation and execution. */
        default String validate(SkillCastContext context) {return "";}
        /** Called once after resource reservation. Throwing after mutations cannot undo native world effects. */
        void apply(SkillCastContext context);
    }
    public SkillDefinition {
        Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(description);
        if(maxLevel<1||maxLevel>1024)throw new IllegalArgumentException("Skill max level must be 1..1024");
        if(name.length()>128||description.length()>2048)throw new IllegalArgumentException("Skill text too long");
        targets=Set.copyOf(targets);effects=List.copyOf(effects);conditions=List.copyOf(conditions);
        attributes=Map.copyOf(attributes);enchantments=List.copyOf(enchantments);
        if(targets.isEmpty())throw new IllegalArgumentException("Skill requires allowed target relations");
        if(effects.size()>32||conditions.size()>32)throw new IllegalArgumentException("Skill action list exceeds limit");
    }
    public static Builder builder(ResourceLocation id) {return new Builder(id);}
    public static SkillDefinition parse(ResourceLocation id,JsonObject json) {return parse(id,json,null);}
    private static SkillDefinition parse(ResourceLocation id,JsonObject j,Action action) {
        Map<String,SkillBindings.AttributeInput> attributes=new HashMap<>();
        if(j.has("attributes"))for(var entry:j.getAsJsonObject("attributes").entrySet()) {
            if(entry.getValue().isJsonPrimitive())attributes.put(entry.getKey(),new SkillBindings.AttributeInput(ResourceLocation.parse(entry.getValue().getAsString()),false));
            else {var a=entry.getValue().getAsJsonObject();attributes.put(entry.getKey(),new SkillBindings.AttributeInput(ResourceLocation.parse(text(a,"attribute","")),bool(a,"target",false)));}
        }
        Set<String> variables=SkillBindings.allowedVariables(attributes);
        Set<Relation> relations=EnumSet.noneOf(Relation.class);
        if(j.has("targets"))for(var e:j.getAsJsonArray("targets"))relations.add(Relation.valueOf(e.getAsString().toUpperCase(Locale.ROOT)));
        else relations.add(Relation.ENEMY);
        List<ResourceLocation> conditions=new ArrayList<>();
        if(j.has("conditions"))for(var e:j.getAsJsonArray("conditions"))conditions.add(ResourceLocation.parse(e.getAsString()));
        JsonObject d=j.has("damage")?j.getAsJsonObject("damage"):new JsonObject();
        DamageMode mode=DamageMode.valueOf(text(d,"mode","none").toUpperCase(Locale.ROOT));
        Damage damage=new Damage(mode,ResourceLocation.parse(text(d,"type",mode==DamageMode.WEAPON?"minecraft:player_attack":"minecraft:magic")),
                formula(d,mode==DamageMode.WEAPON?"multiplier":"amount",mode==DamageMode.WEAPON?"1":"0",variables));
        if(mode==DamageMode.WEAPON&&!damage.type().equals(ResourceLocation.withDefaultNamespace("player_attack")))
            throw new IllegalArgumentException("Native weapon mode retains its native damage type; use formula mode for custom types");
        List<Effect> effects=new ArrayList<>();
        if(j.has("effects"))for(var element:j.getAsJsonArray("effects")) {
            var e=element.getAsJsonObject();
            effects.add(new Effect(ResourceLocation.parse(text(e,"id","")),formula(e,"level","1",variables),
                    formula(e,"duration","1",variables),DurationUnit.valueOf(text(e,"duration_unit","turns").toUpperCase(Locale.ROOT))));
        }
        List<EnchantmentGrant> grants=new ArrayList<>();
        if(j.has("enchantments"))for(var element:j.getAsJsonArray("enchantments")) {
            var e=element.getAsJsonObject();Set<EquipmentSlot> slots=EnumSet.noneOf(EquipmentSlot.class);
            if(e.has("slots"))for(var slot:e.getAsJsonArray("slots"))slots.add(slot(slot.getAsString()));else slots.add(EquipmentSlot.MAINHAND);
            grants.add(new EnchantmentGrant(ResourceLocation.parse(text(e,"id","")),slots));
        }
        JsonObject point=new JsonObject();point.addProperty("type","circle");point.addProperty("radius",0);
        return new SkillDefinition(id,text(j,"name",id.toString()),text(j,"description",""),j.has("max_level")?j.get("max_level").getAsInt():1,
                Aim.valueOf(text(j,"aim","entity").toUpperCase(Locale.ROOT)),relations,Anchor.valueOf(text(j,"anchor","target").toUpperCase(Locale.ROOT)),
                bool(j,"area",false),SkillAreas.compile(j.has("shape")?j.get("shape"):point,variables),formula(j,"range","10",variables),
                formula(j,"min_range","0",variables),formula(j,"vertical_min","-1",variables),formula(j,"vertical_max","1",variables),
                bool(j,"line_of_sight",true),bool(j,"ap_ramp",true),formula(j,"ap_cost","0",variables),formula(j,"mp_cost","0",variables),
                formula(j,"cooldown","0",variables),formula(j,"require","1",variables),damage,effects,conditions,attributes,grants,action);
    }
    private static EquipmentSlot slot(String s) {
        return switch(s.toLowerCase(Locale.ROOT)) {
            case "mainhand","main_hand"->EquipmentSlot.MAINHAND;case "offhand","off_hand"->EquipmentSlot.OFFHAND;
            case "head"->EquipmentSlot.HEAD;case "chest"->EquipmentSlot.CHEST;case "legs"->EquipmentSlot.LEGS;case "feet"->EquipmentSlot.FEET;
            default->throw new IllegalArgumentException("Unsupported skill grant slot "+s);
        };
    }
    private static SkillFormula.Compiled formula(JsonObject j,String key,String fallback,Set<String> allowed) {
        return SkillFormula.compile(text(j,key,fallback),allowed);
    }
    private static String text(JsonObject j,String key,String fallback) {return j.has(key)?j.get(key).getAsString():fallback;}
    private static boolean bool(JsonObject j,String key,boolean fallback) {return j.has(key)?j.get(key).getAsBoolean():fallback;}

    /** The JSON escape hatch keeps all definition parameters available to Java integrations. */
    public static final class Builder {
        private final ResourceLocation id;private JsonObject json=new JsonObject();private Action action;
        private Builder(ResourceLocation id) {this.id=Objects.requireNonNull(id);}
        public Builder json(JsonObject definition) {json=definition.deepCopy();return this;}
        public Builder name(String name) {json.addProperty("name",name);return this;}
        public Builder description(String description) {json.addProperty("description",description);return this;}
        public Builder maxLevel(int level) {json.addProperty("max_level",level);return this;}
        public Builder targets(Relation... allowed) {JsonArray a=new JsonArray();for(var t:allowed)a.add(t.name().toLowerCase(Locale.ROOT));json.add("targets",a);return this;}
        public Builder aim(Aim aim) {json.addProperty("aim",aim.name().toLowerCase(Locale.ROOT));return this;}
        public Builder costs(String ap,String mp) {json.addProperty("ap_cost",ap);json.addProperty("mp_cost",mp);return this;}
        public Builder cooldown(String turns) {json.addProperty("cooldown",turns);return this;}
        public Builder range(String distance) {json.addProperty("range",distance);return this;}
        public Builder shape(JsonObject shape,Anchor anchor) {json.addProperty("area",true);json.add("shape",shape.deepCopy());json.addProperty("anchor",anchor.name().toLowerCase(Locale.ROOT));return this;}
        public Builder weaponDamage(String multiplier) {var d=new JsonObject();d.addProperty("mode","weapon");d.addProperty("multiplier",multiplier);json.add("damage",d);return this;}
        public Builder damage(ResourceLocation type,String amount) {var d=new JsonObject();d.addProperty("mode","formula");d.addProperty("type",type.toString());d.addProperty("amount",amount);json.add("damage",d);return this;}
        public Builder action(Action action) {this.action=Objects.requireNonNull(action);return this;}
        public SkillDefinition build() {return parse(id,json,action);}
    }
}
