package com.tactical.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The private and package-private shulker entries an encounter drives instead of copying.
 *
 * <p>Each is a bytecode fact of this target version, so a changed signature fails mixin application rather than
 * silently doing nothing. Reading the synched attach face and the raw peek is deliberately included: both are
 * facts the client also reads, and a second copy of either would keep drawing a state the body no longer has.</p>
 */
@Mixin(Shulker.class)
public interface ShulkerNativeInvoker {

    @Invoker("setAttachFace")
    void tactical$setAttachFace(Direction face);

    @Invoker("canStayAt")
    boolean tactical$canStayAt(BlockPos cell, Direction face);

    @Invoker("findAttachableSurface")
    Direction tactical$findAttachableSurface(BlockPos cell);

    @Invoker("setRawPeekAmount")
    void tactical$setRawPeek(int peek);

    @Invoker("getRawPeekAmount")
    int tactical$rawPeek();

    @Invoker("teleportSomewhere")
    boolean tactical$teleportSomewhere();

    @Invoker("getAttachFace")
    Direction tactical$attachFace();
}
