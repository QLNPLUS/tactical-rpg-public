package com.tactical.neoforge.battle;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

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
 /**
  * The damage breakdown row for one hit, or null when every modifier was neutral.
  *
  * <p>Display only: the multipliers themselves, the damage that was really dealt and the raw combat events are
  * untouched. A factor whose two-decimal form is exactly {@code 1.00} is left out - it says nothing about this
  * hit - while a non-neutral factor and a zero multiplier ({@code 0.00}) are always shown, because "this hit
  * was reduced to nothing" is exactly what a player must be able to read. When nothing is left to say, no row
  * is produced at all instead of a header with no facts behind it.</p>
  *
  * <p>Every part is its own localizable component and the numbers are formatted on the argument side
  * ({@code Locale.ROOT}), so no language can move a digit, change a separator or leave a dangling one: the
  * separators are only written between fragments that really exist.</p>
  */
 public static Component summary(TacticalDamageMultipliers.Breakdown factors,double defense){
  if(factors==null)return null;
  List<Component> shown=new ArrayList<>();
  addFactor(shown,BattleText.KEY_DAMAGE_FACTOR_DIRECTION,factors.direction());
  addFactor(shown,BattleText.KEY_DAMAGE_FACTOR_HEIGHT,factors.height());
  addFactor(shown,BattleText.KEY_DAMAGE_FACTOR_SKILL,factors.skill());
  addFactor(shown,BattleText.KEY_DAMAGE_FACTOR_DEFENSE,defense);
  if(shown.isEmpty())return null;
  Component sector=switch(factors.sector().name()){
   case "FRONT"->Component.translatable(BattleText.KEY_SECTOR_FRONT);
   case "SIDE"->Component.translatable(BattleText.KEY_SECTOR_SIDE);
   case "REAR"->Component.translatable(BattleText.KEY_SECTOR_REAR);
   default->Component.translatable(BattleText.KEY_SECTOR_UNKNOWN);
  };
  MutableComponent row=Component.translatable(BattleText.KEY_DAMAGE_POSITION,sector);
  for(Component factor:shown)row.append(Component.literal(FACTOR_SEPARATOR)).append(factor);
  return row;
 }

 /** The separator between two modifier fragments; punctuation only, never part of a sentence. */
 private static final String FACTOR_SEPARATOR=" · ";
 /** The two-decimal form a factor has to have to count as "neutral" and be left out of the row. */
 private static final String NEUTRAL_FACTOR="1.00";

 private static void addFactor(List<Component> shown,String key,double value){
  String formatted=BattleText.decimal(value);
  if(NEUTRAL_FACTOR.equals(formatted))return;
  shown.add(Component.translatable(key,formatted));
 }
}
