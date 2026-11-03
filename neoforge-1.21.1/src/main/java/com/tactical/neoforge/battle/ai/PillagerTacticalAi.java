package com.tactical.neoforge.battle.ai;

import com.tactical.core.combat.RangedWeapon;
import com.tactical.neoforge.battle.MobRangedLoadout;
import net.minecraft.world.entity.Mob;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Crossbow tactics: one salvo per own turn, from the same bounded stand-off framework a bow user walks.
 *
 * <p>Planning only decides whether a shot is legal and affordable from a cell. Loading an empty crossbow and
 * firing it in the same own turn is server-side and single-charged there, so the load never occupies a turn of
 * its own. An actor without a crossbow keeps its original melee behaviour: nothing is equipped by entity
 * type.</p>
 *
 * <p>Being armed means a crossbow is really in a hand, not that some other ranged item is: a bow in the main
 * hand does not become a planned crossbow shot, and the server resolves the same crossbow hand this adapter
 * priced.</p>
 */
public final class PillagerTacticalAi implements TacticalMobAi {
    private final RangedStandoff standoff;

    public PillagerTacticalAi(Mob actor) {
        this(RangedWeapon.CROSSBOW, () -> MobRangedLoadout.of(RangedWeapon.CROSSBOW, actor) != null);
    }

    /** Allows decision tests without constructing a Minecraft world or mob. */
    PillagerTacticalAi(BooleanSupplier hasCrossbow) {
        this(RangedWeapon.CROSSBOW, hasCrossbow);
    }

    PillagerTacticalAi(RangedWeapon weapon, BooleanSupplier armed) {
        this.standoff = new RangedStandoff(Objects.requireNonNull(armed));
    }

    @Override
    public RangedWeapon rangedWeapon() { return RangedWeapon.CROSSBOW; }

    @Override
    public AiAction planTurn(AiContext context) { return standoff.planTurn(context); }

    @Override
    public AiAction afterMovement(AiContext context) { return standoff.afterMovement(context); }

    @Override
    public void onActionResolved(AiAction.Kind action) { standoff.onActionResolved(action); }
}
