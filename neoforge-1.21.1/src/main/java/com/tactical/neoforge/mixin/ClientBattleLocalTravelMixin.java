package com.tactical.neoforge.mixin;

import com.tactical.core.combat.MotionRules;
import com.tactical.neoforge.client.BattleClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One physics writer for the owning player's own body while an encounter really carries it.
 *
 * <p>Vanilla integrates levitation and gravity inside {@code LivingEntity.travel} on every local tick. For the
 * <em>owning</em> player that local integration fights the encounter: the client lifts itself, the server pins the
 * body back, and the unit visibly ramps and resets inside a single own turn instead of taking the one bounded
 * displacement its own turn bought. The accepted rule is already "one bounded displacement per own start, then an
 * exact hold, an own-clock expiry and a fall from the real feet", so the local integrator is not allowed to be a
 * second writer of the same body.</p>
 *
 * <p>The seam is deliberately as small as the situation: the {@code travel} integration of this one body is
 * skipped, and nothing else. The entity still ticks, aiStep still runs, effects, hurt, death and rendering are
 * untouched, and vanilla's own trailing animation update is still performed here so the walk animation keeps
 * following the position the server wrote. Every other body - another member, a mob, the local player outside a
 * battle, a local player whose battle ended or whose stamp is stale - stays fully native, because
 * {@link MotionRules#battleOwnsLocalTravel(boolean, boolean, boolean, boolean)} answers false for all of them.</p>
 *
 * <p>This is the client half of the repair; the server half hands the owning client the exact fractional feet of
 * each newly committed frame through vanilla's own acknowledged position correction. Loaded on the client only
 * through {@code tactical.effects.mixins.json}.</p>
 */
@Mixin(LivingEntity.class)
public abstract class ClientBattleLocalTravelMixin {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void tactical$letTheEncounterOwnThisBodysPhysics(Vec3 input, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // A client-only physical-side mixin also sees the integrated server's own bodies. Nothing about a
        // logical-server body's travel belongs to this client, so the answer is decided before the client
        // singleton or the client membership map is touched at all.
        if (!self.level().isClientSide) return;
        // Mojang's own name for this client's LocalPlayer. A null player simply compares false, which keeps the
        // body native.
        Minecraft minecraft = Minecraft.getInstance();
        boolean participant = BattleClientState.participantHealth.containsKey(self.getId());
        if (!MotionRules.battleOwnsLocalTravel(true, self == minecraft.player,
                BattleClientState.active, participant)) {
            return;
        }
        // Exactly vanilla's own trailing update from the same method: a local player is never a FlyingAnimal, so
        // the vertical component stays out of the walk animation and the flag is false.
        self.calculateEntityAnimation(false);
        ci.cancel();
    }
}
