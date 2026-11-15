package com.tactical.neoforge.battle;

import com.tactical.core.combat.ApCalculator;
import com.tactical.core.combat.BattleRules;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The beam's pure arithmetic: the frozen price, the native charge length, the abnormal ceiling and the single
 * settlement.
 *
 * <p>The dedicated-server fixture proves what the entity really does. These tests prove the part that must be
 * decidable without a world: an ordinary guardian's 80 and an elder's 60 are both inside the ceiling, the price is
 * frozen at the launch, and exactly one close can ever owe it.</p>
 */
class GuardianBeamTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Test
    void theCeilingIsAboveBothRealChargesSoItCanNeverShortenThem() {
        assertTrue(GuardianBeam.DEADLINE_TICKS > GuardianBeam.CHARGE_SLACK_TICKS + GuardianBeam.LONGEST_NATIVE_CHARGE,
                "the abnormal ceiling must leave the ordinary guardian flow untouched");
        assertTrue(GuardianBeam.DEADLINE_TICKS > GuardianBeam.CHARGE_SLACK_TICKS + 60,
                "and the elder's shorter flow as well");
        GuardianBeam guardian = new GuardianBeam(ACTOR, TARGET, 3L, 20, 100, 80);
        assertEquals(190, guardian.settleTick(), "ten ticks of pre-heat plus the native eighty");
        assertTrue(guardian.settleTick() < guardian.deadlineTick());
        GuardianBeam elder = new GuardianBeam(ACTOR, TARGET, 3L, 20, 100, 60);
        assertEquals(170, elder.settleTick(), "the elder's own native sixty");
        assertNotEquals(guardian.settleTick(), elder.settleTick());
    }

    @Test
    void theAbnormalCeilingStillEndsABeamThatStoppedAdvancing() {
        GuardianBeam beam = new GuardianBeam(ACTOR, TARGET, 3L, 20, 0, 80);
        assertEquals(GuardianBeam.DEADLINE_TICKS, beam.deadlineTick());
        assertFalse(beam.expired(GuardianBeam.DEADLINE_TICKS - 1));
        assertTrue(beam.expired(GuardianBeam.DEADLINE_TICKS));
        assertEquals(90, new GuardianBeam(ACTOR, TARGET, 3L, 20, 0, 80).settleTick());
    }

    @Test
    void thePriceIsFrozenAndNeverRecomputed() {
        GuardianBeam beam = new GuardianBeam(ACTOR, TARGET, 7L, 25, 40, 80);
        assertEquals(25, beam.price());
        assertEquals(ACTOR, beam.actorId());
        assertEquals(TARGET, beam.targetId());
        assertEquals(7L, beam.epoch());
        assertEquals(80, beam.chargeTicks());
        assertTrue(beam.names(ACTOR, TARGET));
        assertFalse(beam.names(TARGET, ACTOR), "a beam names one actor and one opponent, not a set");
        assertFalse(beam.names(ACTOR, UUID.randomUUID()));
    }

    @Test
    void exactlyOneCloseEverOwesThePrice() {
        GuardianBeam beam = new GuardianBeam(ACTOR, TARGET, 7L, 20, 0, 80);
        assertFalse(beam.settled());
        assertTrue(beam.settle(true), "the first close of a real hit owes the frozen price");
        assertTrue(beam.settled());
        assertFalse(beam.settle(true), "a duplicate hand-back may never charge twice");
        assertFalse(beam.settle(false), "nor may it turn into a refund");

        GuardianBeam refused = new GuardianBeam(ACTOR, TARGET, 7L, 20, 0, 80);
        assertFalse(refused.settle(false), "two refused native hits charge nothing");
        assertFalse(refused.settle(false), "and a repeated refusal still charges nothing");
        assertTrue(refused.settled());
    }

    @Test
    void theBasePriceIsTheRulesetsAndTheSurchargeIsTheSharedOne() {
        assertEquals(20, BattleRules.GUARDIAN_BEAM_COST);
        assertEquals(BattleRules.GUARDIAN_BEAM_COST, ApCalculator.paidActionCost(BattleRules.GUARDIAN_BEAM_COST, 0));
        assertEquals(25, ApCalculator.paidActionCost(BattleRules.GUARDIAN_BEAM_COST, 1));
        assertEquals(30, ApCalculator.paidActionCost(BattleRules.GUARDIAN_BEAM_COST, 2));
    }

    @Test
    void malformedArithmeticIsRefusedInsteadOfFrozen() {
        assertThrows(IllegalArgumentException.class, () -> new GuardianBeam(ACTOR, TARGET, 1L, 0, 0, 80));
        assertThrows(IllegalArgumentException.class, () -> new GuardianBeam(ACTOR, TARGET, 1L, -5, 0, 80));
        assertThrows(IllegalArgumentException.class, () -> new GuardianBeam(ACTOR, TARGET, 1L, 20, 0, 0));
        assertThrows(NullPointerException.class, () -> new GuardianBeam(null, TARGET, 1L, 20, 0, 80));
        assertThrows(NullPointerException.class, () -> new GuardianBeam(ACTOR, null, 1L, 20, 0, 80));
    }
}
