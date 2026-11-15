package com.tactical.neoforge.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;

/**
 * The narrow answer to "may vanilla's own thorn reaction run at all for this held body".
 *
 * <p>Vanilla's guardian spikes are passive: inside {@code Guardian.hurt}, before {@code super.hurt}, a body that
 * is not moving hurts the <em>direct</em> attacker for 2 with its own {@code thorns} damage source. It never checks
 * whether the main hit later lands, so a cancelled main hit does not cancel the spikes - that order is vanilla's
 * and is preserved here.</p>
 *
 * <p>What an encounter must not accept is the reaction being used by somebody who has no business touching this
 * body: a same-side unit, a bystander that never joined, a unit of another encounter. A controlled guardian
 * therefore asks the encounter that holds it, and only a real opposed participant of that same encounter gets the
 * native reaction. A wild guardian - no handler installed - keeps vanilla exactly, because this mod has no claim on
 * it.</p>
 */
public final class GuardianThorns {

    private GuardianThorns() { }

    /**
     * Whether the native spike call for that attacker may proceed.
     *
     * <p>Everything else about the reaction is untouched: the same {@code !isMoving} gate, the same damage-type
     * tags, the same "direct entity is a living entity" test and the same 2.0 amount, all decided by vanilla in the
     * same instruction. Nothing is charged for it: the spikes take no AP and never touch the paid-action counter.</p>
     */
    public static boolean allowsNativeThorns(Guardian guardian, LivingEntity attacker) {
        if (guardian == null || attacker == null) return false;
        if (guardian.level().isClientSide) return true;
        GuardianMotionHandler handler = ((TacticalGuardianAccess) guardian).tactical$motionHandler();
        if (handler == null) return true;
        return handler.tactical$allowsThorns(guardian, attacker);
    }
}
