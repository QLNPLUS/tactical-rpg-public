package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.ai.TacticalWitchAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hold only the witch's own potion segment while this mod controls her; {@code super.aiStep} and every other
 * entity hook keep running.
 *
 * <p>Vanilla picks and counts down a drink inside {@code Witch#aiStep}, outside the goal system, so NoAI is not
 * enough to stop a witch from healing herself on somebody else's turn. The segment is the first statement
 * group of that method, guarded by {@code !level().isClientSide && isAlive()}: gating that one {@code isAlive}
 * call skips exactly the potion work and nothing else. No global {@code aiStep} cancel, no second tick path.</p>
 *
 * <p>Skipping the segment is also what makes a multi-tick drink of ours possible: this mod sets the vanilla
 * {@code using} flag and countdown for its own bounded presentation, and since vanilla's own decrement and
 * settlement never run while control holds, nothing can settle that as a second dose. Nothing here wipes a
 * presentation every tick - the battle owns it and is the only thing that finishes or clears it.</p>
 *
 * <p>The capture half exists because a witch can already be mid-drink when the encounter admits her: that drink
 * must not settle for free, and it must not be swallowed either - it is given back unchanged if the battle
 * never charges for it. Only a real vanilla drinking action is ever taken, so a witch holding an axe, a spare
 * potion or nothing at all is never treated as if she were drinking.</p>
 */
@Mixin(Witch.class)
public abstract class TacticalWitchMixin implements TacticalWitchAccess {
    /** Vanilla's own drinking modifier, so removal and re-application act on the same id. */
    @Unique private static final ResourceLocation TACTICAL$DRINKING =
            ResourceLocation.withDefaultNamespace("drinking");
    @Unique private static final AttributeModifier TACTICAL$DRINKING_MODIFIER =
            new AttributeModifier(TACTICAL$DRINKING, -0.25, AttributeModifier.Operation.ADD_VALUE);

    @Shadow private int usingTime;
    @Unique private boolean tactical$controlled;

    @Override public void tactical$setControlled(boolean controlled) { this.tactical$controlled = controlled; }

    @Override public boolean tactical$isControlled() { return tactical$controlled; }

    @Override public boolean tactical$isDrinking() { return ((Witch) (Object) this).isDrinkingPotion(); }

    @Override public int tactical$drinkCountdown() { return usingTime; }

    @Override
    public WitchDrink tactical$takeDrink() {
        Witch witch = (Witch) (Object) this;
        if (!witch.isDrinkingPotion()) return null;
        AttributeInstance speed = witch.getAttribute(Attributes.MOVEMENT_SPEED);
        WitchDrink drink = new WitchDrink(witch.getMainHandItem(), usingTime, true,
                speed != null && speed.getModifier(TACTICAL$DRINKING) != null);
        tactical$clearDrink();
        // The bottle vanilla was really using goes with the action it belonged to; nothing else is touched.
        witch.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return drink;
    }

    @Override
    public void tactical$restoreDrink(WitchDrink drink) {
        if (drink == null) {
            tactical$clearDrink();
            return;
        }
        Witch witch = (Witch) (Object) this;
        AttributeInstance speed = witch.getAttribute(Attributes.MOVEMENT_SPEED);
        witch.setItemSlot(EquipmentSlot.MAINHAND, drink.potion());
        usingTime = drink.usingTime();
        witch.setUsingItem(drink.drinking());
        if (speed != null) {
            speed.removeModifier(TACTICAL$DRINKING);
            if (drink.slowed()) speed.addTransientModifier(TACTICAL$DRINKING_MODIFIER);
        }
    }

    /**
     * Clear the drink presentation only: flag, countdown and the drinking slowdown.
     *
     * <p>No equipment is touched here. Whoever borrowed a stack for the action owns giving that stack back, so
     * an unrelated main-hand item is never deleted, hidden or overwritten by clearing a pose.</p>
     */
    @Override
    public void tactical$clearDrink() {
        Witch witch = (Witch) (Object) this;
        witch.setUsingItem(false);
        usingTime = 0;
        AttributeInstance speed = witch.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(TACTICAL$DRINKING);
    }

    /**
     * Skip vanilla's autonomous potion segment only while a battle controls this witch.
     *
     * <p>The redirect keeps {@code super.aiStep()} and ordinary entity ticking intact; an uncontrolled witch
     * answers exactly its own value, so out-of-battle behaviour is unchanged.</p>
     */
    @Redirect(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Witch;isAlive()Z"))
    private boolean tactical$skipVanillaDose(Witch witch) {
        return witch.isAlive() && !tactical$controlled;
    }
}
