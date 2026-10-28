package com.tactical.neoforge;

import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BattleStatePacketTest {
 @Test void chargeOrderIdsSurviveCodecAndDoNotAssumeParticipantOrder(){
  var session=java.util.UUID.randomUUID();
  var packet=new BattleStatePacket(true,2,7,35,150,12,0,99,0,new int[]{1,99,0},"zombie",List.of("zombie","player"),7,
          "bow",1,16,0,99,0,24,false,72,new int[]{12,100,100,72,18,20},new int[]{100,24},3,false,new int[]{72,12},19,12,15,70,2,session,41L,777L,9L);
  var buffer=new FriendlyByteBuf(Unpooled.buffer());
  try {
   BattleStatePacket.STREAM_CODEC.encode(buffer,packet);
   var decoded=BattleStatePacket.STREAM_CODEC.decode(buffer);
   assertArrayEquals(new int[]{72,12},decoded.turnEntityIds());assertArrayEquals(packet.gauges(),decoded.gauges());
   assertArrayEquals(packet.participants(),decoded.participants());assertEquals(packet.order(),decoded.order());assertEquals(packet.ap(),decoded.ap());
    assertEquals(19,decoded.attackCost());assertEquals(12,decoded.itemCost());assertEquals(15,decoded.placeCost());
    assertEquals(70,decoded.apRecovery());assertEquals(2,decoded.paidActionCount());assertEquals(0,buffer.readableBytes());
    assertEquals(session,decoded.sessionId());assertEquals(41L,decoded.turnEpoch());
  }finally{buffer.release();}
 }
 @Test void inactiveSnapshotHasNoStalePortraitOrHealthIds(){
  var packet=BattleStatePacket.inactive();assertFalse(packet.active());assertEquals(0,packet.turnEntityIds().length);assertEquals(0,packet.participants().length);assertNull(packet.sessionId());assertEquals(0L,packet.turnEpoch());
 }
}
