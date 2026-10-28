package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.OptionalDouble;

/** Server-owned launch geometry: an arrow's impact never follows its shooter's later movement. */
@EventBusSubscriber(modid=Tactical.MOD_ID)
public final class ArrowImpactGeometry {
 private static final String Y="tactical:launch_feet_y";
 private static final String DIMENSION="tactical:launch_dimension";
 private ArrowImpactGeometry() { }
 public static boolean isArrow(Object entity){return entity instanceof AbstractArrow&&!(entity instanceof ThrownTrident);}
 @SubscribeEvent(priority=EventPriority.LOWEST)
 public static void onJoin(EntityJoinLevelEvent event){
  if(event.isCanceled()||event.getLevel().isClientSide||!isArrow(event.getEntity()))return;
  AbstractArrow arrow=(AbstractArrow)event.getEntity();
  if(!(arrow.getOwner() instanceof LivingEntity shooter))return;
  CompoundTag data=arrow.getPersistentData();
  if(!data.contains(Y)&&Double.isFinite(shooter.getY())){
   data.putDouble(Y,shooter.getY());data.putString(DIMENSION,shooter.level().dimension().location().toString());
  }
 }
 public static OptionalDouble launchFeetY(AbstractArrow arrow){
  CompoundTag data=arrow.getPersistentData();
  if(!data.contains(Y)||!arrow.level().dimension().location().toString().equals(data.getString(DIMENSION)))return OptionalDouble.empty();
  double y=data.getDouble(Y);return Double.isFinite(y)?OptionalDouble.of(y):OptionalDouble.empty();
 }
}
