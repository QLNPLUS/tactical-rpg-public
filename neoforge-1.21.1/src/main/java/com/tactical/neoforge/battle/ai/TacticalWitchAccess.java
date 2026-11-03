package com.tactical.neoforge.battle.ai;

import net.minecraft.world.item.ItemStack;

/**
 * The one narrow handle the battle needs on a vanilla witch: whether it is under this mod's control, and what
 * the drink it had in progress really was.
 *
 * <p>Vanilla settles a witch's potion from her own {@code aiStep} countdown, which keeps running under NoAI.
 * Control therefore has to be able to (a) skip that segment, (b) take the unfinished drink out of the entity
 * without ever letting it settle, and (c) give exactly that unfinished drink back when the unit leaves. None of
 * the three touches health, position, unrelated equipment or the rest of her state.</p>
 *
 * <p>Everything here acts on a real vanilla drinking action only. A witch merely holding a potion in her hand -
 * or an axe, or anything else - is not drinking, so nothing is captured, cleared or renamed for her.</p>
 */
public interface TacticalWitchAccess {
    void tactical$setControlled(boolean controlled);

    boolean tactical$isControlled();

    /** True while the entity carries vanilla's own "drinking potion" state. */
    boolean tactical$isDrinking();

    /**
     * Take the vanilla drink in progress out of the entity in one step, or return null and touch nothing.
     *
     * <p>The bottle that vanilla was really using goes with the captured progress: it is the unfinished action,
     * not unrelated equipment, and the caller owns it until it either restores it or settles a paid dose.</p>
     */
    WitchDrink tactical$takeDrink();

    /** Put an unfinished drink back exactly as it was; null clears only a presentation this battle owns. */
    void tactical$restoreDrink(WitchDrink drink);

    /** Read-only: vanilla's own remaining drink countdown, for diagnostics and fixtures. Changes nothing. */
    int tactical$drinkCountdown();

    /** Clear vanilla's drink presentation: flag, countdown and the drinking slowdown. Never unrelated items. */
    void tactical$clearDrink();

    /**
     * One vanilla drink, captured before control touched it.
     *
     * @param potion   the real main-hand stack at that moment (the potion vanilla would have consumed)
     * @param usingTime the remaining vanilla countdown, so a normal exit gives the same progress back
     * @param drinking  the vanilla "drinking potion" flag
     * @param slowed    whether vanilla's own drinking speed modifier was applied
     */
    record WitchDrink(ItemStack potion, int usingTime, boolean drinking, boolean slowed) {
        public WitchDrink {
            potion = potion == null ? ItemStack.EMPTY : potion.copy();
        }
    }
}
