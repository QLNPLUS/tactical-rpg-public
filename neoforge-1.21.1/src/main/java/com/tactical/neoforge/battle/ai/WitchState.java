package com.tactical.neoforge.battle.ai;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One read-only snapshot of what a potion user may do right now.
 *
 * <p>The tactics read this instead of reaching into a living entity, so the whole priority order (which brew,
 * whether it is affordable, whether the target would really be affected) is decidable in a pure test without a
 * world. The server fills it in exactly once per question, from the same authoritative state the action will be
 * charged from, so a decision and its price never come from two different reads.</p>
 *
 * @param ready          the shared self-drink cooldown allows a drink on this own turn, and it is this actor's
 *                       turn to act
 * @param targetPresent  this own turn locked an opponent that is still legal
 * @param drinkCost      already-quoted AP price of one self-drink; 0 when it cannot drink at all
 * @param throwCost      already-quoted AP price of one thrown potion; 0 when it cannot throw at all
 * @param healthFraction own health as a fraction of its own maximum
 * @param missingHealth  health points missing right now, so a dose that would be wasted is not spent
 * @param healDose       health points one ordinary healing dose really restores for this actor
 * @param healThreshold  configured fraction below which the ordinary (non-emergency) heal applies
 * @param eyeInWater     the actor's eyes are in water and it has no water breathing
 * @param burning        the actor is on fire and has no fire resistance
 * @param targetHealth   the locked opponent's current health, 0 when there is no opponent
 * @param activeSelf     self buffs that are still fully effective on the actor; never re-drunk
 * @param activeTarget   debuffs still fully effective on the opponent; never re-applied
 * @param receptive      brews that would really do something to the opponent (immunity, reverse healing)
 * @param pending        a drink already in progress when the encounter took control, still to be paid for
 */
public record WitchState(boolean ready, boolean targetPresent, int drinkCost, int throwCost,
                         float healthFraction, float missingHealth, float healDose, float healThreshold,
                         boolean eyeInWater, boolean burning, float targetHealth,
                         Set<WitchBrew> activeSelf, Set<WitchBrew> activeTarget, Set<WitchBrew> receptive,
                         WitchBrew pending) {
    /** An actor with no potions at all: every tactic silently declines. */
    public static final WitchState NONE = new WitchState(false, false, 0, 0, 1.0F, 0.0F, 0.0F, 0.0F,
            false, false, 0.0F, EnumSet.noneOf(WitchBrew.class), EnumSet.noneOf(WitchBrew.class),
            EnumSet.noneOf(WitchBrew.class), null);

    public WitchState {
        activeSelf = activeSelf == null || activeSelf.isEmpty()
                ? EnumSet.noneOf(WitchBrew.class) : EnumSet.copyOf(activeSelf);
        activeTarget = activeTarget == null || activeTarget.isEmpty()
                ? EnumSet.noneOf(WitchBrew.class) : EnumSet.copyOf(activeTarget);
        receptive = receptive == null || receptive.isEmpty()
                ? EnumSet.noneOf(WitchBrew.class) : EnumSet.copyOf(receptive);
    }

    /** True when this actor can really drink right now: the cooldown allows it and it can pay for it. */
    public boolean canDrink(int ap) { return ready && drinkCost > 0 && ap >= drinkCost; }

    /** True when this actor can really throw right now: it has an opponent, a price and can pay for it. */
    public boolean canThrow(int ap) { return targetPresent && throwCost > 0 && ap >= throwCost; }

    public boolean hasSelf(WitchBrew brew) { return activeSelf.contains(brew); }

    public boolean hasTarget(WitchBrew brew) { return activeTarget.contains(brew); }

    public boolean receptiveTo(WitchBrew brew) { return receptive.contains(brew); }

    /**
     * Whether drinking that brew right now would really change something for this witch.
     *
     * <p>This is the benefit test a carried-over drink has to pass as well: an action inherited from before the
     * encounter is not a licence to drink something the battle would gain nothing from (a healing dose when no
     * health is missing, a buff that is already fully in place). The same question is asked again at the one
     * settlement point, from fresh state.</p>
     */
    public boolean beneficial(WitchBrew brew) {
        if (brew == null || !brew.selfDrink()) return false;
        return switch (brew) {
            case HEALING -> healDose > 0.0F && missingHealth >= healDose;
            case WATER_BREATHING -> eyeInWater && !hasSelf(WitchBrew.WATER_BREATHING);
            case FIRE_RESISTANCE -> burning && !hasSelf(WitchBrew.FIRE_RESISTANCE);
            case SWIFTNESS -> !hasSelf(WitchBrew.SWIFTNESS);
            default -> false;
        };
    }

    /** Immutable view for callers that must not mutate the snapshot. */
    public Set<WitchBrew> activeSelfView() { return Collections.unmodifiableSet(activeSelf); }
}
