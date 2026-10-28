package com.tactical.core.zone;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BattleZoneTest {
 @Test void actualMembershipDiffersByShapeAndHonorsHeight() {
  var center=new GridPos(0,60,0);
  var round=new BoundedBattleZone(center,4,2,false);
  var box=new BoundedBattleZone(center,4,2,true);
  assertFalse(round.contains(new GridPos(4,60,4)));
  assertTrue(box.contains(new GridPos(4,60,4)));
  assertFalse(box.contains(new GridPos(0,63,0)));
 }
 @Test void expansionIncludesHorizontalAndVerticalNewcomer() {
  BattleZone zone=new BoundedBattleZone(new GridPos(0,60,0),4,2,false);
  var newcomer=new GridPos(7,65,7);zone=zone.including(newcomer);
  assertTrue(zone.contains(newcomer));
  assertTrue(zone.contains(new GridPos(0,60,0)));
  assertTrue(zone.bounds().maxY()>=65);
 }
}
