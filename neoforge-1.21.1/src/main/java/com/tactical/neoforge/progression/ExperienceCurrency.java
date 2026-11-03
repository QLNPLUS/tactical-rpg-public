package com.tactical.neoforge.progression;

import com.tactical.core.progression.ExperienceCurve;
import com.tactical.neoforge.Tactical;
import net.minecraft.server.level.ServerPlayer;

/**
 * Vanilla experience as a whole-point wallet, derived from the level and the bar and written transactionally.
 *
 * <p>The wallet is <em>not</em> {@code totalExperience}: that field is a cumulative score which commands,
 * the enchanting table and third-party mods can leave inconsistent with the bar. It is
 * {@link ExperienceCurve#integerBalance(int, float)} - the level plus the whole XP points inside it - and the
 * same function decides what the page displays, whether a price is affordable and whether a spend landed.
 * One strategy in all three places is what keeps a display from saying "349" while a request priced at 350
 * is accepted, or the reverse.</p>
 *
 * <p>Spending deliberately avoids {@code giveExperiencePoints(-cost)}. That call runs the XP-change hook,
 * where any listener may cancel the change or alter the amount - which would silently turn a paid level-up
 * into a free one. Instead the exact target state is written through the server's own level setter plus the
 * two public fields, then read back with the same integer balance and verified exactly; a mismatch restores
 * the captured state and reports failure, so a refused spend never leaves the player short or ahead. The
 * setter also marks the XP packet dirty, so the client's bar is corrected on the next tick.</p>
 */
public final class ExperienceCurrency {

    /** A vanilla experience state this class can capture and restore exactly. */
    public record State(int level, int total, float progress) { }

    private ExperienceCurrency() { }

    /** The spendable balance right now, in whole XP points. */
    public static long available(ServerPlayer player) {
        if (player == null) return 0L;
        return ExperienceCurve.integerBalance(player.experienceLevel, player.experienceProgress);
    }

    /** Captures the exact fields a failed transaction has to restore. */
    public static State capture(ServerPlayer player) {
        if (player == null) throw new IllegalArgumentException("Missing player");
        return new State(player.experienceLevel, player.totalExperience, player.experienceProgress);
    }

    /** Restores a captured state exactly, including a bar position the vanilla setters would clamp. */
    public static void restore(ServerPlayer player, State state) {
        if (player == null || state == null) return;
        // The setter alone leaves the bar where the caller put it and marks the XP packet dirty again.
        player.setExperienceLevels(state.level());
        player.experienceProgress = state.progress();
        player.totalExperience = state.total();
    }

    /**
     * Spends exactly {@code cost} whole XP points.
     *
     * @return the captured previous state when the spend was committed and verified (a zero cost commits
     *         nothing but is legal), or {@code null} when nothing was changed because the balance does not
     *         cover the cost or the written state did not read back as the expected balance.
     */
    public static State spend(ServerPlayer player, long cost) {
        if (player == null || cost < 0L) return null;
        long available = available(player);
        if (cost == 0L) return capture(player);
        if (available < cost) return null;
        State before = capture(player);
        long remaining = available - cost;
        try {
            ExperienceCurve.Split split = ExperienceCurve.split((double) remaining);
            // Level first: the bar's span is a function of the level it belongs to.
            player.setExperienceLevels(split.level());
            player.experienceProgress = (float) split.progress();
            long total = (long) before.total() - cost;
            player.totalExperience = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, total));
            long after = available(player);
            if (after != remaining) {
                Tactical.LOGGER.warn("[Tactical] XP spend did not verify for {}: expected {} but read {}",
                        player.getUUID(), remaining, after);
                restore(player, before);
                return null;
            }
            return before;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] XP spend failed for {}; restoring the captured state", player.getUUID(), failure);
            restore(player, before);
            return null;
        }
    }
}
