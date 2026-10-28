package com.tactical.neoforge.client;

import com.tactical.core.combat.BattlePhase;
import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BattleClientStateTest {
    @Test void exitClearsEveryActionAndActorReferenceAndIsIdempotent() {
        BattleClientState.active=true;
        BattleClientState.phase=BattlePhase.PLAYER_TURN.ordinal();
        BattleClientState.round=9;
        BattleClientState.ap=77; BattleClientState.maxAp=100; BattleClientState.moveCost=12;
        BattleClientState.playerX=1; BattleClientState.playerY=99; BattleClientState.playerZ=2;
        BattleClientState.reachable.add(new GridPos(1,99,3));
        BattleClientState.actor="another player's avatar";
        BattleClientState.order=List.of("owner","enemy");
        BattleClientState.gauges=new int[]{50,100}; BattleClientState.cursor=1;
        BattleClientState.weapon="bow"; BattleClientState.weaponKind=1; BattleClientState.weaponRange=16;
        BattleClientState.centerX=1; BattleClientState.centerY=99; BattleClientState.centerZ=2; BattleClientState.zoneRadius=24;
        BattleClientState.moving=true; BattleClientState.actorEntityId=42; BattleClientState.actionCount=8;
        BattleClientState.defending=true; BattleClientState.mode=BattleMode.MOVE; BattleClientState.selectedSlot=4;
        BattleClientState.participantHealth.put(42,new int[]{1,20});
        BattleClientState.dirty=false;
        for(int i=0;i<2;i++) {
            BattleClientState.clear();
            assertFalse(BattleClientState.active);assertFalse(BattleClientState.isPlayerTurn());
            assertEquals(BattlePhase.INACTIVE.ordinal(),BattleClientState.phase);
            assertEquals(1,BattleClientState.round);
            assertEquals(0,BattleClientState.ap+BattleClientState.maxAp+BattleClientState.moveCost);
            assertEquals(new GridPos(0,0,0),BattleClientState.playerCell());
            assertTrue(BattleClientState.reachable.isEmpty());assertTrue(BattleClientState.participantHealth.isEmpty());
            assertEquals("",BattleClientState.actor);assertTrue(BattleClientState.order.isEmpty());assertEquals(0,BattleClientState.gauges.length);
            assertEquals(0,BattleClientState.cursor);assertEquals("",BattleClientState.weapon);
            assertEquals(0,BattleClientState.weaponKind+BattleClientState.weaponRange);
            assertEquals(0,BattleClientState.centerX+BattleClientState.centerY+BattleClientState.centerZ+BattleClientState.zoneRadius);
            assertFalse(BattleClientState.moving);assertEquals(-1,BattleClientState.actorEntityId);assertEquals(0,BattleClientState.actionCount);
            assertFalse(BattleClientState.defending);assertEquals(BattleMode.NONE,BattleClientState.mode);assertEquals(-1,BattleClientState.selectedSlot);assertTrue(BattleClientState.dirty);
            assertEquals(-1,BattleClientState.selfEntityId);assertNull(BattleClientState.sessionId);assertEquals(0L,BattleClientState.turnEpoch);
            assertNull(BattleClientState.stamp());
        }
    }

    /** Another player's turn also runs during PLAYER_TURN, so the phase alone is not enough. */
    @Test void onlyTheLocalActorsTurnIsActionable() {
        BattleClientState.clear();
        BattleClientState.active=true;
        BattleClientState.phase=BattlePhase.PLAYER_TURN.ordinal();
        BattleClientState.selfEntityId=11;
        BattleClientState.actorEntityId=11;
        assertTrue(BattleClientState.isPlayerTurn());

        BattleClientState.actorEntityId=12;
        assertFalse(BattleClientState.isPlayerTurn(),"another member's turn is read-only here");

        BattleClientState.actorEntityId=11;
        BattleClientState.phase=BattlePhase.ENEMY_TURN.ordinal();
        assertFalse(BattleClientState.isPlayerTurn(),"an enemy turn is never ours even when the actor id matches");

        BattleClientState.phase=BattlePhase.PLAYER_TURN.ordinal();
        BattleClientState.selfEntityId=-1;
        assertFalse(BattleClientState.isPlayerTurn(),"without a resolved local avatar nothing is actionable");
        BattleClientState.clear();
    }

    @Test void stampEchoesTheAuthoritativeSessionAndTurn() {
        BattleClientState.clear();
        java.util.UUID session=java.util.UUID.randomUUID();
        BattleClientState.active=true;
        BattleClientState.sessionId=session;
        BattleClientState.turnEpoch=4L;
        assertEquals(new com.tactical.neoforge.battle.multi.ActionStamp(session,4L),BattleClientState.stamp());
        BattleClientState.clear();
        assertNull(BattleClientState.stamp(),"no snapshot means no intent can be stamped");
    }
}
