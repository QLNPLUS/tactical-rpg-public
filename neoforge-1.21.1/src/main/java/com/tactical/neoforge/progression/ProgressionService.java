package com.tactical.neoforge.progression;

import com.tactical.core.progression.CharacterProfile;
import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.core.progression.ProgressionRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleManager;
import com.tactical.neoforge.network.CharacterStatePacket;
import com.tactical.neoforge.skill.ManaResources;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * The four server-side entry points of character growth.
 *
 * <p>Every method answers the connection's own player with an immutable
 * {@link CharacterStatePacket}; none of them ever returns null, so a refused intent still refreshes the
 * page instead of leaving a client waiting. Nothing that arrives from a client is trusted: the price, the
 * level, the point balance, the caps and the derived values are all re-read here, on the server thread, from
 * the stored profile and the real player. A refusal carries a structured {@link ProgressionReason} whose
 * text the receiver reads in its own language, plus the current authoritative state.</p>
 *
 * <p>Ordering and double-spending are handled by one rule: every mutation must echo the revision the client
 * was shown. The revision advances once per committed mutation and once when the server's rule set changes,
 * so a duplicate, a replayed or a stale confirmation is refused and cannot spend XP or grant points twice.</p>
 *
 * <p>A mutation is a single transaction: the next profile, the stored record and - for an allocation - this
 * mod's own attribute modifiers are committed together, with the XP state captured beforehand. If any step
 * throws, the stored profile, the spent XP, this mod's modifiers and the health/MP they could have moved are
 * all restored, and the answer is a refusal, so a failed commit consumes nothing and leaves no half-applied
 * bonus. No foreign modifier is ever read or written by that rollback.</p>
 */
public final class ProgressionService {

    private static final UUID NO_OWNER = new UUID(0L, 0L);

    /**
     * How one commit writes the record.
     *
     * <p>It exists so the fixture can make a write fail on purpose and prove the rollback path, without a
     * public fault-injection API and without a generic transaction framework. Production always writes
     * through {@link ProgressionStore#save}; the rollback path never uses this field, so restoring a failed
     * commit never depends on the mechanism that just failed.</p>
     */
    @FunctionalInterface interface CommitStep {
        void save(ServerPlayer player, CharacterProfile profile, ProgressionRules rules);
    }

    static CommitStep saver = ProgressionStore::save;

    private ProgressionService() { }

    /** The authoritative page state; also the answer to a lifecycle push. */
    public static CharacterStatePacket snapshot(ServerPlayer player, int requestId, boolean openScreen) {
        if (player == null) return notAPlayer(requestId, openScreen);
        ProgressionRules rules = ProgressionSettings.rules();
        try {
            ProgressionStore.Loaded loaded = ProgressionStore.load(player, rules);
            if (!loaded.usable()) return refusal(player, requestId, openScreen, rules, null,
                    ProgressionReason.PROFILE_INVALID, null, false);
            ProgressionReason refusal = mutationRefusal(player);
            return answer(player, requestId, openScreen, rules, loaded.profile(), refusal, null, false);
        } catch (RuntimeException unexpected) {
            return failed(player, requestId, openScreen, rules, "snapshot", unexpected);
        }
    }

    /** What one draft would produce, without committing or reserving anything. */
    public static CharacterStatePacket preview(ServerPlayer player, int requestId, long expectedRevision, int[] increments) {
        if (player == null) return notAPlayer(requestId, false);
        ProgressionRules rules = ProgressionSettings.rules();
        try {
            ProgressionStore.Loaded loaded = ProgressionStore.load(player, rules);
            if (!loaded.usable()) return refusal(player, requestId, false, rules, null,
                    ProgressionReason.PROFILE_INVALID, null, false);
            CharacterProfile profile = loaded.profile();
            ProgressionReason refusal = mutationRefusal(player);
            if (expectedRevision != profile.revision())
                return answer(player, requestId, false, rules, profile, staleReason(loaded), null, false);
            Optional<ProgressionReason> failure = draftFailure(increments, profile, rules);
            if (failure.isPresent())
                return answer(player, requestId, false, rules, profile, failure.get(), null, false);
            boolean empty = isAllZero(increments);
            // A preview is a pure computation, so it stays available while the page is read-only; the reason
            // then says why nothing can be confirmed, and the draft numbers are still shown.
            return answer(player, requestId, false, rules, profile, refusal, empty ? null : increments, !empty);
        } catch (RuntimeException unexpected) {
            return failed(player, requestId, false, rules, "preview", unexpected);
        }
    }

    /** One committed level-up: the configured whole XP points are spent, then the points are granted. */
    public static CharacterStatePacket upgrade(ServerPlayer player, int requestId, long expectedRevision) {
        if (player == null) return notAPlayer(requestId, false);
        ProgressionRules rules = ProgressionSettings.rules();
        try {
            ProgressionStore.Loaded loaded = ProgressionStore.load(player, rules);
            if (!loaded.usable()) return refusal(player, requestId, false, rules, null,
                    ProgressionReason.PROFILE_INVALID, null, false);
            CharacterProfile profile = loaded.profile();
            if (expectedRevision != profile.revision())
                return answer(player, requestId, false, rules, profile, staleReason(loaded), null, false);
            ProgressionReason refused = mutationRefusal(player);
            if (refused != ProgressionReason.OK)
                return answer(player, requestId, false, rules, profile, refused, null, false);
            Optional<ProgressionReason> failure = profile.levelUpFailure(rules);
            if (failure.isPresent())
                return answer(player, requestId, false, rules, profile, failure.get(), null, false);
            long cost = rules.xpToNextLevel(profile.level());
            if (ExperienceCurrency.available(player) < cost)
                return answer(player, requestId, false, rules, profile, ProgressionReason.INSUFFICIENT_XP, null, false);
            // Everything that can refuse was checked before a single point of XP moves.
            CharacterProfile next = profile.levelUp(rules);
            ExperienceCurrency.State spent = ExperienceCurrency.spend(player, cost);
            if (spent == null)
                return answer(player, requestId, false, rules, profile, ProgressionReason.INSUFFICIENT_XP, null, false);
            // A level-up changes no invested point, so no attribute has to be touched; the commit only has to
            // store the record and give the XP back if that store fails.
            if (commit(player, rules, profile, next, spent, false) != Commit.COMMITTED)
                return answer(player, requestId, false, rules, profile, ProgressionReason.SERVER_ERROR, null, false);
            // The transaction is durable from here on. If the fresh numbers cannot be read back, the answer
            // says so while still carrying the revision it really reached - never a failure that invites a retry.
            return answer(player, requestId, false, rules, next, mutationRefusal(player), null, false);
        } catch (RuntimeException unexpected) {
            return failed(player, requestId, false, rules, "upgrade", unexpected);
        }
    }

    /** One committed allocation of the six increments; the derived attributes follow in the same step. */
    public static CharacterStatePacket allocate(ServerPlayer player, int requestId, long expectedRevision, int[] increments) {
        if (player == null) return notAPlayer(requestId, false);
        ProgressionRules rules = ProgressionSettings.rules();
        try {
            ProgressionStore.Loaded loaded = ProgressionStore.load(player, rules);
            if (!loaded.usable()) return refusal(player, requestId, false, rules, null,
                    ProgressionReason.PROFILE_INVALID, null, false);
            CharacterProfile profile = loaded.profile();
            if (expectedRevision != profile.revision())
                return answer(player, requestId, false, rules, profile, staleReason(loaded), null, false);
            ProgressionReason refused = mutationRefusal(player);
            if (refused != ProgressionReason.OK)
                return answer(player, requestId, false, rules, profile, refused, null, false);
            Optional<ProgressionReason> failure = profile.allocationFailure(increments, rules);
            if (failure.isPresent())
                return answer(player, requestId, false, rules, profile, failure.get(), null, false);
            CharacterProfile next = profile.allocate(increments, rules);
            if (commit(player, rules, profile, next, null, true) != Commit.COMMITTED)
                return answer(player, requestId, false, rules, profile, ProgressionReason.SERVER_ERROR, null, false);
            // The transaction is durable from here on. A battle this player is in republishes the caps and the
            // charge rate it can already see; that republish can never turn the commit into a failure, and it
            // starts no turn and grants no resource.
            refreshBattleGrowth(player);
            return answer(player, requestId, false, rules, next, mutationRefusal(player), null, false);
        } catch (RuntimeException unexpected) {
            return failed(player, requestId, false, rules, "allocate", unexpected);
        }
    }

    /**
     * Whether this player may change the character at all, as the one reason that refuses it.
     *
     * <p>A spectator, and a dead or removed player, are refused: the last two could otherwise spend the
     * experience fields of a body whose real experience was already dropped on death. Being in a battle is no
     * longer a refusal in itself - the player has chosen that both a level-up and a confirmation may happen in
     * battle - but it is still not unconditional: the encounter is asked whether this player is the acting
     * member of its own turn with nothing in flight. A waiting member, an enemy turn and an action already
     * underway stay read-only. The page itself is readable in every one of those states, and a refusal never
     * tries to "repair" the player - nothing here resurrects anyone or changes a game mode.</p>
     */
    public static ProgressionReason mutationRefusal(ServerPlayer player) {
        if (player == null) return ProgressionReason.NOT_A_PLAYER;
        if (player.isSpectator()) return ProgressionReason.SPECTATOR;
        if (!player.isAlive() || player.isRemoved()) return ProgressionReason.DEAD;
        if (BattleManager.isParticipant(player)) {
            com.tactical.neoforge.battle.ServerBattle battle = BattleManager.containing(player);
            // An unresolvable membership is still a membership: refuse rather than spend blind.
            if (battle == null || !battle.allowsCharacterMutation(player)) return ProgressionReason.IN_BATTLE;
        }
        return ProgressionReason.OK;
    }

    /** True when {@link #mutationRefusal(ServerPlayer)} allows changing the profile. */
    public static boolean mutationsAllowed(ServerPlayer player) { return mutationRefusal(player) == ProgressionReason.OK; }

    /**
     * Stores one profile transition together with the attribute effects it causes, as a single unit.
     *
     * @return true when the record (and, when asked, the modifiers) are committed; false when everything was
     *         restored to the state captured before the call
     */
    /** Outcome of one commit attempt: done, undone after a partial write, or refused before any write. */
    private enum Commit { COMMITTED, ROLLED_BACK, REFUSED }

    private static Commit commit(ServerPlayer player, ProgressionRules rules, CharacterProfile before,
                                 CharacterProfile next, ExperienceCurrency.State spent, boolean applyEffects) {
        // The rollback state is read before anything is written. An allocation moves a maximum, so the MP
        // balance has to be known exactly to restore it; if it cannot be read the commit is refused instead of
        // guessing a balance and later writing that guess back. A level-up changes no maximum, so it never
        // needs the MP balance at all and does not read it.
        double mp = 0.0D;
        if (applyEffects) {
            try {
                mp = ManaResources.current(player);
            } catch (RuntimeException unreadable) {
                Tactical.LOGGER.error("[Tactical] MP balance unreadable for {}; the allocation was refused before "
                        + "anything was written", player.getUUID(), unreadable);
                return Commit.REFUSED;
            }
        }
        float health = player.getHealth();
        try {
            saver.save(player, next, rules);
            if (applyEffects) ProgressionEffects.apply(player, rules, next);
            return Commit.COMMITTED;
        } catch (RuntimeException failureToCommit) {
            Tactical.LOGGER.error("[Tactical] character commit for {} was rolled back", player.getUUID(), failureToCommit);
            rollback(player, rules, before, spent, applyEffects, health, mp);
            return Commit.ROLLED_BACK;
        }
    }

    /** Restores everything a failed commit could have changed; foreign modifiers are never addressed. */
    private static void rollback(ServerPlayer player, ProgressionRules rules, CharacterProfile before,
                                 ExperienceCurrency.State spent, boolean applyEffects, float health, double mp) {
        try {
            ProgressionStore.save(player, before, rules);
        } catch (RuntimeException alsoBroken) {
            Tactical.LOGGER.error("[Tactical] profile rollback failed for {}", player.getUUID(), alsoBroken);
        }
        if (spent != null) {
            try {
                ExperienceCurrency.restore(player, spent);
            } catch (RuntimeException alsoBroken) {
                Tactical.LOGGER.error("[Tactical] XP rollback failed for {}", player.getUUID(), alsoBroken);
            }
        }
        if (applyEffects) {
            try {
                ProgressionEffects.apply(player, rules, before);
            } catch (RuntimeException alsoBroken) {
                Tactical.LOGGER.error("[Tactical] modifier rollback failed for {}", player.getUUID(), alsoBroken);
            }
        }
        try {
            if (player.getHealth() != health) player.setHealth(Math.min(health, player.getMaxHealth()));
        } catch (RuntimeException alsoBroken) {
            Tactical.LOGGER.error("[Tactical] health rollback failed for {}", player.getUUID(), alsoBroken);
        }
        try {
            if (Math.abs(ManaResources.current(player) - mp) > 1.0E-9D) ManaResources.set(player, mp);
        } catch (RuntimeException alsoBroken) {
            Tactical.LOGGER.error("[Tactical] MP rollback failed for {}", player.getUUID(), alsoBroken);
        }
    }

    /**
     * Republishes the caps a committed allocation moved, for the encounter this player is fighting in.
     *
     * <p>Deliberately forgiving: the commit is already durable, so a battle that cannot refresh must not turn
     * a successful allocation into an answer that looks like a failure and invites a retry. Nothing is rolled
     * back and nothing is replayed; the failure is logged and the answer tells the truth about the profile.</p>
     */
    private static void refreshBattleGrowth(ServerPlayer player) {
        if (player == null) return;
        try {
            com.tactical.neoforge.battle.ServerBattle battle = BattleManager.containing(player);
            if (battle != null) battle.refreshGrowth(player);
        } catch (RuntimeException unreadable) {
            Tactical.LOGGER.error("[Tactical] the battle could not republish the growth of {}; the allocation "
                    + "itself is already stored", player.getUUID(), unreadable);
        }
    }

    private static Optional<ProgressionReason> draftFailure(int[] increments, CharacterProfile profile, ProgressionRules rules) {        if (increments == null || increments.length != CharacterStat.COUNT)
            return Optional.of(ProgressionReason.INVALID_INCREMENT);
        for (int value : increments) {
            if (value < 0) return Optional.of(ProgressionReason.INVALID_INCREMENT);
        }
        if (isAllZero(increments)) return Optional.empty();
        long total = 0;
        for (int value : increments) total += value;
        if (total > profile.unspent()) return Optional.of(ProgressionReason.INSUFFICIENT_POINTS);
        int maximum = rules.maximumInvested();
        int[] invested = profile.invested();
        for (int index = 0; index < CharacterStat.COUNT; index++) {
            if (increments[index] > 0 && (long) invested[index] + increments[index] > maximum)
                return Optional.of(ProgressionReason.STAT_CAP);
        }
        return Optional.empty();
    }

    private static boolean isAllZero(int[] values) {
        if (values == null) return true;
        for (int value : values) {
            if (value != 0) return false;
        }
        return true;
    }

    private static ProgressionReason staleReason(ProgressionStore.Loaded loaded) {
        return loaded != null && loaded.rulesChanged() ? ProgressionReason.RULES_CHANGED : ProgressionReason.STALE_REVISION;
    }

    private static CharacterStatePacket refusal(ServerPlayer player, int requestId, boolean openScreen,
                                                ProgressionRules rules, CharacterProfile profile,
                                                ProgressionReason reason, int[] draft, boolean draftValid) {
        return answer(player, requestId, openScreen, rules, profile, reason, draft, draftValid);
    }

    /**
     * Builds one answer from the current real player state; every field is the server's own number.
     *
     * <p>Building an answer can never make a committed transaction look failed: if reading the real values
     * throws, a complete but neutral answer is returned and the client simply refreshes. The commit itself is
     * already durable by then.</p>
     */
    private static CharacterStatePacket answer(ServerPlayer player, int requestId, boolean openScreen,
                                               ProgressionRules rules, CharacterProfile profile,
                                               ProgressionReason reason, int[] draft, boolean draftValid) {
        try {
            ProgressionReason safeReason = reason == null ? ProgressionReason.OK : reason;
            boolean valid = profile != null;
            long xp = ExperienceCurrency.available(player);
            int[] invested = valid ? profile.invested() : new int[CharacterStat.COUNT];
            int[] display = new int[CharacterStat.COUNT];
            for (CharacterStat stat : CharacterStat.values())
                display[stat.index()] = valid ? profile.display(stat, rules) : CharacterStat.BASE;
            double[] coefficients = coefficients(rules);
            double[] derived = ProgressionEffects.derived(player);
            boolean showDraft = draftValid && valid && draft != null;
            double[] draftDerived = showDraft ? ProgressionEffects.draftDerived(player, rules, profile, draft) : derived;
            // Mutability is a fact about the state, not about this one request: a refused price does not make
            // the page read-only, and a battle does.
            boolean canMutate = valid && mutationRefusal(player) == ProgressionReason.OK;
            return new CharacterStatePacket(player.getUUID(), requestId, openScreen, valid,
                    canMutate, true, safeReason.name(), ProgressionText.reason(safeReason),
                    valid ? profile.revision() : 0L, valid ? profile.level() : 0, rules.maxLevel(), rules.statCap(),
                    coefficients, rules.pointsPerLevel(), rules.pointsPerLevel(), valid ? profile.granted() : 0,
                    valid ? profile.unspent() : 0, Math.max(0L, xp), valid ? rules.xpToNextLevel(profile.level()) : 0L,
                    invested, display, derived, showDraft ? draft.clone() : new int[CharacterStat.COUNT], showDraft,
                    draftDerived);
        } catch (RuntimeException unbuildable) {
            // The transaction (if any) is already durable. What failed is reading the fresh numbers, so the
            // answer keeps the revision this side really reached and says the sync is unavailable - it is
            // never reported as a failure that could be retried.
            Tactical.LOGGER.error("[Tactical] character answer for {} could not be read back", player.getUUID(), unbuildable);
            return unavailableAnswer(player, requestId, openScreen, rules, profile);
        }
    }

    /**
     * The answer for a request whose live numbers cannot be read back.
     *
     * <p>It reads neither the experience nor a single attribute, so it cannot fail the way the ordinary answer
     * just did. It carries the revision and the stored points this side does know, {@code syncAvailable=false}
     * so a client renders no experience or derived value as a fact, and {@code canMutate=false} so nothing can
     * be changed on top of a page that is out of date. The client clears its pending wait from it and asks for
     * a fresh snapshot; the server neither rolls back nor replays anything.</p>
     */
    private static CharacterStatePacket unavailableAnswer(ServerPlayer player, int requestId, boolean openScreen,
                                                          ProgressionRules rules, CharacterProfile profile) {
        ProgressionReason reason = ProgressionReason.SYNC_UNAVAILABLE;
        boolean valid = profile != null;
        int[] invested = valid ? profile.invested() : new int[CharacterStat.COUNT];
        int[] display = new int[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values())
            display[stat.index()] = valid ? profile.display(stat, rules) : CharacterStat.BASE;
        return new CharacterStatePacket(player.getUUID(), requestId, openScreen, valid, false, false, reason.name(),
                ProgressionText.reason(reason), valid ? profile.revision() : 0L, valid ? profile.level() : 0,
                rules.maxLevel(), rules.statCap(), coefficients(rules), rules.pointsPerLevel(),
                rules.pointsPerLevel(), valid ? profile.granted() : 0, valid ? profile.unspent() : 0,
                0L, valid ? rules.xpToNextLevel(profile.level()) : 0L, invested, display,
                new double[CharacterStat.COUNT], new int[CharacterStat.COUNT], false, new double[CharacterStat.COUNT]);
    }

    /** The per-point coefficients of one rule set, in stat order. */
    private static double[] coefficients(ProgressionRules rules) {
        double[] coefficients = new double[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) coefficients[stat.index()] = rules.coefficient(stat);
        return coefficients;
    }

    private static CharacterStatePacket failed(ServerPlayer player, int requestId, boolean openScreen,
                                               ProgressionRules rules, String phase, RuntimeException unexpected) {
        Tactical.LOGGER.error("[Tactical] character {} failed unexpectedly during {}", player.getUUID(), phase, unexpected);
        try {
            ProgressionStore.Loaded loaded = ProgressionStore.load(player, rules);
            return answer(player, requestId, openScreen, rules, loaded.usable() ? loaded.profile() : null,
                    ProgressionReason.SERVER_ERROR, null, false);
        } catch (RuntimeException alsoBroken) {
            return empty(player.getUUID(), requestId, openScreen, rules, ProgressionReason.SERVER_ERROR);
        }
    }

    private static CharacterStatePacket notAPlayer(int requestId, boolean openScreen) {
        return empty(NO_OWNER, Math.max(0, requestId), openScreen, ProgressionSettings.rules(), ProgressionReason.NOT_A_PLAYER);
    }

    /** One full-shaped answer with no usable profile; every number is neutral but the shape is complete. */
    private static CharacterStatePacket empty(UUID owner, int requestId, boolean openScreen, ProgressionRules rules,
                                              ProgressionReason reason) {
        return new CharacterStatePacket(owner, requestId, openScreen, false, false, false, reason.name(),
                ProgressionText.reason(reason), 0L, 0, rules.maxLevel(), rules.statCap(), coefficients(rules),
                rules.pointsPerLevel(), rules.pointsPerLevel(), 0, 0, 0L, 0L,
                new int[CharacterStat.COUNT], new int[CharacterStat.COUNT], new double[CharacterStat.COUNT],
                new int[CharacterStat.COUNT], false, new double[CharacterStat.COUNT]);
    }
}
