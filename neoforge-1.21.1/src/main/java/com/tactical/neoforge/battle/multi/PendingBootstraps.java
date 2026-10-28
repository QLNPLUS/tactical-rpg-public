package com.tactical.neoforge.battle.multi;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleManager;
import com.tactical.neoforge.battle.ServerBattle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Members whose entry bootstrap (journal history, warning snapshot, skills) could not be published.
 *
 * <p>The membership and the roster are already committed and stay committed; this registry only owes
 * the <em>rest</em> of the entry publication to that one client. Each debt is bound to the member and
 * the exact membership generation it was registered for, so it can never repair a client that has
 * since left, entered another encounter, or been admitted again at a new generation.</p>
 *
 * <p>Retries are driven by the server tick with a bounded exponential backoff, and are dropped when
 * they are no longer meaningful. Giving up is explicit and logged: an unresolved debt is never
 * reported as self-healed.</p>
 */
public final class PendingBootstraps {

    /** First retry delay in ticks; doubled per attempt. */
    public static final int FIRST_DELAY_TICKS = 5;
    /** Attempts after the two inline ones inside the entry transaction. */
    public static final int MAX_ATTEMPTS = 6;

    /** One owed bootstrap: who, for which encounter, at which membership generation. */
    public record Debt(UUID playerId, UUID sessionId, long generation, int attempts, long nextTick) {
    }

    private static final Map<UUID, Debt> DEBTS = new LinkedHashMap<>();
    private static final List<Long> ATTEMPT_TICKS = new ArrayList<>();
    private static long lastAttempts;
    private static long lastTick;

    private PendingBootstraps() { }

    /**
     * Register (or refresh) the debt of one member whose bootstrap publication failed.
     *
     * @param now the server tick this happened on; the first retry waits {@link #FIRST_DELAY_TICKS}
     *            so a failure is not hammered on the very next tick
     */
    public static void schedule(ServerPlayer player, UUID sessionId, long generation, long now) {
        if (player == null || sessionId == null || generation <= 0) return;
        DEBTS.put(player.getUUID(),
                new Debt(player.getUUID(), sessionId, generation, 0, now + FIRST_DELAY_TICKS));
        Tactical.LOGGER.warn("[Tactical] bootstrap of {} for {} at generation {} is owed and will be retried",
                player.getUUID(), sessionId, generation);
    }

    /** True while this member's bootstrap is still owed. */
    public static boolean owed(UUID playerId) {
        return playerId != null && DEBTS.containsKey(playerId);
    }

    public static Debt debt(UUID playerId) {
        return playerId == null ? null : DEBTS.get(playerId);
    }

    public static int debtCount() {
        return DEBTS.size();
    }

    /** Fixture readback: retries actually attempted since the last {@link #clear()}. */
    public static long attemptsMade() {
        return lastAttempts;
    }

    /** Fixture readback: server tick of the last attempt. */
    public static long lastAttemptTick() {
        return lastTick;
    }

    /** Fixture readback: the tick of every retry, so the backoff spacing can be proven. */
    public static List<Long> attemptTicks() {
        return List.copyOf(ATTEMPT_TICKS);
    }

    /** The membership ended, or the player is gone: the debt can never be delivered. */
    public static void forget(UUID playerId) {
        if (playerId != null) DEBTS.remove(playerId);
    }

    /** Every published membership ended (battle reset, server stop). */
    public static void clear() {
        DEBTS.clear();
        ATTEMPT_TICKS.clear();
        lastAttempts = 0L;
        lastTick = 0L;
    }

    private static void drop(Debt debt, String reason) {
        DEBTS.remove(debt.playerId());
        Tactical.LOGGER.error("[Tactical] bootstrap retry for {} in {} was abandoned after {} attempts ({})",
                debt.playerId(), debt.sessionId(), debt.attempts(), reason);
    }

    /**
     * One server tick: retry at most the debts whose backoff has elapsed.
     *
     * <p>Nothing here touches the membership: a failed retry keeps the member admitted and only
     * schedules the next attempt, and a debt that no longer matches the live membership is dropped
     * instead of being delivered to the wrong encounter.</p>
     */
    public static void tick(MinecraftServer server, long now) {
        if (DEBTS.isEmpty() || server == null) return;
        for (Debt debt : new ArrayList<>(DEBTS.values())) {
            if (now < debt.nextTick()) continue;
            ServerBattle battle = BattleManager.findFor(debt.playerId());
            MembershipGenerations.Membership membership = MembershipGenerations.current(debt.playerId());
            if (battle == null || battle.finished()
                    || !membership.active()
                    || !debt.sessionId().equals(membership.sessionId())
                    || membership.generation() != debt.generation()) {
                // The member left, changed encounter or was admitted again: this debt is void.
                DEBTS.remove(debt.playerId());
                continue;
            }
            // Resolve through the encounter, not the player list: a headless fixture has no entry there,
            // and the member entity is the authoritative one for this encounter anyway.
            ServerPlayer player = battle.memberPlayer(debt.playerId());
            if (player == null) player = server.getPlayerList().getPlayer(debt.playerId());
            if (player == null || !battle.isMember(player)) {
                DEBTS.remove(debt.playerId());
                continue;
            }
            int attempts = debt.attempts() + 1;
            lastAttempts = attempts;
            lastTick = now;
            ATTEMPT_TICKS.add(now);
            try {
                battle.retryBootstrap(player);
            } catch (RuntimeException failure) {
                if (attempts >= MAX_ATTEMPTS) {
                    drop(new Debt(debt.playerId(), debt.sessionId(), debt.generation(), attempts, now),
                            "still failing: " + failure);
                } else {
                    DEBTS.put(debt.playerId(), new Debt(debt.playerId(), debt.sessionId(),
                            debt.generation(), attempts, now + delayTicks(attempts)));
                    Tactical.LOGGER.warn("[Tactical] bootstrap retry {} for {} failed; next attempt in {} ticks",
                            attempts, debt.playerId(), delayTicks(attempts));
                }
                continue;
            }
            DEBTS.remove(debt.playerId());
            Tactical.LOGGER.info("[Tactical] bootstrap of {} for {} completed on retry {}",
                    debt.playerId(), debt.sessionId(), attempts);
        }
    }

    /** Bounded exponential backoff of one retry. */
    static int delayTicks(int attempts) {
        int shift = Math.min(6, Math.max(0, attempts));
        long delay = (long) FIRST_DELAY_TICKS << shift;
        return (int) Math.min(200L, delay);
    }

    /** Fixture-only readback of everything currently owed. */
    public static List<Debt> snapshot() {
        return List.copyOf(DEBTS.values());
    }
}
