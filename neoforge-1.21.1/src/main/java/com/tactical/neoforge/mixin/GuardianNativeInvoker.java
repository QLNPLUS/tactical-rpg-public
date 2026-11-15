package com.tactical.neoforge.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The two native guardian entries an encounter may drive, reached instead of re-implemented.
 *
 * <p>Both are package-private in {@code net.minecraft.world.entity.monster}, and both are read by the client
 * through synched data: {@code setMoving} writes the {@code data_moving} bit the tail, spike and bubble animation
 * answer to, and {@code setActiveAttackTarget} writes the id the beam is drawn from. A copy of either would be a
 * second source of truth - the client would keep animating the old one.</p>
 *
 * <p>The descriptors are fixed bytecode facts of this target version, not inherited guesses: {@code void
 * setMoving(boolean)} and {@code void setActiveAttackTarget(int)} on {@code Guardian}. A changed signature fails
 * the game's own mixin application instead of silently doing nothing.</p>
 */
@Mixin(Guardian.class)
public interface GuardianNativeInvoker {

    /** Write vanilla's own swimming bit, exactly as {@code Guardian.setMoving(boolean)} does. */
    @Invoker("setMoving")
    void tactical$setMoving(boolean moving);

    /** Write the target id the native beam presentation reads, exactly as the attack goal does. */
    @Invoker("setActiveAttackTarget")
    void tactical$setActiveAttackTarget(int entityId);

    /**
     * The synched attack-target data itself, so the raw id can be read and put back exactly.
     *
     * <p>{@code Guardian.getActiveAttackTarget()} answers {@code getTarget()} on the server, which is a different
     * fact from the id the client draws a beam from. An admission rollback has to give back the real raw id, so it is
     * read from the data accessor vanilla itself uses instead of being guessed from the mob's target.</p>
     */
    @Accessor("DATA_ID_ATTACK_TARGET")
    static EntityDataAccessor<Integer> tactical$attackTargetData() {
        throw new AssertionError("mixin accessor");
    }

    /**
     * The stroll goal the native attack goal ends with, readable and writable for the dedicated fixture only.
     *
     * <p>It exists so a fixture can make the <em>native</em> {@code GuardianAttackGoal.stop()} really throw at its own
     * last statement, which is the only way to prove on a real server that a throwing native stop neither refunds a
     * committed hit nor skips the borrowed state. Produkt code never calls it.</p>
     */
    @Accessor("randomStrollGoal")
    RandomStrollGoal tactical$randomStrollGoal();

    /** Set (or, for a fixture, clear) that stroll goal. */
    @Accessor("randomStrollGoal")
    void tactical$randomStrollGoal(RandomStrollGoal goal);
}
