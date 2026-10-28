package com.tactical.neoforge.battle;

import net.minecraft.world.entity.player.Player;
import java.util.function.Supplier;

/** Scoped authorization for an AP-checked server action, never free vanilla right-click use. */
public final class BattleToolUse {
 private static final ThreadLocal<Player> ACTOR=new ThreadLocal<>();
 private BattleToolUse() { }
 public static boolean allowed(Player player){return ACTOR.get()==player;}
 public static <T>T run(Player player,Supplier<T> action){
  Player previous=ACTOR.get();ACTOR.set(player);
  try{return action.get();}finally{if(previous==null)ACTOR.remove();else ACTOR.set(previous);}
 }
}
