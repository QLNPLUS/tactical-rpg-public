package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.network.JoinBattleResultPacket;
import com.tactical.neoforge.network.NearbyBattlesPacket;
import com.tactical.neoforge.network.ServerPackets;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nearby-encounter discovery and direct opt-in joining (server authority).
 *
 * <p>Nothing here trusts a payload: the player comes from the connection, and distance, dimension,
 * eligibility, capacity and the safe action boundary are re-derived from live state for every
 * request. Only summaries of encounters in the caller's own dimension and within
 * {@link #NEARBY_DISTANCE} are sent; an empty answer is the explicit "nothing nearby" state.</p>
 *
 * <p>All calls run on the server thread.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleDiscovery {

    /** How close another encounter has to be to appear in a player's list. */
    public static final double NEARBY_DISTANCE = 64.0D;
    /** One served summary per player per this many server ticks. */
    public static final int REFRESH_INTERVAL_TICKS = 20;

    private static final Map<UUID, Long> LAST_SERVED = new HashMap<>();

    private BattleDiscovery() { }

    /**
     * Answer one discovery request.
     *
     * @return true when a summary was actually sent. A request inside the rate limit is dropped and
     *         the client keeps the list it already has; it is never sent a fabricated empty list.
     */
    public static boolean request(ServerPlayer player, long requestId) {
        if (player == null || player.connection == null) return false;
        long now = player.level().getGameTime();
        Long previous = LAST_SERVED.get(player.getUUID());
        if (previous != null && now - previous < REFRESH_INTERVAL_TICKS) return false;
        LAST_SERVED.put(player.getUUID(), now);
        // Bounded bookkeeping: only recently active requesters need their interval remembered.
        if (LAST_SERVED.size() > 64) {
            LAST_SERVED.entrySet().removeIf(entry -> now - entry.getValue() > 200L);
        }
        ServerPackets.sendUncheckedType(player, new NearbyBattlesPacket(requestId, entriesFor(player)));
        return true;
    }

    /** Summaries for this player, nearest first, each with an explicit join/refuse reason. */
    public static List<NearbyBattlesPacket.Entry> entriesFor(ServerPlayer player) {
        if (player == null) return List.of();
        List<NearbyBattlesPacket.Entry> entries = new ArrayList<>();
        ServerBattle own = BattleManager.findFor(player.getUUID());
        for (ServerBattle battle : BattleManager.active()) {
            if (battle.finished() || battle.level() != player.level()) continue;
            double distanceSq = distanceSquared(battle, player);
            if (distanceSq > NEARBY_DISTANCE * NEARBY_DISTANCE) continue;
            BattleEligibility.Denial denial;
            if (own == battle) {
                denial = BattleEligibility.Denial.ALREADY_MEMBER;
            } else if (own != null) {
                // One encounter per player: another encounter can never accept this one.
                denial = BattleEligibility.Denial.ALREADY_MEMBER;
            } else {
                denial = battle.directJoinDenial(player);
            }
            entries.add(new NearbyBattlesPacket.Entry(battle.sessionId(), battle.managerName(),
                    (int) Math.round(Math.sqrt(distanceSq)), battle.memberCount(), battle.playerCapacity(),
                    denial == BattleEligibility.Denial.NONE, describeForList(denial)));
        }
        entries.sort(Comparator.comparingInt(NearbyBattlesPacket.Entry::distance)
                .thenComparing(entry -> entry.sessionId().toString()));
        return entries.size() <= NearbyBattlesPacket.MAX_ENTRIES
                ? List.copyOf(entries)
                : List.copyOf(entries.subList(0, NearbyBattlesPacket.MAX_ENTRIES));
    }

    /**
     * Direct opt-in join from the list.
     *
     * <p>The answer always names the session the player asked for; a refusal is returned as-is and
     * the client refreshes, so a stale row, a full roster or a lost race can never silently join a
     * different encounter.</p>
     */
    public static JoinBattleResultPacket join(ServerPlayer player, UUID sessionId) {
        JoinBattleResultPacket result = decide(player, sessionId);
        // Exactly one answer reaches the sender on every path, including the early refusals: without it
        // the requesting page would stay in "joining" forever.
        if (player != null) ServerPackets.sendUncheckedType(player, result);
        // A successful join already published its membership transition, its own full snapshot, the
        // journal history and the warning snapshot from inside the commit. This answer is only the UI
        // reply, so it must not authorize or resync anything a second time.
        return result;
    }

    /** Pure decision: no packet is sent here, so every exit is answered by {@link #join}. */
    private static JoinBattleResultPacket decide(ServerPlayer player, UUID sessionId) {
        if (player == null || sessionId == null) return refusal(sessionId, BattleEligibility.Denial.UNRESOLVED);
        if (!player.isAlive() || player.isRemoved()) return refusal(sessionId, BattleEligibility.Denial.DEAD);
        if (BattleManager.findFor(player.getUUID()) != null) {
            return refusal(sessionId, BattleEligibility.Denial.ALREADY_MEMBER);
        }
        ServerBattle battle = BattleManager.findById(sessionId);
        if (battle == null || battle.finished()) {
            return refusal(sessionId, BattleEligibility.Denial.SESSION_CLOSED);
        }
        if (battle.level() != player.level()) return refusal(sessionId, BattleEligibility.Denial.DIFFERENT_DIMENSION);
        if (distanceSquared(battle, player) > NEARBY_DISTANCE * NEARBY_DISTANCE) {
            return refusal(sessionId, BattleEligibility.Denial.OUTSIDE_ZONE);
        }
        return refusal(sessionId, battle.joinDirect(player));
    }

    /**
     * The invitation wording does not fit a self-service join, so the one shared status that mentions
     * an invitation is rephrased here; every other reason is the single authoritative text.
     */
    private static String describeForList(BattleEligibility.Denial denial) {
        if (denial == BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY) return "战斗正在结算动作，请稍后再试";
        return BattleEligibility.describe(denial);
    }

    private static JoinBattleResultPacket refusal(UUID sessionId, BattleEligibility.Denial denial) {
        UUID target = sessionId == null ? new UUID(0L, 0L) : sessionId;
        return new JoinBattleResultPacket(target, denial, BattleEligibility.describe(denial));
    }

    private static double distanceSquared(ServerBattle battle, ServerPlayer player) {
        var center = battle.zone().center();
        double dx = player.getX() - center.x();
        double dy = player.getY() - center.y();
        double dz = player.getZ() - center.z();
        return dx * dx + dy * dy + dz * dz;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SERVED.clear();
        // A stopped server's epoch and counters describe nothing; the next instance publishes a new epoch.
        com.tactical.neoforge.battle.multi.MembershipGenerations.onServerStopped();
        com.tactical.neoforge.battle.multi.PendingBootstraps.clear();
    }
}
