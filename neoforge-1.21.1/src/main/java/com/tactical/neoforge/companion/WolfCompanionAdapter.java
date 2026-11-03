package com.tactical.neoforge.companion;

import com.tactical.neoforge.api.companion.CompanionAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Wolf;

import java.util.UUID;

/**
 * The vanilla wolf as a hand-commanded companion.
 *
 * <p>All of the kind-specific knowledge lives here: being tame, who tamed it, whether it was told to sit,
 * and the sitting pose that control has to lift and then give back. Everything else - the queue slot, AP,
 * the move animation, the native attack, the guard and the release path - is battle machinery shared with
 * any other registered companion.</p>
 */
public final class WolfCompanionAdapter implements CompanionAdapter {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("tactical", "wolf");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public boolean supports(Mob mob) {
        return mob instanceof Wolf;
    }

    @Override
    public UUID controllerId(Mob mob) {
        return mob instanceof Wolf wolf ? wolf.getOwnerUUID() : null;
    }

    @Override
    public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
        if (!(mob instanceof Wolf wolf) || controllerId == null) return false;
        if (!wolf.isTame()) return false;
        if (!controllerId.equals(wolf.getOwnerUUID())) return false;
        // A wolf left sitting was told to stay; it is not dragged into a fight by walking past it. Age and
        // breeding are not part of the approved rules, so they are not part of this check either.
        if (wolf.isOrderedToSit()) return false;
        return wolf.level() == level;
    }

    /**
     * The two sitting flags are captured separately: "ordered to sit" is the owner's instruction, the sitting
     * pose is what the entity is currently rendering, and a vanilla wolf can be in either state without the
     * other. Restoring one into the other would change what the owner asked for.
     */
    private record Pose(boolean orderedToSit, boolean sittingPose) {
    }

    @Override
    public Object capturePose(Mob mob) {
        return mob instanceof Wolf wolf ? new Pose(wolf.isOrderedToSit(), wolf.isInSittingPose()) : null;
    }

    /** Lifting the pose is what makes a commanded wolf actually walk on its own four feet again. */
    @Override
    public void beginControl(Mob mob) {
        if (!(mob instanceof Wolf wolf)) return;
        wolf.setOrderedToSit(false);
        wolf.setInSittingPose(false);
    }

    @Override
    public void restorePose(Mob mob, Object pose) {
        if (!(mob instanceof Wolf wolf) || !(pose instanceof Pose captured)) return;
        wolf.setOrderedToSit(captured.orderedToSit());
        wolf.setInSittingPose(captured.sittingPose());
    }
}
