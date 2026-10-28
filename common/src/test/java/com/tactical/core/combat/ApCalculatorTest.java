package com.tactical.core.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApCalculatorTest {
    @Test void defaultValuesAndSpeedIndependentCap() {
        assertEquals(150, ApCalculator.defaultMaxAp());
        assertEquals(12, ApCalculator.defaultMoveCost());
        assertEquals(10, ApCalculator.defaultMeleeCost());
        for(double speed:new double[]{0,.1,.23,1,10}) assertEquals(150,ApCalculator.maxAp(speed));
    }
    @Test void fasterMovementCostsLess() {
        assertEquals(6,ApCalculator.moveCost(.2));
        assertEquals(24,ApCalculator.moveCost(.05));
        assertEquals(4,ApCalculator.moveCost(1));
    }
    @Test void meleePriceIsIndependentOfNativeAttackSpeed() {
        for(double speed:new double[]{0,.5,1,1.6,2,10}) assertEquals(10,ApCalculator.meleeCost(speed));
    }
    @Test void sharedCountPricesEveryCategoryWithExactCeiling() {
        assertEquals(10,ApCalculator.paidActionCost(10,0));
        assertEquals(13,ApCalculator.paidActionCost(10,1));
        assertEquals(23,ApCalculator.paidActionCost(15,2));
        assertEquals(35,ApCalculator.paidActionCost(20,3));
        assertEquals(16,ApCalculator.paidActionCost(8,4));
        assertEquals(23,ApCalculator.paidActionCost(10,5));
        assertEquals(Integer.MAX_VALUE,ApCalculator.paidActionCost(Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
    @Test void firstOwnTurnAndCarryoverRecoveryRespectCap() {
        assertEquals(100,ApCalculator.initialAp(150,100));
        assertEquals(60,ApCalculator.initialAp(60,100));
        assertEquals(100,ApCalculator.beginOwnTurnAp(100,150,50,true));
        assertEquals(80,ApCalculator.beginOwnTurnAp(30,150,50,false));
        assertEquals(150,ApCalculator.beginOwnTurnAp(130,150,50,false));
        assertEquals(30,ApCalculator.beginOwnTurnAp(30,150,0,false));
        assertEquals(40,ApCalculator.beginOwnTurnAp(130,40,50,false));
        assertEquals(0,ApCalculator.beginOwnTurnAp(-1,150,0,false));
        assertEquals(Integer.MAX_VALUE,ApCalculator.beginOwnTurnAp(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,false));
    }
    @Test void initiativeStillFavorsFastAttackers() {
        assertTrue(ApCalculator.initiative(1.6,.1,0)>ApCalculator.initiative(1,.23,0));
    }
}
