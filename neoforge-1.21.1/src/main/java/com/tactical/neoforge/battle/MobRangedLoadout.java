package com.tactical.neoforge.battle;

import com.tactical.core.combat.RangedWeapon;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Which ranged weapon this actor really carries, and the hand it will really be fired from.
 *
 * <p>Vanilla picks the firing hand with {@code ProjectileUtil.getWeaponHoldingHand}: the main hand wins when
 * it holds the matching item, otherwise the off hand. Detection follows that exact rule, so a planner can
 * never validate a shot that the firing code then refuses, and never aims for the main hand while the real
 * projectile leaves from the off hand.</p>
 *
 * <p>Nothing here equips a weapon: an actor with no bow and no crossbow reports no loadout and keeps its
 * original melee behaviour.</p>
 */
public record MobRangedLoadout(RangedWeapon weapon, InteractionHand hand) {
    /** What one held item is, without asking which entity holds it. */
    public enum Carried { NONE, BOW, CROSSBOW }

    public static Carried carried(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Carried.NONE;
        if (stack.getItem() instanceof BowItem) return Carried.BOW;
        if (stack.getItem() instanceof CrossbowItem) return Carried.CROSSBOW;
        return Carried.NONE;
    }

    /**
     * Pure hand-priority rule: main hand first, then off hand, across both ranged weapon types.
     *
     * <p>Used where no adapter has declared which weapon it fires - a preview of a caster that is not one of
     * this encounter's AI actors, for example. A real AI shot goes through {@link #of(RangedWeapon, Carried,
     * Carried)} instead.</p>
     *
     * @return the loadout, or {@code null} when neither hand holds a supported ranged weapon
     */
    public static MobRangedLoadout of(Carried mainHand, Carried offHand) {
        if (mainHand == Carried.BOW) return new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.MAIN_HAND);
        if (mainHand == Carried.CROSSBOW) return new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.MAIN_HAND);
        if (offHand == Carried.BOW) return new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.OFF_HAND);
        if (offHand == Carried.CROSSBOW) return new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.OFF_HAND);
        return null;
    }

    public static MobRangedLoadout of(LivingEntity actor) {
        if (actor == null) return null;
        return of(carried(actor.getMainHandItem()), carried(actor.getOffhandItem()));
    }

    /** Whether one hand really holds the weapon type a shot uses. */
    public static boolean matches(RangedWeapon weapon, Carried carried) {
        return weapon == RangedWeapon.BOW ? carried == Carried.BOW : carried == Carried.CROSSBOW;
    }

    /**
     * The hand that will really fire one weapon type.
     *
     * <p>A shot must be planned for the weapon its own firing code will use, so the search is limited to that
     * type: main hand first when it holds it, otherwise the off hand. That is the same per-item rule vanilla
     * applies, and it is what keeps a mixed loadout honest - a crossbow-holding actor whose main hand holds a
     * bow plans and fires the crossbow from the off hand, instead of planning a bow shot that the native
     * crossbow path would never perform.</p>
     *
     * @return the loadout, or {@code null} when neither hand holds that type
     */
    public static MobRangedLoadout of(RangedWeapon weapon, Carried mainHand, Carried offHand) {
        if (weapon == null) return null;
        if (matches(weapon, mainHand)) return new MobRangedLoadout(weapon, InteractionHand.MAIN_HAND);
        if (matches(weapon, offHand)) return new MobRangedLoadout(weapon, InteractionHand.OFF_HAND);
        return null;
    }

    public static MobRangedLoadout of(RangedWeapon weapon, LivingEntity actor) {
        if (actor == null) return null;
        return of(weapon, carried(actor.getMainHandItem()), carried(actor.getOffhandItem()));
    }

    /** The exact stack the shot would consume, so loading and firing use the same hand. */
    public ItemStack stack(LivingEntity actor) { return actor.getItemInHand(hand); }
}
