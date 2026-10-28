package com.tactical.core.grid;

/** Horizontal lip clearance followed by a gravity-like descent; shared for server/client collision/render. */
public final class DropTrajectory {
 private DropTrajectory() {}
 public static GridTrajectory.Position sample(GridPos from, GridPos to, double progress) {
  double t=Math.max(0,Math.min(1,progress));
  double horizontal=Math.min(1,t/0.5), fall=Math.max(0,(t-0.5)/0.5);
  return new GridTrajectory.Position(from.x()+0.5+(to.x()-from.x())*horizontal,
   from.y()+0.25*Math.sin(Math.PI*t)+(to.y()-from.y())*fall*fall,
   from.z()+0.5+(to.z()-from.z())*horizontal);
 }
}
