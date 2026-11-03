package com.tactical.neoforge.skill;

import com.tactical.core.combat.PositionalDamage;
import com.tactical.neoforge.TacticalConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Extensible side-effect-free cast requirements; empty means satisfied.
 *
 * <p>Two registration forms share one registry:</p>
 * <ul>
 *   <li>{@link Condition} - the original String contract, unchanged. Its result is finished text, so it
 *       travels as a literal: this mod never guesses that a returned string is a translation key.</li>
 *   <li>{@link Localized} - an optional richer form used by this mod's own conditions. It returns a native
 *       {@link Component}, so a client reads the failure in its own language instead of the server's. A
 *       third-party condition does not have to implement it.</li>
 * </ul>
 *
 * <p>The production path ({@link #failureComponent}) prefers {@link Localized} and falls back to the
 * literal form, so an existing integration keeps working exactly as before.</p>
 */
public final class SkillConditions {
    @FunctionalInterface public interface Condition {String failure(SkillCastContext context);}

    /** Optional richer form: a failure this mod's own conditions can express as structure. */
    @FunctionalInterface public interface Localized {
        /** @return the failure, or {@code null} when this condition is satisfied. */
        Component failureComponent(SkillCastContext context);
    }

    private static final Map<ResourceLocation,Condition> CONDITIONS=new ConcurrentSkipListMap<>();
    private static final Set<ResourceLocation> GLOBAL=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static {
        registerGlobal(ResourceLocation.fromNamespaceAndPath("tactical","melee_weapon"),new MeleeWeapon());
        register(ResourceLocation.fromNamespaceAndPath("tactical","rear"),new Rear());
    }
    private SkillConditions() { }

    /** The melee-weapon requirement: main-hand sword, axe, mace or trident. */
    private static final class MeleeWeapon implements Condition,Localized {
        private static boolean armed(SkillCastContext context) {
            var stack=context.caster().getMainHandItem();
            return stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES)||stack.is(Items.MACE)||stack.is(Items.TRIDENT);
        }
        @Override public String failure(SkillCastContext context) {
            return armed(context)?"":"需要近战武器";
        }
        @Override public Component failureComponent(SkillCastContext context) {
            return armed(context)?null:SkillText.translatable(SkillText.KEY_CONDITION_MELEE_WEAPON);
        }
    }

    /** The rear requirement: the selected entity target must be attacked from behind. */
    private static final class Rear implements Condition,Localized {
        private static PositionalDamage.Sector sector(SkillCastContext context,boolean[] missing) {
            var selected=context.affected().stream().filter(e->e.getUUID().equals(context.requested().entity())).findFirst().orElse(null);
            if(selected==null) {missing[0]=true;return null;}
            double front=45,rear=45;
            try {front=TacticalConfig.INSTANCE.damageFrontHalfAngle.get();rear=TacticalConfig.INSTANCE.damageRearHalfAngle.get();}catch(IllegalStateException ignored) { }
            return PositionalDamage.sector(selected.yBodyRot,context.caster().getX()-selected.getX(),context.caster().getZ()-selected.getZ(),front,rear);
        }
        @Override public String failure(SkillCastContext context) {
            boolean[] missing=new boolean[1];
            var sector=sector(context,missing);
            if(missing[0])return "需要敌方实体目标";
            return sector==PositionalDamage.Sector.REAR?"":"必须从目标背后发动";
        }
        @Override public Component failureComponent(SkillCastContext context) {
            boolean[] missing=new boolean[1];
            var sector=sector(context,missing);
            if(missing[0])return SkillText.translatable(SkillText.KEY_CONDITION_REAR_TARGET);
            return sector==PositionalDamage.Sector.REAR?null:SkillText.translatable(SkillText.KEY_CONDITION_REAR);
        }
    }

    public static void register(ResourceLocation id,Condition condition) {
        if(CONDITIONS.putIfAbsent(Objects.requireNonNull(id),Objects.requireNonNull(condition))!=null)
            throw new IllegalArgumentException("Duplicate skill condition "+id);
    }
    public static void registerGlobal(ResourceLocation id,Condition condition) {register(id,condition);GLOBAL.add(id);}
    public static String failure(SkillCastContext context) {return failure(context,true);}
    public static String failure(SkillCastContext context,boolean includeTargetConditions) {
        for(var id:context.definition().conditions()) {
            if(!includeTargetConditions&&!GLOBAL.contains(id))continue;
            Condition condition=require(id);
            String failure=condition.failure(context);
            if(failure!=null&&!failure.isBlank())return failure;
        }
        return "";
    }
    /** Whether this condition is registered as global (checked without a selected target). */
    public static boolean global(ResourceLocation id) {return GLOBAL.contains(id);}

    /**
     * The failure of the first unsatisfied condition, as display structure, or {@link SkillText#none()}.
     *
     * <p>This is the production path. A {@link Localized} condition answers with its own key; the original
     * String form is carried as a literal, because a condition was never obliged to return a key and this
     * mod does not guess.</p>
     */
    public static Component failureComponent(SkillCastContext context,boolean includeTargetConditions) {
        for(var id:context.definition().conditions()) {
            if(!includeTargetConditions&&!GLOBAL.contains(id))continue;
            Condition condition=require(id);
            Component failure=condition instanceof Localized localized?localized.failureComponent(context)
                    :literal(condition.failure(context));
            if(!SkillText.isEmpty(failure))return failure;
        }
        return SkillText.none();
    }
    private static Component literal(String failure) {
        return failure==null||failure.isBlank()?null:Component.literal(failure);
    }
    private static Condition require(ResourceLocation id) {
        Condition condition=CONDITIONS.get(id);
        if(condition==null)throw new IllegalArgumentException("Unknown skill condition "+id);
        return condition;
    }
}
