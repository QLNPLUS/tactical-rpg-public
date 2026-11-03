package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The snapshot check of one battle placement.
 *
 * <p>A placement is not always one block: a bed, a door or a tall plant captures several snapshots, and
 * an item may move the placement context on its own. The clicked cell is therefore not proof that every
 * affected cell is legal, and repairing the world afterwards would mean deleting blocks and re-creating
 * items by hand.</p>
 *
 * <p>So the check runs inside the native place event that
 * {@code CommonHooks.onPlaceItemIntoWorld} fires while the snapshots are still captured: refusing there
 * makes vanilla restore every captured block and put the item stack back, which is exactly the rollback a
 * battle action needs. The scope is thread-confined and only set by {@code ServerBattle.placeBlock}, so an
 * ordinary player placing a block outside a battle never meets this listener.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BlockPlaceGuard {

    private static final ThreadLocal<Predicate<BlockPos>> ACTIVE = new ThreadLocal<>();

    private BlockPlaceGuard() {
    }

    /**
     * Run {@code action} with these placement rules in force.
     *
     * <p>The previous scope is restored afterwards, so nesting can never leave a rule behind on the
     * server thread.</p>
     */
    public static <T> T run(Predicate<BlockPos> allowed, Supplier<T> action) {
        Predicate<BlockPos> previous = ACTIVE.get();
        ACTIVE.set(allowed);
        try {
            return action == null ? null : action.get();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    /** Whether the running placement scope allows this cell; true when no battle action is running. */
    public static boolean allows(BlockPos pos) {
        Predicate<BlockPos> allowed = ACTIVE.get();
        return allowed == null || allowed.test(pos);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (ACTIVE.get() == null || event == null) return;
        if (!allows(event.getPos())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (ACTIVE.get() == null || event == null) return;
        var snapshots = event.getReplacedBlockSnapshots();
        if (snapshots == null) return;
        for (var snapshot : snapshots) {
            if (snapshot == null) continue;
            if (!allows(snapshot.getPos())) {
                // One illegal cell refuses the whole placement, because vanilla rolls back all of them
                // together: keeping the legal part would be a half action nobody priced.
                event.setCanceled(true);
                return;
            }
        }
    }
}
