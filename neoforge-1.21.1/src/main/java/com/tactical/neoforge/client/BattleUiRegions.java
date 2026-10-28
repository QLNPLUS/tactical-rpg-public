package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import net.minecraft.client.Minecraft;

/** Camera gestures belong to world space, not visible interface panels. */
public final class BattleUiRegions {
 private BattleUiRegions() { }
 public static Position cursor() {
  var mc=Minecraft.getInstance();
  return new Position((float)(mc.mouseHandler.xpos()*mc.getWindow().getGuiScaledWidth()/Math.max(1,mc.getWindow().getScreenWidth())),
          (float)(mc.mouseHandler.ypos()*mc.getWindow().getGuiScaledHeight()/Math.max(1,mc.getWindow().getScreenHeight())));
 }
 public static int hotbarSlot() {
  var mc=Minecraft.getInstance();if(mc.options.hideGui) return -1;
  Position p=cursor();int w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();
  if(p.y<h-22||p.y>h||p.x<w/2f-90||p.x>=w/2f+90) return -1;
  return (int)((p.x-(w/2f-90))/20);
 }
 public static boolean overUi() {
  var mc=Minecraft.getInstance();Position gui=cursor();
  if(BattleLog.containsAt(gui)) return true;
  if(mc.screen!=null&&!(mc.screen instanceof TacticalScreen)) return true;
  for(Document d:Document.getAll()) {
   if(!UiZoom.owns(d)||d.isDisposed())continue;
   Element e=d.getElementById("aui-scale-root");if(e==null)e=d.getElementById("charge-root");if(e==null||!e.isVisible||"none".equals(e.getComputedStyle().display))continue;
   Position p=d.screenToDocumentPosition(gui);var r=e.getBoundingClientRect();
   if(p.x>=r.x&&p.x<=r.x+r.width&&p.y>=r.y&&p.y<=r.y+r.height)return true;
  }
  int w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();
  return !mc.options.hideGui&&gui.x>=w/2f-115&&gui.x<=w/2f+115&&gui.y>=h-65;
 }
}
