package com.tactical.neoforge.progression;

import com.tactical.core.progression.CharacterProfile;
import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionRules;
import com.tactical.neoforge.Tactical;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * The character profile as saved player data, in its own namespaced record.
 *
 * <p>Three facts are deliberately stored next to the profile: a schema version, a monotonic revision and
 * the identity (fingerprint) of the rule set the profile was last priced under. Nothing is kept in a cache
 * or a weak map - every read goes to the player's persistent data - because a cached value is not a ledger
 * and would not survive a restart.</p>
 *
 * <p>{@link #peek(Player, ProgressionRules)} is a pure read for display paths. {@link #load(Player,
 * ProgressionRules)} is the authoritative read used before any decision, and it performs exactly two kinds
 * of repair: it initialises a <em>missing</em> record, and it advances the revision once when the rule set
 * that priced the profile has changed. An existing record that cannot be parsed, or whose schema is
 * unknown, is reported as invalid and left untouched: this code never resets it and never hands out a point
 * for it.</p>
 *
 * <p>Why the fingerprint repairs through the revision: a page that was opened under one rule set quoted a
 * price and a set of caps. If the configuration changes while that page is open, the next request must not
 * be charged the new, possibly higher price. Advancing the revision once makes the stored revision differ
 * from the one the page was priced under, so that request is refused as stale and the client refreshes with
 * the real numbers, having spent nothing and granted nothing.</p>
 */
public final class ProgressionStore {

    /** Namespaced key of this mod's character record inside the player's persistent data. */
    public static final String DATA_KEY = "tactical:progression";
    /** Storage schema of that record. An unknown value refuses modification instead of guessing. */
    public static final int SCHEMA = 1;

    private static final String TAG_SCHEMA = "schema";
    private static final String TAG_RULES = "rules";
    private static final String TAG_REVISION = "revision";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_GRANTED = "granted";
    private static final String TAG_UNSPENT = "unspent";
    private static final String TAG_INVESTED = "invested";
    private static final String TAG_OWNER = "owner";

    /** Outcome of reading one player's profile. */
    public enum Status {
        /** No record was stored. {@link #load} creates one; {@link #peek} reports nothing usable. */
        MISSING,
        /** A usable profile. */
        VALID,
        /** A stored record this code refuses to interpret; it is never reset and never modified. */
        INVALID
    }

    public record Loaded(Status status, CharacterProfile profile, boolean rulesChanged, String detail) {
        public Loaded {
            if (status == null) throw new IllegalArgumentException("Missing load status");
        }

        public boolean usable() { return status == Status.VALID && profile != null; }
    }

    private record Stored(Status status, CharacterProfile profile, String fingerprint, String detail) { }

    private ProgressionStore() { }

    /**
     * Read-only view: no record is created, no revision is advanced and nothing is written.
     *
     * <p>{@code rulesChanged} is still reported, so a display path can tell that the profile is priced
     * under older rules. Used where a write would be surprising, such as inside skill formula evaluation.</p>
     */
    public static Loaded peek(Player player, ProgressionRules rules) {
        ProgressionRules safeRules = rules == null ? ProgressionRules.DEFAULTS : rules;
        Stored stored = read(player);
        if (stored.status() != Status.VALID)
            return new Loaded(stored.status(), stored.profile(), false, stored.detail());
        boolean changed = !safeRules.fingerprint().equals(stored.fingerprint());
        return new Loaded(Status.VALID, stored.profile(), changed, changed ? "rules changed" : null);
    }

    /**
     * Authoritative read: initialises a missing record and advances the revision exactly once when the rule
     * set that priced the profile has changed. Never throws for stored data.
     */
    public static Loaded load(Player player, ProgressionRules rules) {
        if (player == null || player.level().isClientSide)
            return new Loaded(Status.INVALID, null, false, "not a server player");
        ProgressionRules safeRules = rules == null ? ProgressionRules.DEFAULTS : rules;
        Stored stored = read(player);
        if (stored.status() == Status.MISSING) {
            CharacterProfile created = CharacterProfile.fresh().withRevision(1L);
            save(player, created, safeRules);
            return new Loaded(Status.VALID, created, false, "initialized");
        }
        if (stored.status() == Status.INVALID) return new Loaded(Status.INVALID, null, false, stored.detail());
        if (safeRules.fingerprint().equals(stored.fingerprint()))
            return new Loaded(Status.VALID, stored.profile(), false, null);
        CharacterProfile repaired = stored.profile().withRevision(stored.profile().revision() + 1L);
        save(player, repaired, safeRules);
        Tactical.LOGGER.info("[Tactical] progression rules changed for {}; revision {} -> {} so an old quote cannot be charged",
                player.getUUID(), stored.profile().revision(), repaired.revision());
        return new Loaded(Status.VALID, repaired, true, "rules changed");
    }

    private static Stored read(Player player) {
        if (player == null || player.level().isClientSide)
            return new Stored(Status.INVALID, null, "", "not a server player");
        CompoundTag root = player.getPersistentData();
        if (!root.contains(DATA_KEY, Tag.TAG_COMPOUND)) return new Stored(Status.MISSING, null, "", null);
        CompoundTag record = root.getCompound(DATA_KEY);
        int schema = record.getInt(TAG_SCHEMA);
        if (schema != SCHEMA) return new Stored(Status.INVALID, null, "", "unknown schema " + schema);
        String storedOwner = record.getString(TAG_OWNER);
        UUID owner = player.getUUID();
        if (!storedOwner.isEmpty() && !storedOwner.equals(owner.toString()))
            return new Stored(Status.INVALID, null, "", "profile belongs to " + storedOwner);
        CharacterProfile parsed;
        try {
            parsed = new CharacterProfile(record.getInt(TAG_LEVEL), record.getInt(TAG_GRANTED),
                    record.getInt(TAG_UNSPENT), investedOf(record), record.getLong(TAG_REVISION));
        } catch (RuntimeException broken) {
            return new Stored(Status.INVALID, null, "", "unreadable profile: " + broken.getMessage());
        }
        return new Stored(Status.VALID, parsed, record.getString(TAG_RULES), null);
    }

    private static int[] investedOf(CompoundTag record) {
        if (!record.contains(TAG_INVESTED, Tag.TAG_INT_ARRAY)) return new int[0];
        return record.getIntArray(TAG_INVESTED);
    }

    /** Writes one profile, always with the schema, the owner identity and the pricing rule fingerprint. */
    public static void save(Player player, CharacterProfile profile, ProgressionRules rules) {
        if (player == null || profile == null) throw new IllegalArgumentException("Missing profile to save");
        ProgressionRules safeRules = rules == null ? ProgressionRules.DEFAULTS : rules;
        CompoundTag record = new CompoundTag();
        record.putInt(TAG_SCHEMA, SCHEMA);
        record.putString(TAG_OWNER, player.getUUID().toString());
        record.putString(TAG_RULES, safeRules.fingerprint());
        record.putLong(TAG_REVISION, profile.revision());
        record.putInt(TAG_LEVEL, profile.level());
        record.putInt(TAG_GRANTED, profile.granted());
        record.putInt(TAG_UNSPENT, profile.unspent());
        record.putIntArray(TAG_INVESTED, profile.invested());
        player.getPersistentData().put(DATA_KEY, record);
    }

    /** Drops this mod's record; used by fixtures that need a genuinely new character. */
    public static void remove(Player player) {
        if (player == null) return;
        player.getPersistentData().remove(DATA_KEY);
    }

    /** Copies the whole record to another player instance, as a death clone requires. */
    public static void copy(Player from, Player to) {
        if (from == null || to == null) return;
        CompoundTag source = from.getPersistentData();
        if (!source.contains(DATA_KEY, Tag.TAG_COMPOUND)) return;
        to.getPersistentData().put(DATA_KEY, source.getCompound(DATA_KEY).copy());
    }

    /** Whether a stored record exists at all; used by fixtures and diagnostics. */
    public static boolean present(Player player) {
        return player != null && player.getPersistentData().contains(DATA_KEY, Tag.TAG_COMPOUND);
    }

    /** The stored invested array without validating the rest of the record; diagnostics only. */
    public static int[] rawInvested(Player player) {
        if (player == null) return new int[CharacterStat.COUNT];
        CompoundTag root = player.getPersistentData();
        if (!root.contains(DATA_KEY, Tag.TAG_COMPOUND)) return new int[CharacterStat.COUNT];
        int[] stored = root.getCompound(DATA_KEY).getIntArray(TAG_INVESTED);
        return stored.length == CharacterStat.COUNT ? stored.clone() : new int[CharacterStat.COUNT];
    }
}
