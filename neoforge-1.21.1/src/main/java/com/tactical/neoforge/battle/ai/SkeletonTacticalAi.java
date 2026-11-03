package com.tactical.neoforge.battle.ai;

import com.tactical.core.combat.RangedWeapon;
import com.tactical.neoforge.battle.MobRangedLoadout;
import net.minecraft.world.entity.Mob;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Bow tactics use the owner's world/ballistic checks, never vanilla facing-dependent sight.
 *
 * <p>Vanilla strays and bogged shoot through exactly this path: their own
 * {@code performRangedAttack -> getArrow} decides the tipped arrow, so this adapter only chooses the cell
 * and the one shot per own turn. It never builds an arrow of its own, and it never equips a bow.</p>
 *
 * <p>The adapter fires a bow and nothing else, so being armed means a bow is really in a hand - not that any
 * ranged item is. A crossbow held in one hand therefore never turns into a planned bow shot.</p>
 */
public final class SkeletonTacticalAi implements TacticalMobAi {
    private final RangedStandoff standoff;

    public SkeletonTacticalAi(Mob actor) {
        this(RangedWeapon.BOW, () -> MobRangedLoadout.of(RangedWeapon.BOW, actor) != null);
    }

    /** Allows decision tests without constructing a Minecraft world or mob. */
    SkeletonTacticalAi(BooleanSupplier hasBow) {
        this(RangedWeapon.BOW, hasBow);
    }

    SkeletonTacticalAi(RangedWeapon weapon, BooleanSupplier armed) {
        this.standoff = new RangedStandoff(Objects.requireNonNull(armed));
    }

    @Override
    public RangedWeapon rangedWeapon() { return RangedWeapon.BOW; }

    @Override
    public AiAction planTurn(AiContext context) { return standoff.planTurn(context); }

    @Override
    public AiAction afterMovement(AiContext context) { return standoff.afterMovement(context); }

    @Override
    public void onActionResolved(AiAction.Kind action) { standoff.onActionResolved(action); }
}
