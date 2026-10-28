package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.network.AttackRangeRequestPacket;
import com.tactical.neoforge.network.AttackRangeSnapshotPacket;
import com.tactical.neoforge.network.AttackRangeSnapshotPacket.Target;
import com.tactical.neoforge.network.AttackRangeSnapshotPacket.Tile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-member, request-driven native preview. Terrain tint never substitutes for attack
 * validation, and one player's preview cache can never be served to another player.
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class AttackRangeService {
    public static final int CACHE_TICKS = 4;

    private record ParticipantStamp(int id, UUID uuid, double x, double y, double z, AABB bounds,
                                    boolean alive, float health) { }
    private record Fingerprint(GridPos origin, int revision, int kind, int selected,
                               List<ParticipantStamp> roster) { }

    private static final class Entry {
        ServerBattle source;
        ServerLevel level;
        UUID battleId;
        /** Membership generation this cache belongs to; a new one invalidates it. */
        long membershipGeneration = Long.MIN_VALUE;
        long lastRequestTick = Long.MIN_VALUE;
        AttackRangeSnapshotPacket cached;
        Fingerprint fingerprint;
        List<ItemStack> inventory = List.of();
    }

    private static final Map<UUID, Entry> CACHE = new HashMap<>();

    private AttackRangeService() { }

    /**
     * Drop one player's preview cache.
     *
     * <p>Called on every membership transition: a cache built for an earlier membership must never be
     * served afterwards, not even when the player re-enters the same encounter, because the geometry
     * and the roster it was computed from may be stale.</p>
     */
    public static void forget(ServerPlayer player) {
        if (player != null) CACHE.remove(player.getUUID());
    }

    public static void request(ServerPlayer player, AttackRangeRequestPacket request) {
        ServerBattle battle = BattleManager.findFor(player.getUUID());
        if (battle == null || battle.finished() || !battle.isMember(player)) return;
        // A stale session or an older turn generation is dropped without touching any state.
        if (!battle.session().accepts(request.stamp())) return;
        long tick = player.level().getGameTime();
        Entry entry = CACHE.computeIfAbsent(player.getUUID(), ignored -> new Entry());
        long generation = com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(player);
        if (entry.source != battle || entry.membershipGeneration != generation) {
            clearEntry(entry);
            entry.source = battle;
            entry.level = player.serverLevel();
            entry.battleId = battle.sessionId();
            entry.membershipGeneration = generation;
        }
        if (entry.level != player.serverLevel()) {
            clearCache(entry);
            return;
        }
        // No client-controlled coordinates, radius, roster or weapon; bound request work before scanning.
        if (entry.lastRequestTick == tick) return;
        entry.lastRequestTick = tick;
        GridPos origin = battle.playerCell(player);
        if (origin == null) return;
        int kind = battle.weaponKind(player);
        if (!player.isAlive() || !battle.isPlayerTurnNow(player) || battle.isBusy()) {
            clearCache(entry);
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                    new AttackRangeSnapshotPacket(request.requestId(), false,
                    entry.battleId, player.level().dimension().location(), tick, origin, kind, false, List.of(), List.of()));
            return;
        }
        List<LivingEntity> participants = battle.participants();
        List<ParticipantStamp> roster = participants.stream().limit(AttackRangeSnapshotPacket.MAX_TARGETS)
                .map(entity -> new ParticipantStamp(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(),
                        entity.getZ(), entity.getBoundingBox(), entity.isAlive() && !entity.isRemoved(), entity.getHealth()))
                .toList();
        Fingerprint now = new Fingerprint(origin, battle.stateRevision(), kind, player.getInventory().selected, roster);
        if (entry.cached == null || tick < entry.cached.serverTick() || tick - entry.cached.serverTick() >= CACHE_TICKS
                || !now.equals(entry.fingerprint) || !inventoryMatches(entry, player)) {
            entry.cached = build(battle, player, participants, origin, kind, tick, entry.battleId);
            entry.fingerprint = now;
            entry.inventory = new ArrayList<>(player.getInventory().getContainerSize());
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
                entry.inventory.add(player.getInventory().getItem(slot).copy());
        }
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player, entry.cached.forRequest(request.requestId()));
    }

    private static boolean inventoryMatches(Entry entry, ServerPlayer player) {
        if (entry.inventory.size() != player.getInventory().getContainerSize()) return false;
        for (int slot = 0; slot < entry.inventory.size(); slot++) {
            if (!ItemStack.matches(entry.inventory.get(slot), player.getInventory().getItem(slot))) return false;
        }
        return true;
    }

    private static AttackRangeSnapshotPacket build(ServerBattle battle, ServerPlayer player,
                                                   List<LivingEntity> participants, GridPos origin, int kind,
                                                   long tick, UUID battleId) {
        ServerLevel level = player.serverLevel();
        List<Tile> tiles = new ArrayList<>();
        boolean truncated = false;
        int radius = kind == 0 ? 1 : Math.min(AttackRangeGeometry.MAX_RADIUS,
                Math.max(battle.weaponRangeFor(player, origin), battle.weaponRangeFor(player,
                        new GridPos(origin.x(), origin.y() - AttackRangeGeometry.MAX_ELEVATION, origin.z()))));
        // Visit near columns first so truncation remains useful on layered terrain.
        scan: for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                int x = origin.x() + dx;
                int z = origin.z() + dz;
                if (!level.hasChunkAt(new BlockPos(x, origin.y(), z))) continue;
                for (int offset = 0; offset <= AttackRangeGeometry.MAX_ELEVATION * 2; offset++) {
                    int dy = offset == 0 ? 0 : (offset % 2 == 1 ? (offset + 1) / 2 : -offset / 2);
                    GridPos cell = new GridPos(x, origin.y() + dy, z);
                    if (!battle.zone().contains(cell)
                            || !AttackRangeGeometry.contains(origin, cell, kind, battle.weaponRangeFor(player, cell))
                            || !LevelGridWorld.isStandable(level, x, cell.y(), z)) continue;
                    if (tiles.size() == AttackRangeGeometry.MAX_TILES) {
                        truncated = true;
                        break scan;
                    }
                    BlockPos support = new BlockPos(x, cell.y() - 1, z);
                    double top = support.getY() + level.getBlockState(support).getCollisionShape(level, support).max(Direction.Axis.Y);
                    if (Double.isFinite(top) && top >= cell.y() - 1D && top <= cell.y() + 1D)
                        tiles.add(new Tile(cell, top));
                }
            }
        }
        List<Target> targets = new ArrayList<>();
        int checked = 0;
        for (LivingEntity target : participants) {
            if (checked++ == AttackRangeSnapshotPacket.MAX_TARGETS) {
                truncated = true;
                break;
            }
            if (target == player || !target.isAlive() || target.isRemoved()) continue;
            if (battle.previewWeaponAttack(player, target).isEmpty()) targets.add(new Target(target.getId(), target.getUUID()));
        }
        return new AttackRangeSnapshotPacket(0, true, battleId, level.dimension().location(), tick,
                origin, kind, truncated, tiles, targets);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (CACHE.isEmpty()) return;
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Entry> entry : CACHE.entrySet()) {
            Entry value = entry.getValue();
            ServerBattle battle = value.source;
            if (battle == null || battle.finished() || BattleManager.findFor(entry.getKey()) != battle) {
                stale.add(entry.getKey());
                continue;
            }
            long now = value.level.getGameTime();
            if (now < value.lastRequestTick || now - value.lastRequestTick >= CACHE_TICKS) clearCache(value);
        }
        for (UUID id : stale) CACHE.remove(id);
    }

    @SubscribeEvent public static void onServerStopped(ServerStoppedEvent event) { clear(); }

    private static void clearCache(Entry entry) {
        entry.cached = null;
        entry.fingerprint = null;
        entry.inventory = List.of();
    }

    private static void clearEntry(Entry entry) {
        clearCache(entry);
        entry.source = null;
        entry.level = null;
        entry.battleId = null;
        entry.lastRequestTick = Long.MIN_VALUE;
    }

    private static void clear() {
        CACHE.clear();
    }
}
