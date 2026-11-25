package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tactical.neoforge.battle.ShulkerChildren;
import com.tactical.neoforge.battle.ShulkerHandler;
import com.tactical.neoforge.battle.ShulkerLandingGate;
import com.tactical.neoforge.battle.ShulkerNative;
import com.tactical.neoforge.battle.ShulkerReactions;
import com.tactical.neoforge.battle.TacticalShulkerAccess;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Route the native shulker behaviours through the encounter that controls the unit.
 *
 * <p>Each seam was checked against the real build bytecode instead of a guess, and each is a redirect or an
 * injection <em>around</em> a call vanilla itself makes - never a replacement of the method that contains it:</p>
 *
 * <ol>
 *   <li>{@code teleportSomewhere()} answers its own {@code isNoAi()} guard first. A controlled unit keeps its
 *       {@code NoAI} flag: only a relocation this encounter authorized for this very call may pass, consumed
 *       once, so vanilla's five native attempts, its {@code ±8} search, its support gates and the cancelable
 *       {@code EnderEntity} event all still run exactly as written.</li>
 *   <li>The {@code EnderEntity} event is wrapped. Vanilla lets <b>every</b> listener re-point the destination, so
 *       a gate registered at any event priority can be re-pointed afterwards; the only place the point that will
 *       really be used is known, and nothing has moved yet, is the instant the post returns. The validated face
 *       is then written instead of the pre-event one, which vanilla computed for a candidate the event may have
 *       replaced.</li>
 *   <li>{@code hurt} is bracketed. Vanilla's whole reaction - the low-health relocation and the same-family
 *       bullet reaction, with the replication that may follow it - lives inside that one call, so the permission
 *       and the child window are opened on entry and dropped on <em>every</em> return, with the real receipt
 *       settled there.</li>
 *   <li>{@code hitByShulkerBullet}'s {@code addFreshEntity} is observed <em>after</em> the world really
 *       registered the child. A level-join event fires before registration and may be cancelled, so it cannot
 *       serve as a receipt.</li>
 *   <li>{@code onPeekAmountChange()} pushes every nearby body with {@code Entity.move(MoverType.SHULKER, …)}.
 *       The call is routed so a protected unit is never moved by an opening shell, and an allowed body keeps
 *       vanilla's accepted vector through the encounter's own validated handoff instead of a second mover.</li>
 *   <li>{@code findNewAttachment()} re-glues a body whose support is gone to the first face it finds. The call is
 *       redirected so a controlled unit never silently attaches outside its own battlefield; the encounter then
 *       decides, on its own turn, whether to re-attach, relocate or release the unit.</li>
 * </ol>
 *
 * <p>No synched data is registered and no new state is invented: the handler is plain mixin state, so a wild
 * shulker keeps every vanilla behaviour.</p>
 */
@Mixin(Shulker.class)
public abstract class TacticalShulkerMixin implements TacticalShulkerAccess {

    @Unique private ShulkerHandler tactical$handler;

    @Override
    public void tactical$setHandler(ShulkerHandler handler) {
        tactical$handler = handler;
    }

    @Override
    public ShulkerHandler tactical$handler() {
        return tactical$handler;
    }

    /** One-call permission for the native relocation; the entity's own flag is never written. */
    @Redirect(method = "teleportSomewhere",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Shulker;isNoAi()Z"))
    private boolean tactical$authorizedRelocation(Shulker self) {
        if (self.level().isClientSide) return self.isNoAi();
        if (ShulkerNative.consumeAuthorization(self)) return false;
        return self.isNoAi();
    }

    /**
     * Judge the destination after the complete event post, and before vanilla writes the face or the position.
     *
     * <p>This is the only correct place: every listener has run, so the point named here is the point vanilla is
     * about to use. The gate may cancel the event, which vanilla already handles by nulling the face and skipping
     * {@code setPos} entirely.</p>
     */
    @Redirect(method = "teleportSomewhere",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/event/EventHooks;onEnderTeleport"
                            + "(Lnet/minecraft/world/entity/LivingEntity;DDD)"
                            + "Lnet/neoforged/neoforge/event/entity/EntityTeleportEvent$EnderEntity;"))
    private EntityTeleportEvent.EnderEntity tactical$gateFinalLanding(net.minecraft.world.entity.LivingEntity entity,
                                                                     double x, double y, double z) {
        EntityTeleportEvent.EnderEntity event = EventHooks.onEnderTeleport(entity, x, y, z);
        Shulker self = (Shulker) (Object) this;
        if (!self.level().isClientSide) ShulkerLandingGate.validateFinal(self, event);
        return event;
    }

    /**
     * Write the face the gate validated, not the one vanilla derived from the pre-event candidate.
     *
     * <p>After a re-pointed destination vanilla's own face can name a support that is not there. The gate has
     * already proved a legal face at the final quantised cell, so that one is written instead; when no gate is
     * armed the native face stays exactly as vanilla computed it.</p>
     */
    @Redirect(method = "teleportSomewhere",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/Shulker;setAttachFace(Lnet/minecraft/core/Direction;)V"))
    private void tactical$writeValidatedFace(Shulker self, Direction face) {
        Direction validated = self.level().isClientSide ? null : ShulkerLandingGate.takeValidatedFace(self);
        ((ShulkerNativeInvoker) self).tactical$setAttachFace(validated != null ? validated : face);
    }

    /** Restore the exact hurt scope on normal return and on a throwing native/mod callback. */
    @WrapMethod(method = "hurt")
    private boolean tactical$hurtScope(net.minecraft.world.damagesource.DamageSource source, float amount,
                                      Operation<Boolean> original) {
        Shulker self = (Shulker) (Object) this;
        if (self.level().isClientSide) return original.call(source, amount);
        try {
            ShulkerReactions.beginHurt(self);
            return original.call(source, amount);
        } finally {
            ShulkerReactions.endHurt(self);
        }
    }

    /**
     * Report a native-born child at the world's own receipt.
     *
     * <p>Vanilla creates the child itself inside {@code hitByShulkerBullet} - the native roll, origin and colour
     * copy - and calls {@code level.addFreshEntity}. Reading the returned boolean is reading the registration,
     * which a join event can never provide: that event fires before the add and may still cancel it.</p>
     */
    @Redirect(method = "hitByShulkerBullet",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean tactical$observeNativeChild(Level level, Entity entity) {
        boolean registered = level.addFreshEntity(entity);
        if (registered && entity instanceof Shulker child) {
            ShulkerChildren.report((Shulker) (Object) this, child);
        }
        return registered;
    }

    /** An opening shell never displaces a protected body, and never moves one twice. */
    @Redirect(method = "onPeekAmountChange",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;move"
                            + "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void tactical$guardPeekPush(Entity pushed, MoverType type, Vec3 motion) {
        ShulkerHandler handler = tactical$handler;
        if (handler != null && handler.tactical$push(pushed, type, motion)) return;
        pushed.move(type, motion);
    }

    /** A controlled body never re-glues itself outside its own battlefield. */
    @Redirect(method = "findNewAttachment",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/Shulker;setAttachFace(Lnet/minecraft/core/Direction;)V"))
    private void tactical$guardAttachment(Shulker self, Direction face) {
        ShulkerHandler handler = tactical$handler;
        if (handler != null && !handler.tactical$mayChangeAttachment(self, self.blockPosition(), face)) return;
        ((ShulkerNativeInvoker) self).tactical$setAttachFace(face);
    }
}
