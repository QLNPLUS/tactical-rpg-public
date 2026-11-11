package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.SlimeSplitLifecycle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.MobSplitEvent;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * The two moments of a native split this encounter has to see, and nothing else.
 *
 * <p>Vanilla does all of it in {@code Slime#remove}: it copies the parent's own control flags onto every child,
 * optionally builds two to four children with {@code setSize(size / 2, true)}, offers that list to
 * {@link MobSplitEvent}, and only then adds what survived the event. Reproducing any of that here would be a second,
 * quietly different slime; the encounter only needs to know when it happens and what the parent's borrowed flags
 * were at that instant.</p>
 *
 * <p>{@link WrapMethod} brackets the <em>whole</em> method in a {@code try/finally}, so the per-thread scope is
 * restored on a normal return, on an exception thrown by any listener, and when a nested removal of another parent
 * happens inside this one. The two exits are still told apart: only a normal return proves vanilla finished
 * producing and adding children, so an exception leaves the wait unconfirmed rather than complete. The wrapped event
 * call reports what the child list became after every listener had its say. A slime this mod never took, a size-1
 * body, a living {@code discard} and every other mob are untouched: the hooks run, ask one identity question, and do
 * nothing.</p>
 */
@Mixin(Slime.class)
public abstract class SlimeSplitMixin {

    @WrapMethod(method = "remove")
    private void tactical$nativeRemove(Entity.RemovalReason reason, Operation<Void> original) {
        SlimeSplitLifecycle.Pending scope = SlimeSplitLifecycle.beforeNativeRemove((Slime) (Object) this);
        // The finally closes the scope either way, but only a normal return may be read as "vanilla really
        // produced and added its children". An exception therefore leaves the outcome unknown instead of
        // reporting a completed split.
        boolean returned = false;
        try {
            original.call(reason);
            returned = true;
        } finally {
            SlimeSplitLifecycle.afterNativeRemove(scope, returned);
        }
    }

    @WrapOperation(method = "remove",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/event/EventHooks;onMobSplit("
                            + "Lnet/minecraft/world/entity/Mob;Ljava/util/List;)"
                            + "Lnet/neoforged/neoforge/event/entity/living/MobSplitEvent;"))
    private MobSplitEvent tactical$observeSplitEvent(Mob parent, List<Mob> children,
                                                     Operation<MobSplitEvent> original) {
        MobSplitEvent event = original.call(parent, children);
        SlimeSplitLifecycle.afterSplitEvent(parent, children, event);
        return event;
    }
}
