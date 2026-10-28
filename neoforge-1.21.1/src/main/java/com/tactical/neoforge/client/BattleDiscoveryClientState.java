package com.tactical.neoforge.client;

import com.tactical.neoforge.network.JoinBattleIntentPacket;
import com.tactical.neoforge.network.JoinBattleResultPacket;
import com.tactical.neoforge.network.NearbyBattlesPacket;
import com.tactical.neoforge.network.NearbyBattlesRequestPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side mirror of the nearby-encounter list.
 *
 * <p>Deliberately separate from {@link BattleClientState}: this is what other people's encounters
 * look like from outside, so it never feeds the battle HUD, the camera or any intent stamp. It only
 * triggers {@link JoinBattleIntentPacket}, and the server decides everything else.</p>
 */
public final class BattleDiscoveryClientState {

    /** Local rate limit; the server enforces its own, this only avoids pointless packets. */
    public static final int LOCAL_REFRESH_TICKS = 20;
    /**
     * How long one outstanding request may wait before it is retried.
     *
     * <p>Retrying never overwrites a request that is still unanswered: when the round trip is longer
     * than the refresh interval, replacing the id would make every arriving answer stale and the list
     * would starve. Only after this bound is a new id issued, because the server may have silently
     * dropped the request inside its own rate limit.</p>
     */
    public static final int RESPONSE_TIMEOUT_TICKS = 40;
    /** Upper bound of the adaptive window; the retry never waits longer than this. */
    public static final int MAX_RESPONSE_TIMEOUT_TICKS = 320;

    private static final Set<UUID> DISMISSED = new LinkedHashSet<>();
    private static List<NearbyBattlesPacket.Entry> entries = List.of();
    private static long requestCounter;
    private static long lastRequestTick = Long.MIN_VALUE;
    private static boolean awaiting;
    private static String lastMessage = "";
    private static long revision;
    private static UUID joining;
    /** Only the newest request may update the list; an older answer is stale. */
    private static long latestRequestId;
    /** A refresh the local rate limit dropped; owed and retried once the interval has passed. */
    private static boolean refreshPending;
    /** When the outstanding request was sent, so the timeout can be measured. */
    private static long awaitingSince;
    /** Current window: grows while answers keep missing, so a slow link is not starved. */
    private static int responseWindow = RESPONSE_TIMEOUT_TICKS;
    /** Newest request whose answer was already applied; anything older must not overwrite it. */
    private static long appliedRequestId;
    private static Transport transport = new MinecraftTransport();

    /** Where requests go and where the clock comes from; replaceable so these rules are testable. */
    public interface Transport {
        boolean ready();
        long now();
        void requestSummary(long requestId);
        void joinSession(UUID sessionId);
    }

    /** Minecraft-backed transport; the only part of this class that needs a live client. */
    private static final class MinecraftTransport implements Transport {
        @Override public boolean ready() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.level != null && minecraft.player != null
                    && minecraft.getConnection() != null && minecraft.player.isAlive();
        }

        @Override public long now() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.level == null ? 0L : minecraft.level.getGameTime();
        }

        @Override public void requestSummary(long requestId) {
            PacketDistributor.sendToServer(new NearbyBattlesRequestPacket(requestId));
        }

        @Override public void joinSession(UUID sessionId) {
            PacketDistributor.sendToServer(new JoinBattleIntentPacket(sessionId));
        }
    }

    /** Fixture-only: drive these rules with a stub clock and transport. */
    public static void transportForTesting(Transport replacement) {
        transport = replacement == null ? new MinecraftTransport() : replacement;
    }

    private BattleDiscoveryClientState() { }

    public static List<NearbyBattlesPacket.Entry> entries() { return entries; }

    /** Rows the player has not dismissed with "暂不加入". */
    public static List<NearbyBattlesPacket.Entry> visibleEntries() {
        if (DISMISSED.isEmpty()) return entries;
        List<NearbyBattlesPacket.Entry> visible = new ArrayList<>(entries.size());
        for (NearbyBattlesPacket.Entry entry : entries) {
            if (!DISMISSED.contains(entry.sessionId())) visible.add(entry);
        }
        return List.copyOf(visible);
    }

    public static long revision() { return revision; }
    public static String lastMessage() { return lastMessage; }
    public static boolean awaiting() { return awaiting; }
    public static UUID joining() { return joining; }

    /**
     * Ask for a fresh summary.
     *
     * @return false when nothing was sent. A locally rate-limited request is remembered as pending, so
     *         the page can show "will retry" instead of silence, and {@link #tick()} sends it as soon
     *         as the interval has passed.
     */
    public static boolean request() {
        if (!transport.ready()) return false;
        long now = transport.now();
        if (awaiting) {
            // An unanswered request keeps its id for the whole adaptive window, so a slow answer can
            // still be applied instead of being treated as stale.
            if (now - awaitingSince < responseWindow) {
                markPending();
                return false;
            }
            // The window passed: the server may have silently dropped it. Widen before retrying, so a
            // link slower than the first window is not starved, and stay bounded.
            responseWindow = Math.min(MAX_RESPONSE_TIMEOUT_TICKS, responseWindow * 2);
        } else if (lastRequestTick != Long.MIN_VALUE && now - lastRequestTick < LOCAL_REFRESH_TICKS) {
            markPending();
            return false;
        }
        lastRequestTick = now;
        awaitingSince = now;
        boolean justAwaited = !awaiting;
        awaiting = true;
        latestRequestId = ++requestCounter;
        if (refreshPending || justAwaited) {
            // The visible status changed (a request is now in flight), so the page repaints.
            refreshPending = false;
            revision++;
        }
        transport.requestSummary(latestRequestId);
        return true;
    }

    /** Record that a refresh is owed, and let the page repaint when that is new information. */
    private static void markPending() {
        if (!refreshPending) {
            refreshPending = true;
            revision++;
        }
    }

    /** Periodic refresh used by the non-focus-stealing hint while the player is not fighting. */
    public static void tick() {
        if (BattleClientState.active) return;
        if (!transport.ready()) return;
        request();
    }

    /** True while a rate-limited refresh is still owed; the page shows this instead of silence. */
    public static boolean refreshPending() {
        return refreshPending;
    }

    /** The newest request id this client sent; older answers are ignored. */
    public static long latestRequestId() {
        return latestRequestId;
    }

    public static void accept(NearbyBattlesPacket packet) {
        // The answer must belong to a request this client still considers in flight: the newest one, or
        // the single predecessor it replaced after a timeout. That predecessor is still a truthful
        // answer for a slow link, but an answer older than the one already applied never overwrites it.
        boolean newest = packet.requestId() == latestRequestId;
        boolean inFlightPredecessor = packet.requestId() == latestRequestId - 1
                && packet.requestId() > appliedRequestId;
        if (!newest && !inFlightPredecessor) return;
        if (packet.requestId() < appliedRequestId) return;
        appliedRequestId = packet.requestId();
        entries = List.copyOf(packet.entries());
        if (awaiting && newest) awaiting = false;
        // A real answer means the link is healthy again: reset the adaptive window.
        responseWindow = RESPONSE_TIMEOUT_TICKS;
        revision++;
        // "暂不加入" lasts for this page session only: reopening the page offers every row again,
        // while the periodic background refresh cannot silently undo the player's choice.
    }

    public static void accept(JoinBattleResultPacket packet) {
        // The result answers a request; it does not authorize anything. Which encounter this client
        // shows is decided only by the server's membership transition, which follows the commit.
        // Only the row this player confirmed may drive the page's own completion or refresh.
        if (joining == null || !joining.equals(packet.sessionId())) {
            if (packet.joined()) {
                entries = List.of();
                DISMISSED.clear();
                revision++;
                NearbyBattlesScreen.close();
            }
            return;
        }
        joining = null;
        lastMessage = packet.message();
        if (packet.joined()) {
            // The authoritative snapshot for the new encounter follows; the list is done.
            entries = List.of();
            DISMISSED.clear();
            revision++;
            NearbyBattlesScreen.close();
            return;
        }
        // Never guess another encounter: ask for a fresh list. The server refuses requests inside its
        // own interval, so a dropped refresh stays pending and is retried once the interval passes.
        lastRequestTick = Long.MIN_VALUE;
        request();
        revision++;
    }

    /** Offer every row again; called when the player reopens the page. */
    public static void undismissAll() {
        if (DISMISSED.isEmpty()) return;
        DISMISSED.clear();
        revision++;
    }

    /** "暂不加入" for one row; reopening the page offers it again. */
    public static void dismiss(UUID sessionId) {
        if (sessionId != null) {
            DISMISSED.add(sessionId);
            revision++;
        }
    }

    /**
     * The row at one visible index: the single mapping the rendered rows and the row clicks share.
     *
     * <p>Rendering and clicking must never compute the index separately. A dismissed row leaves
     * {@link #visibleEntries()}, so the rows below it shift up; a stale index would then click the
     * encounter that moved into that slot instead of the one the player sees.</p>
     */
    public static NearbyBattlesPacket.Entry rowAt(int index) {
        List<NearbyBattlesPacket.Entry> visible = visibleEntries();
        return index < 0 || index >= visible.size() ? null : visible.get(index);
    }

    /**
     * Join the encounter shown at {@code index}, or report that the row is gone.
     *
     * @return false when that slot no longer holds a row; the page then refreshes instead of silently
     *         doing nothing on a stale row.
     */
    public static boolean joinRow(int index) {
        NearbyBattlesPacket.Entry entry = rowAt(index);
        if (entry == null) {
            request();
            return false;
        }
        join(entry.sessionId());
        return true;
    }

    /**
     * Dismiss the encounter shown at {@code index}.
     *
     * @return false when that slot no longer holds a row.
     */
    public static boolean dismissRow(int index) {
        NearbyBattlesPacket.Entry entry = rowAt(index);
        if (entry == null) {
            request();
            return false;
        }
        dismiss(entry.sessionId());
        return true;
    }

    public static void join(UUID sessionId) {
        if (sessionId == null) return;
        joining = sessionId;
        // Clicking a row expresses intent only; the server's transition is what admits this client.
        // The pending row is visible information, so the page repaints.
        revision++;
        transport.joinSession(sessionId);
    }

    /**
     * Which entry the B key takes: the original battle menu while a battle is shown, the nearby
     * list otherwise. Kept as a predicate so the routing rule itself is covered by a unit test.
     */
    public static boolean bOpensBattleMenu() {
        return BattleClientState.active;
    }

    /** True when the hint should be visible: something nearby, and this client is not fighting. */
    public static boolean hintVisible() {
        if (BattleClientState.active) return false;
        for (NearbyBattlesPacket.Entry entry : entries) {
            if (entry.joinable()) return true;
        }
        return !entries.isEmpty();
    }

    /**
     * Drop the nearby rows once this client is inside an encounter.
     *
     * <p>The list is only meaningful while looking for one; keeping it would let a stale row offer a
     * join while the client is already fighting, and the joined row's dismissal must not outlive it.</p>
     */
    public static void clearRows() {
        if (entries.isEmpty() && DISMISSED.isEmpty() && joining == null) return;
        entries = List.of();
        DISMISSED.clear();
        joining = null;
        revision++;
    }

    public static void clear() {
        entries = List.of();
        DISMISSED.clear();
        lastMessage = "";
        awaiting = false;
        joining = null;
        refreshPending = false;
        latestRequestId = 0L;
        awaitingSince = 0L;
        lastRequestTick = Long.MIN_VALUE;
        responseWindow = RESPONSE_TIMEOUT_TICKS;
        revision++;
    }
}
