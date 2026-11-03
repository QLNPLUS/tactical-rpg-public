package com.tactical.neoforge.battle;

import com.tactical.core.combat.RangedWeapon;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobRangedLoadoutTest {
    @Test
    void sameTypeMainHandIsTheFiringHand() {
        assertEquals(new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.MAIN_HAND),
                MobRangedLoadout.of(RangedWeapon.BOW, MobRangedLoadout.Carried.BOW, MobRangedLoadout.Carried.NONE));
        assertEquals(new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.MAIN_HAND),
                MobRangedLoadout.of(RangedWeapon.CROSSBOW, MobRangedLoadout.Carried.CROSSBOW, MobRangedLoadout.Carried.NONE));
        // Same type in both hands: the main hand is the one vanilla's hand helper picks.
        assertEquals(new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.MAIN_HAND),
                MobRangedLoadout.of(RangedWeapon.CROSSBOW, MobRangedLoadout.Carried.CROSSBOW,
                        MobRangedLoadout.Carried.CROSSBOW));
    }

    @Test
    void sameTypeOffHandIsRecognisedAndBecomesTheFiringHand() {
        assertEquals(new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.OFF_HAND),
                MobRangedLoadout.of(RangedWeapon.BOW, MobRangedLoadout.Carried.NONE, MobRangedLoadout.Carried.BOW));
        assertEquals(new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.OFF_HAND),
                MobRangedLoadout.of(RangedWeapon.CROSSBOW, MobRangedLoadout.Carried.NONE,
                        MobRangedLoadout.Carried.CROSSBOW));
    }

    @Test
    void aMixedLoadoutNeverBorrowsTheOtherWeaponTypeFromTheMainHand() {
        // Pillager with a bow in the main hand and a crossbow in the off hand: the crossbow really fires, and the
        // bow must not become a planned bow shot from the main hand.
        assertEquals(new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.OFF_HAND),
                MobRangedLoadout.of(RangedWeapon.CROSSBOW, MobRangedLoadout.Carried.BOW,
                        MobRangedLoadout.Carried.CROSSBOW));
        // Stray with a crossbow in the main hand and a bow in the off hand: the bow really fires.
        assertEquals(new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.OFF_HAND),
                MobRangedLoadout.of(RangedWeapon.BOW, MobRangedLoadout.Carried.CROSSBOW,
                        MobRangedLoadout.Carried.BOW));
    }

    @Test
    void anActorWithoutItsOwnWeaponTypeHasNoShotAtAll() {
        // Holding the other ranged item is not a licence for an unapproved native ability.
        assertNull(MobRangedLoadout.of(RangedWeapon.CROSSBOW, MobRangedLoadout.Carried.BOW, MobRangedLoadout.Carried.NONE));
        assertNull(MobRangedLoadout.of(RangedWeapon.BOW, MobRangedLoadout.Carried.CROSSBOW, MobRangedLoadout.Carried.NONE));
        assertNull(MobRangedLoadout.of(RangedWeapon.BOW, MobRangedLoadout.Carried.NONE, MobRangedLoadout.Carried.NONE));
        assertNull(MobRangedLoadout.of((RangedWeapon) null, MobRangedLoadout.Carried.BOW, MobRangedLoadout.Carried.BOW));
    }

    @Test
    void theItemDrivenFallbackStillPrefersAnyRangedWeaponInTheMainHand() {
        // Used only where no adapter declared a weapon (a preview of a non-AI caster), so it keeps the old rule.
        assertEquals(new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.MAIN_HAND),
                MobRangedLoadout.of(MobRangedLoadout.Carried.BOW, MobRangedLoadout.Carried.CROSSBOW));
        assertEquals(new MobRangedLoadout(RangedWeapon.CROSSBOW, InteractionHand.MAIN_HAND),
                MobRangedLoadout.of(MobRangedLoadout.Carried.CROSSBOW, MobRangedLoadout.Carried.BOW));
        assertEquals(new MobRangedLoadout(RangedWeapon.BOW, InteractionHand.OFF_HAND),
                MobRangedLoadout.of(MobRangedLoadout.Carried.NONE, MobRangedLoadout.Carried.BOW));
        assertNull(MobRangedLoadout.of(MobRangedLoadout.Carried.NONE, MobRangedLoadout.Carried.NONE));
    }

    @Test
    void anActorWithNothingInHandHasNoLoadoutAtAll() {
        assertNull(MobRangedLoadout.of((net.minecraft.world.entity.LivingEntity) null));
        assertNull(MobRangedLoadout.of(RangedWeapon.BOW, (net.minecraft.world.entity.LivingEntity) null));
        assertEquals(MobRangedLoadout.Carried.NONE, MobRangedLoadout.carried(null));
    }
}
