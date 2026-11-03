package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Witch decisions without a world: the fixed self-drink order, the deterministic throw order, the shared
 * own-turn cooldown and the typed potion payload.
 *
 * <p>Every case reads only what the server would hand the adapter, so a passing decision test means "this brew
 * for this state" and never "a real dose landed". Real HP, real effects and the real splash belong to the
 * dedicated-server fixture.</p>
 */
class WitchTacticalAiTest {
    private static final int DRINK_COST = 25;
    private static final int THROW_COST = 15;
    private static final float MAX_HEALTH = 26.0F;

    // ---- self-drink priority (plan §3.2) ------------------------------------------------------------

    @Test
    void emergencyHealComesBeforeEveryOtherDrink() {
        var weak = context(cell(6, 0), 100, s -> s.health(6.0F, MAX_HEALTH).water(true).burning(true));
        AiAction action = new WitchTacticalAi().planTurn(weak);
        assertEquals(AiAction.Kind.DRINK_POTION, action.kind());
        assertEquals(WitchBrew.HEALING, action.brew(), "a dying witch heals herself, not her legs");
    }

    @Test
    void waterBreathingAndFireResistanceComeFromTheSituationNotFromHealth() {
        var wet = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).water(true));
        assertEquals(WitchBrew.WATER_BREATHING, new WitchTacticalAi().planTurn(wet).brew());

        var burning = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).burning(true));
        assertEquals(WitchBrew.FIRE_RESISTANCE, new WitchTacticalAi().planTurn(burning).brew());

        // An already active buff is not re-drunk while the situation is unchanged.
        var alreadyWet = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).water(true)
                .self(EnumSet.of(WitchBrew.WATER_BREATHING)));
        assertNotEquals(WitchBrew.WATER_BREATHING, new WitchTacticalAi().planTurn(alreadyWet).brew());
    }

    @Test
    void ordinaryHealNeedsBothTheThresholdAndAWholeDose() {
        var wounded = context(cell(6, 0), 100, s -> s.health(12.0F, MAX_HEALTH));
        assertEquals(WitchBrew.HEALING, new WitchTacticalAi().planTurn(wounded).brew());

        // Below half of a small health pool, but less than one whole dose missing: a heal would be wasted.
        var nearlyWhole = context(cell(6, 0), 100, s -> s.health(2.9F, 6.0F));
        assertNotEquals(WitchBrew.HEALING, new WitchTacticalAi().planTurn(nearlyWhole).brew());

        // Half of the maximum by fraction, but nothing is actually missing.
        var boosted = context(cell(6, 0), 100, s -> s.health(20.0F, 20.0F).fraction(0.5F));
        assertNotEquals(WitchBrew.HEALING, new WitchTacticalAi().planTurn(boosted).brew());
    }

    @Test
    void swiftnessIsOnlyForAnOpponentBeyondElevenBlocks() {
        var far = context(cell(14, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH));
        assertEquals(WitchBrew.SWIFTNESS, new WitchTacticalAi().planTurn(far).brew());

        var near = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH));
        assertNotEquals(WitchBrew.SWIFTNESS, new WitchTacticalAi().planTurn(near).brew());

        // Already swift: no point drinking it again, so she walks into throwing range instead.
        var alreadySwift = context(cell(14, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH)
                .self(EnumSet.of(WitchBrew.SWIFTNESS)));
        assertEquals(AiAction.Kind.MOVE, new WitchTacticalAi().planTurn(alreadySwift).kind());
    }

    @Test
    void aHealthyWitchWithNothingToGainDoesNotDrink() {
        var context = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH));
        assertEquals(AiAction.Kind.THROW_POTION, new WitchTacticalAi().planTurn(context).kind());
    }

    @Test
    void anUnaffordableDrinkNeverBecomesAFreeDose() {
        // 24 AP buys everything except the 25 AP drink.
        var context = context(cell(6, 0), DRINK_COST - 1, s -> s.health(6.0F, MAX_HEALTH));
        AiAction action = new WitchTacticalAi().planTurn(context);
        assertEquals(AiAction.Kind.THROW_POTION, action.kind());
        assertTrue(action.brew().thrown());
    }

    @Test
    void aCoolingDownWitchDoesNotDrinkEvenWhenItWouldHelp() {
        var onCooldown = context(cell(6, 0), 100, s -> s.health(6.0F, MAX_HEALTH).notReady());
        AiAction action = new WitchTacticalAi().planTurn(onCooldown);
        assertEquals(AiAction.Kind.THROW_POTION, action.kind());
        assertTrue(action.brew().thrown());
    }

    // ---- shared own-turn cooldown ------------------------------------------------------------------

    @Test
    void cooldownIsDrinkOnTheFirstTurnThenTwoRefusalsThenDrinkAgain() {
        var cooldown = new WitchDrinkCooldown(3);
        assertTrue(cooldown.ready(1), "1st own turn may drink");
        cooldown.settle(1);
        assertFalse(cooldown.ready(2), "2nd own turn is refused");
        assertFalse(cooldown.ready(3), "3rd own turn is refused");
        assertTrue(cooldown.ready(4), "4th own turn may drink again");
        assertEquals(4, cooldown.readyTurn());
    }

    @Test
    void twoWitchesNeverShareOneCooldown() {
        var first = new WitchDrinkCooldown(3);
        var second = new WitchDrinkCooldown(3);
        first.settle(1);
        assertFalse(first.ready(2));
        assertTrue(second.ready(2), "the other witch's first turn is untouched");
    }

    @Test
    void planningNeverAdvancesTheCooldownAndOtherTurnsNeverResetIt() {
        var cooldown = new WitchDrinkCooldown(3);
        var ai = new WitchTacticalAi();
        var context = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH));
        AiAction first = ai.planTurn(context);
        AiAction second = ai.planTurn(context);
        assertEquals(first.kind(), second.kind(), "repeated planning in one own turn answers the same thing");
        assertEquals(1, cooldown.readyTurn(), "planning is not a drink");
        assertTrue(cooldown.ready(1));
        assertFalse(cooldown.ready(0), "a turn index that has not happened yet is never ready");
    }

    @Test
    void aRefusedDrinkStartsNoCooldown() {
        var cooldown = new WitchDrinkCooldown(3);
        assertEquals(1, cooldown.readyTurn(), "an unsettled cooldown never moved");
        assertTrue(cooldown.ready(1));
    }

    @Test
    void aCarriedOverDrinkIsHonouredFirstAndDroppedWhenItCannotBePaidFor() {
        var affordable = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH)
                .burning(true).pending(WitchBrew.FIRE_RESISTANCE));
        AiAction action = new WitchTacticalAi().planTurn(affordable);
        assertEquals(AiAction.Kind.DRINK_POTION, action.kind());
        assertEquals(WitchBrew.FIRE_RESISTANCE, action.brew());

        var expensive = context(cell(6, 0), DRINK_COST - 1, s -> s.health(MAX_HEALTH, MAX_HEALTH)
                .burning(true).pending(WitchBrew.FIRE_RESISTANCE));
        new WitchTacticalAi().planTurn(expensive);
        assertTrue(expensive.cleared, "a drink nobody paid for never takes effect and is dropped");
    }

    @Test
    void aCarriedOverDrinkWithNothingLeftToGiveIsDroppedInsteadOfForceDrunk() {
        var wasted = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH)
                .pending(WitchBrew.HEALING));
        AiAction action = new WitchTacticalAi().planTurn(wasted);
        assertTrue(wasted.cleared, "a carried heal at full health buys nothing, so it is not drunk");
        assertNotEquals(AiAction.Kind.DRINK_POTION, action.kind());

        var alreadyBuff = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH)
                .self(EnumSet.of(WitchBrew.FIRE_RESISTANCE)).burning(true)
                .pending(WitchBrew.FIRE_RESISTANCE));
        new WitchTacticalAi().planTurn(alreadyBuff);
        assertTrue(alreadyBuff.cleared, "nor is one whose buff is already fully in place");
    }

    @Test
    void theBenefitTestMatchesWhatEachBrewCanStillGive() {
        var full = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH)).witch();
        assertFalse(full.beneficial(WitchBrew.HEALING), "a whole dose would be wasted at full health");
        assertFalse(full.beneficial(WitchBrew.WATER_BREATHING), "her eyes are not in water");
        assertFalse(full.beneficial(WitchBrew.FIRE_RESISTANCE), "she is not burning");
        assertTrue(full.beneficial(WitchBrew.SWIFTNESS), "a never-drunk swiftness still helps");
        assertFalse(full.beneficial(WitchBrew.POISON), "a thrown brew is never a self-drink");

        var wounded = context(cell(6, 0), 100, s -> s.health(12.0F, MAX_HEALTH)).witch();
        assertTrue(wounded.beneficial(WitchBrew.HEALING), "a whole dose really lands");

        var wet = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).water(true)).witch();
        assertTrue(wet.beneficial(WitchBrew.WATER_BREATHING));

        var swift = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH)
                .self(EnumSet.of(WitchBrew.SWIFTNESS))).witch();
        assertFalse(swift.beneficial(WitchBrew.SWIFTNESS), "an active buff is not re-drunk");
    }

    // ---- thrown brews (plan §3.3) ------------------------------------------------------------------

    @Test
    void slownessFirstForAFarOpponentThatDoesNotHaveIt() {
        var context = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F));
        assertEquals(WitchBrew.SLOWNESS, WitchTacticalAi.chooseThrow(context));

        // An already effective slowness is not refreshed; poison is the next thing that still helps.
        var slowed = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS)));
        assertEquals(WitchBrew.POISON, WitchTacticalAi.chooseThrow(slowed));
    }

    @Test
    void poisonNeedsAnOpponentThatCanLoseAtLeastEightHealth() {
        var tough = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(12.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS)));
        assertEquals(WitchBrew.POISON, WitchTacticalAi.chooseThrow(tough));

        var nearlyDead = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(6.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS)));
        assertEquals(WitchBrew.HARMING, WitchTacticalAi.chooseThrow(nearlyDead));
    }

    @Test
    void weaknessOnlyInTheOpponentsFace() {
        var adjacent = context(cell(2, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS, WitchBrew.POISON)));
        assertEquals(WitchBrew.WEAKNESS, WitchTacticalAi.chooseThrow(adjacent));

        var far = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS, WitchBrew.POISON)));
        assertNotEquals(WitchBrew.WEAKNESS, WitchTacticalAi.chooseThrow(far));
    }

    @Test
    void immunitySkipsAnEffectThatWouldNotLandAtAll() {
        var context = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .notReceptive(EnumSet.of(WitchBrew.SLOWNESS, WitchBrew.POISON)));
        assertEquals(WitchBrew.HARMING, WitchTacticalAi.chooseThrow(context),
                "the instant brew is the only thing that still works");
    }

    @Test
    void harmingIsNeverThrownAtATargetItWouldHeal() {
        var undead = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS, WitchBrew.POISON))
                .notReceptive(EnumSet.of(WitchBrew.HARMING, WitchBrew.WEAKNESS)));
        assertNull(WitchTacticalAi.chooseThrow(undead), "walking away beats handing an opponent health back");
        assertEquals(AiAction.Kind.END_TURN, new WitchTacticalAi().planTurn(undead).kind());

        // With a debuff that still lands, she throws that instead of the reversed instant brew.
        var debuffLeft = context(cell(9, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .notReceptive(EnumSet.of(WitchBrew.HARMING)));
        assertEquals(WitchBrew.SLOWNESS, WitchTacticalAi.chooseThrow(debuffLeft));
    }

    // ---- position ----------------------------------------------------------------------------------

    @Test
    void aDistantWitchClosesInWhileReservingTheThrowPrice() {
        // 11 blocks: outside the throwing range but not far enough to warrant a swiftness drink.
        var context = context(cell(11, 0), 40, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F));
        AiAction move = new WitchTacticalAi().planTurn(context);
        assertEquals(AiAction.Kind.MOVE, move.kind());
        assertTrue(move.path().size() * context.moveCost() + THROW_COST <= 40,
                "the walk must leave the throw affordable, path=" + move.path());
        GridPos end = move.path().getLast();
        assertTrue(Math.abs(end.x()) <= 10, "and must end inside the throwing range: " + end);
    }

    @Test
    void aWitchInTheOpponentsFaceBacksOffWhenThereIsRoom() {
        var room = context(cell(1, 0), 60, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F)
                .target(EnumSet.of(WitchBrew.SLOWNESS, WitchBrew.POISON, WitchBrew.WEAKNESS)));
        AiAction move = new WitchTacticalAi().planTurn(room);
        assertEquals(AiAction.Kind.MOVE, move.kind(), "there is room to step back");
        assertTrue(Math.abs(move.path().getLast().x()) >= 4, "and the new cell is comfortable");
    }

    @Test
    void aCorneredWitchStillThrowsFromWhereSheStands() {
        var cornered = context(cell(1, 0), 60, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F),
                pos -> true, pos -> pos.y() == 64 && pos.z() == 0 && pos.x() == 1);
        assertEquals(AiAction.Kind.THROW_POTION, new WitchTacticalAi().planTurn(cornered).kind());
    }

    @Test
    void aBlockedLineBecomesALateralMoveAndThenTheSameThrow() {
        Predicate<GridPos> onlyBackRowClear = pos -> pos.z() == 1;
        var blocked = context(cell(7, 0), 60, s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F),
                onlyBackRowClear, pos -> pos.y() == 64 && Math.abs(pos.z()) <= 2);
        AiAction move = new WitchTacticalAi().planTurn(blocked);
        assertEquals(AiAction.Kind.MOVE, move.kind(), "no bottle is thrown through cover");
        GridPos end = move.path().getLast();
        assertEquals(1, end.z(), "the new cell has a clear line");
        assertTrue(Math.abs(end.x()) <= 10);

        var after = context(end, 60 - move.path().size() * 10,
                s -> s.health(MAX_HEALTH, MAX_HEALTH).targetHealth(20.0F), onlyBackRowClear,
                pos -> pos.y() == 64 && Math.abs(pos.z()) <= 2);
        assertEquals(AiAction.Kind.THROW_POTION, new WitchTacticalAi().afterMovement(after).kind());
    }

    @Test
    void onlyOneMainActionPerOwnTurn() {
        var ai = new WitchTacticalAi();
        var context = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH));
        assertEquals(AiAction.Kind.THROW_POTION, ai.planTurn(context).kind());
        ai.onActionResolved(AiAction.Kind.THROW_POTION);
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context).kind());
        // A new own turn may act again: this is a per-turn flag, not a permanent lock.
        assertEquals(AiAction.Kind.THROW_POTION, ai.planTurn(context).kind());
    }

    @Test
    void noLegalOpponentMeansNoThrow() {
        var context = context(cell(6, 0), 100, s -> s.health(MAX_HEALTH, MAX_HEALTH), null, null);
        assertEquals(AiAction.Kind.END_TURN, new WitchTacticalAi().planTurn(context).kind());
    }

    @Test
    void anUnaffordableThrowIsNeverTaken() {
        var context = context(cell(6, 0), THROW_COST - 1, s -> s.health(MAX_HEALTH, MAX_HEALTH));
        assertEquals(AiAction.Kind.END_TURN, new WitchTacticalAi().planTurn(context).kind());
    }

    // ---- typed payload -----------------------------------------------------------------------------

    @Test
    void potionActionsCarryOnlyTheirOwnBrew() {
        assertEquals(WitchBrew.POISON, AiAction.throwPotion(WitchBrew.POISON).brew());
        assertEquals(WitchBrew.HEALING, AiAction.drink(WitchBrew.HEALING).brew());
        assertThrows(IllegalArgumentException.class, () -> AiAction.drink(WitchBrew.POISON));
        assertThrows(IllegalArgumentException.class, () -> AiAction.throwPotion(WitchBrew.HEALING));
        assertThrows(IllegalArgumentException.class,
                () -> new AiAction(AiAction.Kind.MELEE, List.of(), -1, null, null, WitchBrew.POISON));
        assertThrows(IllegalArgumentException.class,
                () -> new AiAction(AiAction.Kind.DRINK_POTION, List.of(), -1, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AiAction(AiAction.Kind.THROW_POTION, List.of(), -1, null, null, WitchBrew.HEALING));
    }

    @Test
    void olderConstructorsKeepWorking() {
        assertEquals(AiAction.Kind.END_TURN, AiAction.endTurn().kind());
        assertEquals(AiAction.Kind.MOVE, new AiAction(AiAction.Kind.MOVE, List.of(cell(1, 0))).kind());
        assertEquals(AiAction.Kind.MELEE, AiAction.of(AiAction.Kind.MELEE).kind());
        assertNull(new AiAction(AiAction.Kind.MELEE, List.of(), -1, null, null).brew());
    }

    // ---- fixture -----------------------------------------------------------------------------------

    private static GridPos cell(int x, int z) { return new GridPos(x, 64, z); }

    private static Context context(GridPos actor, int ap, StateSpec spec) {
        Builder builder = spec.apply(new Builder(true));
        return new Context(actor, ap, builder.buildState(), builder.hasTarget, builder.clear,
                builder.standable);
    }

    private static Context context(GridPos actor, int ap, StateSpec spec, Predicate<GridPos> clear) {
        Builder builder = spec.apply(new Builder(clear != null));
        builder.clear = clear;
        return new Context(actor, ap, builder.buildState(), builder.hasTarget, builder.clear,
                builder.standable);
    }

    private static Context context(GridPos actor, int ap, StateSpec spec, Predicate<GridPos> clear,
                                   Predicate<GridPos> standable) {
        Builder builder = spec.apply(new Builder(clear != null));
        builder.clear = clear;
        if (standable != null) builder.standable = standable;
        return new Context(actor, ap, builder.buildState(), builder.hasTarget, builder.clear,
                builder.standable);
    }

    interface StateSpec { Builder apply(Builder builder); }

    static final class Builder {
        final boolean hasTarget;
        float health = MAX_HEALTH;
        float maxHealth = MAX_HEALTH;
        Float fraction;
        EnumSet<WitchBrew> self = EnumSet.noneOf(WitchBrew.class);
        EnumSet<WitchBrew> target = EnumSet.noneOf(WitchBrew.class);
        EnumSet<WitchBrew> notReceptive = EnumSet.noneOf(WitchBrew.class);
        boolean water;
        boolean burning;
        boolean ready = true;
        float targetHealth = 20.0F;
        WitchBrew pending;
        Predicate<GridPos> clear = pos -> true;
        Predicate<GridPos> standable = pos -> pos.y() == 64 && Math.abs(pos.x()) <= 30 && Math.abs(pos.z()) <= 4;

        Builder(boolean hasTarget) { this.hasTarget = hasTarget; }

        Builder health(float value, float max) { this.health = value; this.maxHealth = max; return this; }
        Builder fraction(float value) { this.fraction = value; return this; }
        Builder self(EnumSet<WitchBrew> brews) { this.self = brews; return this; }
        Builder target(EnumSet<WitchBrew> brews) { this.target = brews; return this; }
        Builder notReceptive(EnumSet<WitchBrew> brews) { this.notReceptive = brews; return this; }
        Builder water(boolean value) { this.water = value; return this; }
        Builder burning(boolean value) { this.burning = value; return this; }
        Builder notReady() { this.ready = false; return this; }
        Builder targetHealth(float value) { this.targetHealth = value; return this; }
        Builder pending(WitchBrew brew) { this.pending = brew; return this; }

        WitchState buildState() {
            EnumSet<WitchBrew> receptive = EnumSet.noneOf(WitchBrew.class);
            for (WitchBrew brew : WitchBrew.values()) {
                if (brew.thrown() && !notReceptive.contains(brew)) receptive.add(brew);
            }
            float healthFraction = fraction != null ? fraction : health / maxHealth;
            return new WitchState(ready, hasTarget, DRINK_COST, THROW_COST, healthFraction,
                    Math.max(0.0F, maxHealth - health), 4.0F, (float) WitchTacticalAi.DEFAULT_HEAL_THRESHOLD,
                    water, burning, hasTarget ? targetHealth : 0.0F, self, target, receptive, pending);
        }
    }

    private static final class Context implements AiContext {
        private final GridPos actorCell;
        private final int ap;
        private final WitchState state;
        private final boolean hasTarget;
        private final Predicate<GridPos> clear;
        private final Predicate<GridPos> standable;
        private boolean cleared;

        Context(GridPos actorCell, int ap, WitchState state, boolean hasTarget, Predicate<GridPos> clear,
                Predicate<GridPos> standable) {
            this.actorCell = actorCell;
            this.ap = ap;
            this.state = state;
            this.hasTarget = hasTarget;
            this.clear = clear;
            this.standable = standable;
        }

        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos actorCell() { return actorCell; }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public GridPos targetCell() { return cell(0, 0); }
        @Override public int ap() { return ap; }
        @Override public int moveCost() { return 10; }
        @Override public WitchState witch() { return state; }
        @Override public boolean canThrowFrom(GridPos from) {
            return hasTarget && Math.abs(from.x()) <= 10 && clear.test(from);
        }
        @Override public void clearPendingDrink() { cleared = true; }
        @Override public boolean drinkPotion(WitchBrew brew) { return true; }
        @Override public boolean throwPotion(WitchBrew brew) { return true; }
        @Override public List<GridPos> pathToPlayer() { return List.of(); }
        @Override public List<GridPos> standableNeighbors(GridPos from) {
            var result = new ArrayList<GridPos>();
            for (GridPos pos : List.of(from.offset(-1, 0, 0), from.offset(1, 0, 0),
                    from.offset(0, 0, -1), from.offset(0, 0, 1))) {
                if (standable.test(pos)) result.add(pos);
            }
            return result;
        }
        @Override public boolean occupied(GridPos pos) { return pos.equals(targetCell()); }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) { return a.horizontalDistance(b) == 1; }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return clear.test(a); }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }
}
