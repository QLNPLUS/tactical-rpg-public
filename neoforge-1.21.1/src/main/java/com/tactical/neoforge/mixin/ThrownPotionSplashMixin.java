package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BattlePotionSplash;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Narrow the candidate list of one battle bottle's splash; leave every other bottle exactly as vanilla left it.
 *
 * <p>The seam is the {@code getEntitiesOfClass} call at the top of {@code ThrownPotion.applySplash}, before the
 * per-entity loop. Filtering there covers the instant effects and the periodic ones with one rule, keeps
 * vanilla's range, its distance falloff, its "would this instance even survive" check and the real
 * {@code applyInstantenousEffect}/{@code addEffect} calls, and needs no source information that a global effect
 * event does not have.</p>
 *
 * <p>Only splash bottles are affected. A lingering bottle becomes an area cloud with its own application path,
 * which this filter deliberately does not claim to cover.</p>
 */
@Mixin(ThrownPotion.class)
public abstract class ThrownPotionSplashMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "applySplash", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> tactical$filterSplashCandidates(Level level, Class type, AABB box,
                                                              Iterable<MobEffectInstance> effects, Entity hit) {
        return BattlePotionSplash.filter((ThrownPotion) (Object) this, level.getEntitiesOfClass(type, box));
    }
}
