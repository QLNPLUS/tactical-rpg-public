package com.tactical.neoforge.client;

import com.tactical.core.combat.BattlePhase;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.network.BattleStatePacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Client-side mirror of the latest battle snapshot. */
public final class BattleClientState {

    public static boolean active;
    public static int phase;
    public static int round = 1;
    public static int ap;
    public static int maxAp;
    public static double mp, maxMp, mpRecovery;
    public static int moveCost;
    public static int attackCost=10,itemCost=8,placeCost=10,apRecovery=50,paidActionCount;
    public static int playerX;
    public static int playerY;
    public static int playerZ;
    public static final Set<GridPos> reachable = new HashSet<>();
    public static String actor = "";
    public static List<String> order = List.of();
    public static int[] gauges = new int[0];
    public static int[] turnEntityIds = new int[0];
    public static int cursor;
    public static String weapon = "";
    public static int weaponKind;
    public static int weaponRange;
    public static int centerX;
    public static int centerY;
    public static int centerZ;
    public static int zoneRadius = com.tactical.core.combat.BattleRules.ZONE_RADIUS;
    public static boolean moving;
    public static int actorEntityId = -1;
    public static int actionCount;
    public static boolean defending;
    /** Encounter binding echoed in every client intent; null when no battle is active. */
    public static java.util.UUID sessionId;
    /** Authoritative turn generation of the last accepted snapshot. */
    public static long turnEpoch;
    /** This client's own entity id, captured from the local avatar, never from the packet. */
    public static int selfEntityId = -1;
    public static BattleMode mode = BattleMode.NONE;
    public static int selectedSlot = -1;
    public static final java.util.Map<Integer, int[]> participantHealth = new java.util.LinkedHashMap<>();
    public static boolean dirty;

    private BattleClientState() {
    }

    /**
     * The one place a server-stamped state payload becomes client state.
     *
     * <p>Membership is decided server-side and published as a monotonic generation; this method only
     * asks {@link BattleMembership} whether the payload may change anything. A newer generation is the
     * entry the server performed, an identical replay is idempotent, and everything else is refused, so
     * no click, page or stray packet can authorize a transition.</p>
     *
     * @return true when this payload was applied, false when it belonged elsewhere or was malformed.
     */
    public static boolean apply(BattleStatePacket packet) {
        if (packet == null || !packet.active()) return false;
        BattleMembership.Decision decision = BattleMembership.transition(packet.membershipServerEpoch(),
                packet.membershipGeneration(), packet.sessionId(), true);
        if (!BattleMembership.decided(decision)) return false;
        // Inside one membership the snapshot must not move the client backwards in turn order.
        if (decision == BattleMembership.Decision.ACCEPT_IDEMPOTENT && packet.turnEpoch() < turnEpoch) {
            return false;
        }
        active = true;
        phase = packet.phase();
        round = packet.round();
        ap = packet.ap();
        maxAp = packet.maxAp();
        moveCost = packet.moveCost();
        attackCost=packet.attackCost();itemCost=packet.itemCost();placeCost=packet.placeCost();
        apRecovery=packet.apRecovery();paidActionCount=packet.paidActionCount();
        playerX = packet.playerX();
        playerY = packet.playerY();
        playerZ = packet.playerZ();
        reachable.clear();
        int[] cells = packet.reachable();
        for (int i = 0; i + 2 < cells.length; i += 3) {
            reachable.add(new GridPos(cells[i], cells[i + 1], cells[i + 2]));
        }
        actor = packet.actor();
        order = new ArrayList<>(packet.order());
        gauges = packet.gauges().clone();
        turnEntityIds=packet.turnEntityIds().clone();
        cursor = packet.cursor();
        weapon = packet.weapon();
        weaponKind = packet.weaponKind();
        weaponRange = packet.weaponRange();
        centerX = packet.centerX();
        centerY = packet.centerY();
        centerZ = packet.centerZ();
        zoneRadius = packet.zoneRadius();
        moving = packet.moving();
        actorEntityId = packet.actorEntityId();
        actionCount = packet.actionCount();
        defending = packet.defending();
        sessionId = packet.sessionId();
        turnEpoch = packet.turnEpoch();
        participantHealth.clear();
        int[] participants = packet.participants();
        for (int i = 0; i + 2 < participants.length; i += 3) {
            participantHealth.put(participants[i], new int[]{participants[i + 1], participants[i + 2]});
        }
        dirty = true;
        return true;
    }

    /**
     * Apply the server's exit transition: the client stops showing that encounter.
     *
     * <p>Only a server-stamped newer generation may end membership here. The encounter id of the exit
     * is kept by the caller so the journal can refuse the older generation's traffic while a legitimate
     * re-entry (a newer generation for the same encounter) is still able to show it.</p>
     *
     * @return the membership decision, or null when the payload was refused. A caller must only tear
     *         the UI down for {@link BattleMembership.Decision#ACCEPT_TRANSITION}: an idempotent replay
     *         of the same exit must not close what the player has opened since.
     */
    public static BattleMembership.Decision applyInactive(BattleStatePacket packet) {
        if (packet == null || packet.active()) return null;
        BattleMembership.Decision decision = BattleMembership.transition(packet.membershipServerEpoch(),
                packet.membershipGeneration(), packet.sessionId(), false);
        if (!BattleMembership.decided(decision)) return null;
        clear();
        return decision;
    }

    /** True when this snapshot quotes different action costs, so the labels need rebuilding. */
    public static boolean quotesChanged(BattleStatePacket packet) {
        return packet != null && (attackCost != packet.attackCost() || itemCost != packet.itemCost()
                || placeCost != packet.placeCost());
    }

    /**
     * Clear the displayed encounter only.
     *
     * <p>The retirement marker survives on purpose: the UI teardown that follows a scoped clear must
     * not re-open the door for that encounter's delayed traffic. {@link #resetSession()} is the
     * lifecycle reset (logout, reconnect, unscoped resync) that forgets it.</p>
     */
    public static void clear() {
        active = false;
        phase = BattlePhase.INACTIVE.ordinal();
        round = 1;
        ap = maxAp = moveCost = 0;
        mp = maxMp = mpRecovery = 0;
        SkillClientState.clear();
        attackCost=itemCost=placeCost=apRecovery=paidActionCount=0;
        playerX = playerY = playerZ = 0;
        reachable.clear();
        actor = "";
        order = List.of();
        gauges = new int[0];
        turnEntityIds=new int[0];
        cursor = 0;
        weapon = "";
        weaponKind = weaponRange = 0;
        centerX = centerY = centerZ = zoneRadius = 0;
        moving = false;
        actorEntityId = -1;
        actionCount = 0;
        defending = false;
        sessionId = null;
        turnEpoch = 0L;
        selfEntityId = -1;
        mode = BattleMode.NONE;
        selectedSlot = -1;
        participantHealth.clear();
        dirty = true;
    }

    /**
     * True only while this client's own avatar is the acting unit. The phase alone is not
     * enough: another player's turn also runs during PLAYER_TURN and must stay read-only.
     */
    public static boolean isPlayerTurn() {
        return active && phase == BattlePhase.PLAYER_TURN.ordinal()
                && actorEntityId >= 0 && actorEntityId == selfEntityId;
    }

    /** Session/turn token for outgoing intents; null when no authoritative snapshot exists. */
    public static com.tactical.neoforge.battle.multi.ActionStamp stamp() {
        if (sessionId == null) return null;
        return new com.tactical.neoforge.battle.multi.ActionStamp(sessionId, turnEpoch);
    }

    public static GridPos playerCell() {
        return new GridPos(playerX, playerY, playerZ);
    }
}
