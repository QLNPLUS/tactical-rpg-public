package com.tactical.neoforge.mixin;

import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The one native entry an encounter may use to relocate a controlled enderman.
 *
 * <p>Vanilla's {@code EnderMan#teleport(DDD)Z} is private, and it is the only path that keeps what a teleport really
 * is: it scans the support column under the target point, refuses motionless-or-water ground, fires
 * {@code EntityTeleportEvent.EnderEntity} so other mods can veto or re-point it, and then calls
 * {@code randomTeleport} - which is what plays the sound, emits the portal particles and posts the teleport game
 * event. Reaching around it with {@code randomTeleport} or {@code setPos} plus hand-made particles would be a
 * different behaviour wearing the same name, and the event could not cancel it.</p>
 *
 * <p>The descriptor is fixed bytecode fact, not an inherited guess: {@code private boolean teleport(double, double,
 * double)} in {@code net.minecraft.world.entity.monster.EnderMan}. A changed signature fails the game's own mixin
 * application instead of silently doing nothing.</p>
 */
@Mixin(EnderMan.class)
public interface EnderManTeleportInvoker {

    /** Drive one native teleport attempt at that exact point; the return value is vanilla's own. */
    @Invoker("teleport")
    boolean tactical$teleport(double x, double y, double z);
}
