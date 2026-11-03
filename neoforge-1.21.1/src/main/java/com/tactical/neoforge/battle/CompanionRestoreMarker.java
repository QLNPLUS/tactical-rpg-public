package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.UUID;

/**
 * The smallest durable record control needs: what to give back if the unit is saved mid-battle and the
 * encounter that held it never gets to release it.
 *
 * <p>Control takes the AI lock and persistence flag from a mob for the duration of a fight. A clean exit
 * gives both back, but a save or a chunk unload during the fight writes the locked values into the world
 * file, and the encounter's own state is not persisted - so without this marker a reloaded wolf would keep a
 * battle's AI lock forever. The marker carries exactly the two original flags (plus the session, for
 * diagnosis) in the mob's own persistent data, and is removed the moment control ends, however it ends.</p>
 *
 * <p>What it deliberately does not carry: position, health, equipment, owner, AP, queue membership or the
 * captured pose. A reloaded unit is not re-admitted by this path - it only stops the lock from surviving a
 * save. It is not a general persistence layer for battles.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class CompanionRestoreMarker {

    /** Versioned key: a future format change may read this one and refuse, instead of guessing. */
    public static final String KEY = "tactical_companion_restore";
    public static final int VERSION = 1;

    private CompanionRestoreMarker() {
    }

    /** Write the marker after the admission committed and before control changes the entity further. */
    public static void write(Mob mob, boolean originalNoAi, boolean originalPersistenceRequired,
                             UUID sessionId, UUID ownerId) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", VERSION);
        tag.putBoolean("noai", originalNoAi);
        tag.putBoolean("persistent", originalPersistenceRequired);
        if (sessionId != null) tag.putUUID("session", sessionId);
        if (ownerId != null) tag.putUUID("owner", ownerId);
        mob.getPersistentData().put(KEY, tag);
    }

    /** True when this unit carries a marker of a format this version understands. */
    public static boolean present(Mob mob, CompoundTag persistent) {
        if (persistent == null || !persistent.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return false;
        CompoundTag tag = persistent.getCompound(KEY);
        return tag.getInt("v") == VERSION;
    }

    /** Remove the marker; every release path and every restore calls this. */
    public static void clear(Mob mob) {
        mob.getPersistentData().remove(KEY);
    }

    /**
     * Give the two original flags back for a unit that was saved while under control and is now being loaded.
     *
     * <p>Only when no live encounter still controls this exact instance: a unit that is still held keeps its
     * marker, because control is still in effect and its own release will clear it. Nothing else is touched -
     * not the cell, not the health, not the equipment, not the owner, and never a queue entry or an AP grant.</p>
     */
    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        CompoundTag persistent = mob.getPersistentData();
        if (!present(mob, persistent)) return;
        ServerBattle holder = BattleManager.containing(mob);
        if (holder != null && holder.isCompanion(mob)) {
            // Still the live control of the same encounter: its own release owns the flags.
            return;
        }
        CompoundTag tag = persistent.getCompound(KEY);
        mob.setNoAi(tag.getBoolean("noai"));
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) mob)
                .tactical$persistenceRequired(tag.getBoolean("persistent"));
        mob.setTarget(null);
        clear(mob);
        Tactical.LOGGER.info("[Tactical] restored a saved companion {} to its own AI flag without re-admitting it",
                mob.getUUID());
    }
}
