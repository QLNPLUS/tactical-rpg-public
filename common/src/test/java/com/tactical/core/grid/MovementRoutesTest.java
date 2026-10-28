package com.tactical.core.grid;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementRoutesTest {
 private GridNavigator grid(){return new GridNavigator((x,z,y)->new GridPos(x,x<0?9:10,z));}
 @Test void bothDistinctRoutesRequireChoice(){
  var start=new GridPos(0,10,0);var target=new GridPos(-1,9,0);
  var routes=MovementRoutes.plan(grid(),start,target,p->true,(a,b)->true,8);
  assertTrue(routes.choice());assertEquals(3,routes.walk().size());assertEquals(1,routes.drop().size());
 }
 @Test void threeBlockedSidesLeaveOnlyPhysicalDrop(){
  var start=new GridPos(0,10,0);var target=new GridPos(-1,9,0);
  var routes=MovementRoutes.plan(grid(),start,target,p->p.x()<0,(a,b)->true,8);
  assertTrue(routes.walk().isEmpty());assertEquals(java.util.List.of(target),routes.drop());
 }
 @Test void rejectedPhysicalDropStillRequiresOrdinaryDetour(){
  var start=new GridPos(0,10,0);var target=new GridPos(-1,9,0);
  var routes=MovementRoutes.plan(grid(),start,target,p->true,(a,b)->false,8);
  assertEquals(3,routes.walk().size());assertEquals(target,routes.walk().get(2));
  assertTrue(routes.drop().isEmpty());
 }
 @Test void insufficientBudgetHidesUnaffordableDetour(){
  var routes=MovementRoutes.plan(grid(),new GridPos(0,10,0),new GridPos(-1,9,0),p->true,(a,b)->true,1);
  assertTrue(routes.walk().isEmpty());assertEquals(1,routes.drop().size());
 }
 @Test void rejectedDropCannotUseDirectDownhillWalkWithinOneStepBudget(){
  var routes=MovementRoutes.plan(grid(),new GridPos(0,10,0),new GridPos(-1,9,0),p->true,(a,b)->false,1);
  assertTrue(routes.walk().isEmpty());assertTrue(routes.drop().isEmpty());
 }
 @Test void blockedWalkingEdgesLeaveOnlyIndependentPhysicalDrop(){
  var routes=MovementRoutes.plan(grid(),new GridPos(0,10,0),new GridPos(-1,9,0),
    p->true,(a,b)->true,8,(a,b)->false);
  assertTrue(routes.walk().isEmpty());assertEquals(1,routes.drop().size());
 }
 @Test void rejectedIncomingEdgeDoesNotHideAReachableAlternative(){
  var navigator=new GridNavigator((x,z,y)->new GridPos(x,y,z));
  var start=new GridPos(0,10,0);var target=new GridPos(1,10,0);
  java.util.function.BiPredicate<GridPos,GridPos> edge=(a,b)->!(a.equals(start)&&b.equals(target));
  assertFalse(navigator.reachable(start,2,p->true,edge).containsKey(target));
  assertEquals(3,navigator.reachable(start,3,p->true,edge).get(target).intValue());
  var routes=MovementRoutes.plan(navigator,start,target,p->true,(a,b)->false,3,edge);
  assertEquals(3,routes.walk().size());assertEquals(target,routes.walk().get(2));
 }
}
