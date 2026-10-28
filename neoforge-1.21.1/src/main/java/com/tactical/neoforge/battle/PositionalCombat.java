package com.tactical.neoforge.battle;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;

/** Position bonuses are restricted to real melee and arrow damage between this encounter's actors. */
public final class PositionalCombat {
 private PositionalCombat() { }
 public static boolean eligible(DamageSource source,LivingEntity attacker){
  if(ArrowImpactGeometry.isArrow(source.getDirectEntity()))return source.is(DamageTypes.ARROW);
  return source.getDirectEntity()==attacker&&(source.is(DamageTypes.PLAYER_ATTACK)
          ||source.is(DamageTypes.MOB_ATTACK)||source.is(DamageTypes.MOB_ATTACK_NO_AGGRO));
 }
 public static TacticalDamageMultipliers.Breakdown evaluate(LivingEntity attacker,LivingEntity target,DamageSource source){
  double x=attacker.getX()-target.getX(),z=attacker.getZ()-target.getZ(),sourceY=attacker.getY();
  boolean known=Double.isFinite(x)&&Double.isFinite(z)&&Double.isFinite(sourceY);
  if(ArrowImpactGeometry.isArrow(source.getDirectEntity())){
   AbstractArrow arrow=(AbstractArrow)source.getDirectEntity();Vec3 velocity=arrow.getDeltaMovement();
   x=-velocity.x;z=-velocity.z;
   if(x*x+z*z<1.0E-12){
    Vec3 location=source.getSourcePosition();x=location==null?0:location.x-target.getX();z=location==null?0:location.z-target.getZ();
   }
   // Missing launch metadata must not silently substitute the shooter's current height.
   sourceY=ArrowImpactGeometry.launchFeetY(arrow).orElse(target.getY());
   known=Double.isFinite(x)&&Double.isFinite(z)&&Double.isFinite(sourceY);
  }
  return TacticalDamageMultipliers.evaluate(new TacticalDamageMultipliers.Context(attacker,target,source,
          target.yBodyRot,x,z,sourceY,target.getY(),known));
 }
 public static String summary(TacticalDamageMultipliers.Breakdown factors,double defense){
  String sector=switch(factors.sector().name()){
   case "FRONT"->"正面";case "SIDE"->"侧击";case "REAR"->"背击";default->"来向未知";
  };
  return String.format(Locale.ROOT,"伤害修正 · %s ×%.2f · 高差 ×%.2f · 技能 ×%.2f · 防御 ×%.2f",
          sector,factors.direction(),factors.height(),factors.skill(),defense);
 }
}
