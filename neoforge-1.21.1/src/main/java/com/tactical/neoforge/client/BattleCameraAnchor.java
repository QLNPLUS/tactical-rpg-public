package com.tactical.neoforge.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Render-only eye-position anchor. Never spawned, ticked by the world, or sent to the server. */
final class BattleCameraAnchor extends Entity {
    BattleCameraAnchor(Level level) {
        super(EntityType.MARKER, level);
        noPhysics = true;
    }

    void place(Vec3 eyePosition, Entity viewSource, float partialTick) {
        setPos(eyePosition);
        xo = xOld = eyePosition.x;
        yo = yOld = eyePosition.y;
        zo = zOld = eyePosition.z;
        setYRot(viewSource.getViewYRot(partialTick));
        setXRot(viewSource.getViewXRot(partialTick));
        yRotO = getYRot();
        xRotO = getXRot();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
