package com.tactical.neoforge.client;

import net.minecraft.world.entity.Entity;

/** Restores real render-view identity after proxy-assisted camera placement. */
public interface CameraViewEntityAccess {
    void tactical$setViewEntity(Entity entity);
}
