package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.core.grid.GridTrajectory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;

/** Collision sweeps use the exact trajectory rendered by the owning client. */
public final class DropClearance {
 private DropClearance() {}
 public static boolean canDrop(Level level, Player actor, GridPos from, GridPos to, int maxDrop,
                                Predicate<GridPos> allowed) {
  int down=from.y()-to.y();
  if(down<1||down>maxDrop||Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())!=1) return false;
  return clear(level,actor,from,to,allowed,true);
 }
 public static boolean canStep(Level level, Player actor, GridPos from, GridPos to, Predicate<GridPos> allowed) {
  return com.tactical.core.grid.GridMovementRules.canStep(from,to) && clear(level,actor,from,to,allowed,false);
 }
 private static boolean clear(Level level, Player actor, GridPos from, GridPos to, Predicate<GridPos> allowed, boolean drop) {
  if(!allowed.test(to)||!LevelGridWorld.isStandable(level,to.x(),to.y(),to.z())) return false;
  AABB shape=actor.getBoundingBox().move(-actor.getX(),-actor.getY(),-actor.getZ());
  int samples=from.y()==to.y()?8:40;
  for(int i=0;i<=samples;i++) {
   var point=drop?com.tactical.core.grid.DropTrajectory.sample(from,to,i/(double)samples):GridTrajectory.sampleStep(from,to,i/(double)samples);
   Vec3 pos=new Vec3(point.x(),point.y(),point.z());
   GridPos cell=new GridPos(net.minecraft.util.Mth.floor(pos.x),net.minecraft.util.Mth.floor(pos.y),net.minecraft.util.Mth.floor(pos.z));
   if(!allowed.test(cell)) return false;
   AABB box=shape.move(pos);
   if(!level.noCollision(actor,box)||!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,box,
           other->other!=actor&&other.isAlive()&&!other.isSpectator()).isEmpty()) return false;
  }
  return true;
 }
 public static boolean clearTrajectorySegment(Level level, Player actor, GridPos from, GridPos to,
                                                double start, double end, boolean drop) {
  AABB shape=actor.getBoundingBox().move(-actor.getX(),-actor.getY(),-actor.getZ());
  int samples=Math.max(8,(int)Math.ceil((end-start)*80));
  for(int i=0;i<=samples;i++) {
   double t=start+(end-start)*i/samples;
   var p=drop?com.tactical.core.grid.DropTrajectory.sample(from,to,t):GridTrajectory.sampleStep(from,to,t);
   AABB box=shape.move(p.x(),p.y(),p.z());
   if(!level.noCollision(actor,box)||!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,box,
           other->other!=actor&&other.isAlive()&&!other.isSpectator()).isEmpty()) return false;
  }
  return true;
 }
 public static boolean clearSegment(Level level, Player actor, Vec3 from, Vec3 to) {
  AABB shape=actor.getBoundingBox().move(-actor.getX(),-actor.getY(),-actor.getZ());
  for(int i=0;i<=4;i++) {
   AABB box=shape.move(from.lerp(to,i/4.0));
   if(!level.noCollision(actor,box)||!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,box,
           other->other!=actor&&other.isAlive()&&!other.isSpectator()).isEmpty()) return false;
  }
  return true;
 }
 public static Vec3 sample(GridPos from, GridPos to, double t) {
  var pos=com.tactical.core.grid.DropTrajectory.sample(from,to,t);
  return new Vec3(pos.x(),pos.y(),pos.z());
 }
}
