package com.tactical.neoforge.progression;

import com.tactical.core.progression.CharacterProfile;
import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.TacticalAttributes;
import com.tactical.neoforge.skill.ManaAttributes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The native attributes growth actually reaches, and the one modifier per stat that carries it.
 *
 * <p>Five of the six stats already have a native attribute; intelligence adds
 * {@code tactical:skill_power}, whose default 1 means "no change", so a character with nothing invested
 * produces exactly the old numbers. Every modifier is transient, namespaced and identified by one stable
 * id per stat, and is applied with an upsert: re-applying after a login, a respawn or a dimension change
 * replaces this mod's own modifier instead of stacking a second one, and never touches a base value or
 * another mod's modifier.</p>
 *
 * <p>Raising a maximum never refills anything: health keeps its current value, and the MP balance is
 * clamped on its next read by {@code ManaResources} rather than being topped up here. Lowering a maximum
 * clamps only if the current value now exceeds it.</p>
 */
public final class ProgressionEffects {

    private static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, Tactical.MOD_ID);

    /**
     * Skill damage multiplier, applied once inside the core skill system.
     *
     * <p>Default 1 keeps every existing result identical; it is only ever moved by this mod's own
     * intelligence modifier, and it deliberately does not touch plain attacks, AP/MP costs, cooldowns or
     * effect levels.</p>
     */
    public static final DeferredHolder<Attribute, Attribute> SKILL_POWER = ATTRIBUTES.register("skill_power",
            () -> new RangedAttribute(ProgressionText.ATTRIBUTE_SKILL_POWER, 1.0D, 0.0D, 1_000.0D).setSyncable(true));

    private static final ResourceLocation VIGOR_ID = id("progression_vigor");
    private static final ResourceLocation MIND_ID = id("progression_mind");
    private static final ResourceLocation ENDURANCE_ID = id("progression_endurance");
    private static final ResourceLocation STRENGTH_ID = id("progression_strength");
    private static final ResourceLocation DEXTERITY_ID = id("progression_dexterity");
    private static final ResourceLocation INTELLIGENCE_ID = id("progression_intelligence");

    private ProgressionEffects() { }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, path); }

    /** Modifier id of one stat; stable across logins, saves and versions of this feature. */
    public static ResourceLocation modifierId(CharacterStat stat) {
        return switch (stat) {
            case VIGOR -> VIGOR_ID;
            case MIND -> MIND_ID;
            case ENDURANCE -> ENDURANCE_ID;
            case STRENGTH -> STRENGTH_ID;
            case DEXTERITY -> DEXTERITY_ID;
            case INTELLIGENCE -> INTELLIGENCE_ID;
        };
    }

    /** Attribute instance one stat writes to, or {@code null} when this living type does not carry it. */
    public static AttributeInstance attributeOf(LivingEntity actor, CharacterStat stat) {
        if (actor == null) return null;
        return switch (stat) {
            case VIGOR -> actor.getAttribute(Attributes.MAX_HEALTH);
            case MIND -> actor.getAttribute(ManaAttributes.MAX_MP);
            case ENDURANCE -> actor.getAttribute(TacticalAttributes.MAX_AP);
            case STRENGTH -> actor.getAttribute(Attributes.ATTACK_DAMAGE);
            case DEXTERITY -> actor.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED);
            case INTELLIGENCE -> actor.getAttribute(SKILL_POWER);
        };
    }

    /** Registers the skill-power attribute exactly once, from {@link ProgressionHandler#register(IEventBus)}. */
    static void register(IEventBus modEventBus) {
        ATTRIBUTES.register(modEventBus);
        modEventBus.addListener(ProgressionEffects::addToLivingTypes);
    }

    private static void addToLivingTypes(EntityAttributeModificationEvent event) {
        for (var type : event.getTypes()) {
            if (!event.has(type, SKILL_POWER)) event.add(type, SKILL_POWER);
        }
    }

    /**
     * Current skill damage multiplier; 1.0 only when this actor has no such attribute at all.
     *
     * <p>A legitimate zero is respected: another mod may push the multiplier down to its own floor, and this
     * getter must not quietly raise it to 1. Only a missing attribute, or a non-finite value, is neutral 1.</p>
     */
    public static double skillPower(LivingEntity actor) {
        if (actor == null) return 1.0D;
        AttributeInstance instance = attributeOf(actor, CharacterStat.INTELLIGENCE);
        if (instance == null) return 1.0D;
        double value = instance.getValue();
        return Double.isFinite(value) ? Math.max(0.0D, value) : 1.0D;
    }

    /**
     * Makes the actor's attributes reflect exactly {@code profile}'s invested points.
     *
     * <p>Idempotent by construction: the amount is recomputed from the invested points and written through
     * one stable id, so a repeated call cannot stack, and a removed investment removes this mod's own
     * modifier. The value of a stat that is not invested is simply not contributed at all, so the
     * baseline {@code 10} never becomes a hidden bonus.</p>
     */
    public static void apply(LivingEntity actor, ProgressionRules rules, CharacterProfile profile) {
        if (actor == null || rules == null || profile == null) return;
        for (CharacterStat stat : CharacterStat.values()) {
            AttributeInstance instance = attributeOf(actor, stat);
            if (instance == null) continue;
            double amount = rules.bonus(stat, profile.invested(stat));
            AttributeModifier existing = instance.getModifier(modifierId(stat));
            if (amount <= 0) {
                if (existing != null) instance.removeModifier(modifierId(stat));
                continue;
            }
            if (existing != null && Double.compare(existing.amount(), amount) == 0) continue;
            instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId(stat), amount,
                    AttributeModifier.Operation.ADD_VALUE));
        }
        // Only a lowered maximum needs clamping: raising it keeps whatever the actor already has.
        if (actor.getHealth() > actor.getMaxHealth()) actor.setHealth(actor.getMaxHealth());
    }

    /** Removes this mod's own contribution from every stat it writes to. */
    public static void clear(LivingEntity actor) {
        if (actor == null) return;
        for (CharacterStat stat : CharacterStat.values()) {
            AttributeInstance instance = attributeOf(actor, stat);
            if (instance != null) instance.removeModifier(modifierId(stat));
        }
    }

    /**
     * The six values the server publishes as "the actual numbers this character has".
     *
     * <p>They are read back from the real attribute instances, so any other modifier - equipment, potions,
     * another mod, and the multiplier stages in particular - is already included and the client never has to
     * reconstruct a derived value. Endurance is reported in the unit the page shows: the actual maximum AP
     * that {@code tactical:max_ap} produces (the attribute itself keeps its percentage semantics), rounded
     * exactly like {@code TacticalAttributes.maxAp} does.</p>
     */
    public static double[] derived(LivingEntity actor) {
        double[] values = new double[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) {
            AttributeInstance instance = attributeOf(actor, stat);
            double raw = instance == null ? 0.0D : instance.getValue();
            values[stat.index()] = stat == CharacterStat.ENDURANCE ? actualAp(raw) : raw;
        }
        return values;
    }

    /**
     * The same six values as they would be with {@code draft} committed, computed without touching the actor.
     *
     * <p>{@code draft} holds the <em>increments</em> a client proposed, exactly as they travel in a preview
     * request, so each entry is added to the committed points of its stat. Each value is computed on an
     * independent copy of the real attribute instance - the copy is filled with {@code replaceFrom}, only
     * this mod's own modifier is replaced in it, and the copy is then read with {@code getValue()} - so every
     * foreign contribution (base value, flat modifiers, multiplied-base, multiplied-total) and the
     * attribute's own range sanitizing are part of the preview exactly as they will be after the real
     * allocation. The real player is never modified and nothing is rolled back.</p>
     */
    public static double[] draftDerived(LivingEntity actor, ProgressionRules rules, CharacterProfile profile, int[] draft) {
        double[] values = new double[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) {
            int index = stat.index();
            int invested = profile == null ? 0 : profile.invested(stat);
            int increment = draft == null || index >= draft.length ? 0 : Math.max(0, draft[index]);
            AttributeInstance real = attributeOf(actor, stat);
            if (real == null) {
                values[index] = 0.0D;
                continue;
            }
            double raw = previewValue(real, rules, stat, invested + increment);
            values[index] = stat == CharacterStat.ENDURANCE ? actualAp(raw) : raw;
        }
        return values;
    }

    /** One stat's value as the attribute itself would report it with {@code invested} points of this stat. */
    private static double previewValue(AttributeInstance real, ProgressionRules rules, CharacterStat stat, int invested) {
        AttributeInstance copy = new AttributeInstance(real.getAttribute(), ignored -> { });
        copy.replaceFrom(real);
        ResourceLocation id = modifierId(stat);
        copy.removeModifier(id);
        double amount = rules == null ? 0.0D : rules.bonus(stat, invested);
        if (amount > 0) copy.addOrUpdateTransientModifier(new AttributeModifier(id, amount,
                AttributeModifier.Operation.ADD_VALUE));
        return copy.getValue();
    }

    /**
     * The maximum AP a {@code tactical:max_ap} percentage produces, rounded like the battle system rounds it.
     *
     * <p>Kept identical to {@code TacticalAttributes.maxAp} on purpose: the page must show the number the
     * battle will actually use, not the internal percentage.</p>
     */
    public static double actualAp(double maxApPercent) {
        if (!Double.isFinite(maxApPercent)) return 0.0D;
        return Math.max(1, (int) Math.round(com.tactical.core.combat.ApCalculator.defaultMaxAp()
                * (maxApPercent / 100.0D)));
    }
}
