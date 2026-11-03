package com.tactical.neoforge.battle;

import com.tactical.neoforge.battle.ai.WitchBrew;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * The one place a brew name becomes a real vanilla potion.
 *
 * <p>Both the self-drink and the thrown bottle resolve here, so the item a witch really drinks and the splash
 * her potion really creates are the same registry entries vanilla uses - no hand-built effect lists and no
 * private copy of the potion table. The effect holders that decide "is this still fully in place" come from the
 * same mapping, so a decision never checks a different effect from the one that would be applied.</p>
 */
final class WitchBrews {
    private WitchBrews() { }

    /** The vanilla potion behind one brew. */
    static Holder<Potion> potion(WitchBrew brew) {
        return switch (brew) {
            case HEALING -> Potions.HEALING;
            case WATER_BREATHING -> Potions.WATER_BREATHING;
            case FIRE_RESISTANCE -> Potions.FIRE_RESISTANCE;
            case SWIFTNESS -> Potions.SWIFTNESS;
            case SLOWNESS -> Potions.SLOWNESS;
            case POISON -> Potions.POISON;
            case WEAKNESS -> Potions.WEAKNESS;
            case HARMING -> Potions.HARMING;
        };
    }

    /** The periodic effect behind one brew, or null for the two instant ones. */
    static Holder<MobEffect> periodicEffect(WitchBrew brew) {
        return switch (brew) {
            case HEALING, HARMING -> null;
            case WATER_BREATHING -> MobEffects.WATER_BREATHING;
            case FIRE_RESISTANCE -> MobEffects.FIRE_RESISTANCE;
            case SWIFTNESS -> MobEffects.MOVEMENT_SPEED;
            case SLOWNESS -> MobEffects.MOVEMENT_SLOWDOWN;
            case POISON -> MobEffects.POISON;
            case WEAKNESS -> MobEffects.WEAKNESS;
        };
    }

    /** The drinkable item stack for one brew, exactly as vanilla would build it. */
    static ItemStack drink(WitchBrew brew) {
        return PotionContents.createItemStack(Items.POTION, potion(brew));
    }

    /** The splash item stack for one brew, exactly as vanilla would build it. */
    static ItemStack splash(WitchBrew brew) {
        return PotionContents.createItemStack(Items.SPLASH_POTION, potion(brew));
    }

    /**
     * Which brew an item a witch was already holding corresponds to, or null when it is not one of them.
     *
     * <p>Only the four drinkable brews are recognisable: a drink of something this ruleset does not model
     * cannot be migrated into a typed action, so it is given back untouched instead of being renamed.</p>
     */
    static WitchBrew brewOf(ItemStack stack) {
        if (stack == null || !stack.is(Items.POTION)) return null;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()) return null;
        Holder<Potion> held = contents.potion().get();
        for (WitchBrew brew : WitchBrew.values()) {
            if (brew.selfDrink() && potion(brew).value() == held.value()) return brew;
        }
        return null;
    }
}
