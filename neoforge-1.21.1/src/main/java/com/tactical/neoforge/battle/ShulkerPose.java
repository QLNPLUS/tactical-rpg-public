package com.tactical.neoforge.battle;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

/**
 * The native pose and shell state a shulker had before this encounter touched it.
 *
 * <p>Taking a unit into a battle mutates three of its own native values: the synched attach face, the raw peek
 * (which also decides whether vanilla's covered-armour modifier is on the body) and the body's position. If the
 * admission then fails, the creature must be the same creature it was - not a shulker left closed, re-glued or
 * standing where somebody else put it.</p>
 *
 * <p>Kinds are deliberately separated here from the ordinary {@code restoreUnit} snapshot in the encounter, which
 * covers AI, persistence, target and position for every type. This one covers the three values only a shulker
 * has, and it is used <em>only</em> for a rollback: a normal exit keeps the real final position and the real
 * effects, because the body really went there.</p>
 */
public record ShulkerPose(Direction face, int rawPeek, boolean coveredArmour, Vec3 position) {

    /** Vanilla's own modifier id, reconstructed instead of guessed. */
    private static final ResourceLocation COVERED = ResourceLocation.withDefaultNamespace("covered");

    /** Read the three native values without changing anything. */
    public static ShulkerPose of(Shulker shulker) {
        if (shulker == null) return null;
        AttributeInstance armour = shulker.getAttribute(Attributes.ARMOR);
        boolean covered = armour != null && armour.getModifier(COVERED) != null;
        return new ShulkerPose(ShulkerNative.attachFace(shulker), ShulkerNative.rawPeek(shulker), covered,
                shulker.position());
    }

    /**
     * Write the saved values back, exactly.
     *
     * <p>The peek is written only when it really differs, so vanilla's own sound and armour edit happen once and
     * a body that was already as it was stays untouched. Writing peek {@code 0} restores the covered-armour
     * modifier through vanilla's own setter; writing any other peek removes it, which is the state an open shell
     * really has.</p>
     */
    public void restore(Shulker shulker) {
        if (shulker == null) return;
        ShulkerNative.setAttachFace(shulker, face);
        if (ShulkerNative.rawPeek(shulker) != rawPeek) {
            ShulkerNative.setRawPeek(shulker, rawPeek);
        } else if (rawPeek == 0 && !coveredArmour) {
            // Same peek but a missing modifier can happen when vanilla's own teleport wrote the data value
            // directly; one native write repairs it without inventing a different shell state.
            ShulkerNative.setRawPeek(shulker, rawPeek);
        }
        shulker.setPos(position.x, position.y, position.z);
        shulker.setDeltaMovement(Vec3.ZERO);
        shulker.fallDistance = 0.0F;
    }

    /** Diagnostic form: which three values a rollback would give back. */
    public String describe() {
        return face.getSerializedName() + "/peek=" + rawPeek + "/covered=" + coveredArmour;
    }
}
