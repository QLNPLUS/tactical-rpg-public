package com.tactical.neoforge.battle;

import com.tactical.neoforge.mixin.GuardianNativeInvoker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;

/**
 * The narrow questions the guardian mixins and the encounter ask about one held body.
 *
 * <p>Two of vanilla's own facts are package-private - the {@code data_moving} synched bit's setter among them -
 * so they are reached through the mod's own invoker mixin instead of being duplicated. Recomputing them would
 * mean a second answer that can disagree with the client's, which is exactly what the tail and spike animations
 * read.</p>
 */
public final class GuardianMotion {

    private GuardianMotion() { }

    /**
     * Whether this encounter owns that exact body's displacement right now.
     *
     * <p>False for a wild guardian (no handler installed), for a released one (the handler is cleared) and on the
     * client, where nothing of this ruleset runs.</p>
     */
    public static boolean controls(LivingEntity actor) {
        if (!(actor instanceof Guardian guardian)) return false;
        if (guardian.level().isClientSide) return false;
        GuardianMotionHandler handler = ((TacticalGuardianAccess) guardian).tactical$motionHandler();
        return handler != null && handler.tactical$controls(guardian);
    }

    /**
     * Keep the native {@code data_moving} bit in step with the movement that really happens.
     *
     * <p>The bit is not decoration: vanilla reads it for the tail sweep, the spike retraction, the bubble trail and
     * its own "am I swimming" answer, and a client that was told the wrong one would animate a stationary body or
     * a swimming statue. It is written only for the two exact types this ruleset moves.</p>
     */
    public static void moving(LivingEntity actor, boolean moving) {
        if (!(actor instanceof Guardian guardian)) return;
        if (guardian.level().isClientSide) return;
        ((GuardianNativeInvoker) guardian).tactical$setMoving(moving);
    }
}
