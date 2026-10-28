package com.tactical.neoforge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.network.DefendPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefendPacketTest {
 @Test void allCardinalDefenseDirectionsSurviveWire(){
  var stamp=new ActionStamp(java.util.UUID.randomUUID(),7L);
  for(int yaw:new int[]{0,90,180,270}){
   var buffer=new FriendlyByteBuf(Unpooled.buffer());
   try{
    DefendPacket.STREAM_CODEC.encode(buffer,new DefendPacket(stamp,yaw));
    var decoded=DefendPacket.STREAM_CODEC.decode(buffer);
    assertEquals(yaw,decoded.yaw());
    assertEquals(stamp,decoded.stamp(),"defend keeps its session/turn authority token on the wire");
   }
   finally{buffer.release();}
  }
 }
}
