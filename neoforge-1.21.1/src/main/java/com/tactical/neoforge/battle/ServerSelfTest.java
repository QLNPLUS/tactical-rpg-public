package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * Headless self-test driven by {@code -Dtactical.selftest=battle}: spawns a
 * fake player and a zombie, runs a full turn, and stops the server.
 */
public final class ServerSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static ServerPlayer fake;

    private ServerSelfTest() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String mode = System.getProperty("tactical.selftest", "");
        if (!"battle".equals(mode) && !"regression".equals(mode)) {
            return;
        }
        enabled = true;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        if ("regression".equals(System.getProperty("tactical.selftest"))) {
            setupRegression(level, spawn);
            return;
        }

        fake = new FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes("tactical-test".getBytes()), "TacticalTest"));
        fake.setPos(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);

        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie != null) {
            zombie.setPos(spawn.getX() + 3.5D, spawn.getY(), spawn.getZ() + 0.5D);
            level.addFreshEntity(zombie);
            Tactical.LOGGER.info("[Tactical] selftest zombie spawned at {}", zombie.blockPosition());
        } else {
            Tactical.LOGGER.error("[Tactical] selftest could not create zombie");
        }
        Tactical.LOGGER.info("[Tactical] selftest player at {}", fake.blockPosition());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled || fake == null) {
            return;
        }
        ticks++;
        if ("regression".equals(System.getProperty("tactical.selftest"))) {
            try {
                tickRegression(event.getServer());
            } catch (AssertionError error) {
                Tactical.LOGGER.error("[Tactical] REGRESSION FAILED at stage {}", regressionStage, error);
                BattleManager.stop(fake);
                enabled = false;
                try {
                    event.getServer().halt(false);
                } catch (Throwable ignored) {
                    // Fall through to the explicit exit below.
                }
                // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
                Runtime.getRuntime().halt(1);
                throw error;
            }
            return;
        }
        if (ticks == 20) {
            boolean started = BattleManager.start(fake, true);
            Tactical.LOGGER.info("[Tactical] selftest start battle={}", started);
            ServerBattle battle = BattleManager.current();
            if (battle != null) {
                Tactical.LOGGER.info("[Tactical] selftest initial: cell={} ap={} enemies={} reachable={}",
                        battle.playerCell(), battle.currentAp(), battle.enemyCount(),
                        battle.reachableCells().size());
            }
        }
        if (ticks == 40) {
            ServerBattle battle = BattleManager.current();
            if (battle != null && !battle.reachableCells().isEmpty()) {
                GridPos target = battle.reachableCells().stream()
                        .max(java.util.Comparator.comparingInt(cell -> cell.manhattanDistance(battle.playerCell())))
                        .orElse(null);
                if (target != null) {
                    BattleManager.move(fake, target.x(), target.y(), target.z());
                    Tactical.LOGGER.info("[Tactical] selftest long move -> {}", target);
                }
            } else {
                Tactical.LOGGER.warn("[Tactical] selftest no reachable cells");
            }
        }
        if (ticks == 60) {
            ServerBattle battle = BattleManager.current();
            if (battle != null) {
                String enemyId = battle.firstEnemyId();
                if (enemyId != null) {
                    fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
                    fake.getInventory().add(
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW, 16));
                    Tactical.LOGGER.info("[Tactical] selftest bow equipped, enemies before: {}",
                            battle.debugEnemies());
                    battle.tryAttack(UUID.fromString(enemyId));
                    Tactical.LOGGER.info("[Tactical] selftest enemies after bow: {}",
                            battle.debugEnemies());
                }
            }
        }
        if (ticks == 100) {
            ServerBattle battle = BattleManager.current();
            if (battle != null) {
                Tactical.LOGGER.info("[Tactical] selftest after move: cell={} ap={} phase={}",
                        battle.playerCell(), battle.currentAp(), battle.phaseName());
                BattleManager.endTurn(fake);
                Tactical.LOGGER.info("[Tactical] selftest end turn requested");
            }
        }
        if (ticks == 220) {
            ServerBattle battle = BattleManager.current();
            Tactical.LOGGER.info("[Tactical] selftest final: battle={} phase={} ap={} enemies={}",
                    battle != null,
                    battle == null ? "none" : battle.phaseName(),
                    battle == null ? -1 : battle.currentAp(),
                    battle == null ? -1 : battle.enemyCount());
            BattleManager.stop(fake);
            event.getServer().halt(false);
        }
    }

    // Opt-in deterministic fixture, never enabled by the normal battle self-test.
    // Run manually with -Dtactical.selftest=regression in a disposable test world.
    private static int regressionStage;
    private static GridPos ledge;
    private static GridPos exit;
    private static GridPos corner;
    private static Zombie obstruction;
    private static Zombie rangedTarget;
    private static final java.util.List<Zombie> fixtures = new java.util.ArrayList<>();
    private static boolean sawWest;
    private static boolean sawSouth;
    private static float targetHealth;
    private static int shotAp;
    private static int bowResolvedTick = -1;
    private static net.minecraft.world.item.ItemStack testWeapon;

    private static void setupRegression(ServerLevel level, BlockPos spawn) {
        int x = spawn.getX(), z = spawn.getZ();
        int floor = Math.min(level.getMaxBuildHeight() - 10, spawn.getY() + 16);
        // FakePlayer is not a chunk-loading player. Keep the remote fixture's
        // chunks entity-ticking or the entity index can hide/unload its mobs.
        for (int cx = (x - 12) >> 4; cx <= (x + 12) >> 4; cx++) {
            for (int cz = (z - 12) >> 4; cz <= (z + 12) >> 4; cz++) level.setChunkForced(cx, cz, true);
        }
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlock(new BlockPos(x + dx, floor, z + dz),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 4; dy++) level.setBlock(new BlockPos(x + dx, floor + dy, z + dz),
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
        }
        ledge = new GridPos(x, floor + 2, z);
        exit = new GridPos(x - 1, floor + 1, z);
        corner = new GridPos(x - 1, floor + 1, z + 1);
        for (int[] offset : new int[][] {{0, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            level.setBlock(new BlockPos(x + offset[0], floor + 1, z + offset[1]),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        }
        fake = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("tactical-regression".getBytes()), "GridRegression"));
        fake.setPos(x + 0.5, floor + 2, z + 0.5);
        fake.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED).setBaseValue(100);
        fake.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0.2);
        obstruction = regressionZombie(level, x + 1, floor + 2, z);
        regressionZombie(level, x, floor + 2, z - 1);
        regressionZombie(level, x, floor + 2, z + 1);
        // Shoot outwards from the downhill corner, not through the south-side
        // blocker: real arrows correctly collide with nearer combatants.
        rangedTarget = regressionZombie(level, x - 7, floor + 1, z + 1);
    }

    private static Zombie regressionZombie(ServerLevel level, int x, int y, int z) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        require(zombie != null, "zombie fixture creation");
        zombie.setPos(x + 0.5, y, z + 0.5);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_HELMET));
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        zombie.setHealth(100);
        require(level.addFreshEntity(zombie), "fixture added to server level");
        fixtures.add(zombie);
        return zombie;
    }

    private static void tickRegression(MinecraftServer server) {
        if (ticks < 20) return;
        if (ticks >= 400) require(false, "regression completion deadline");
        ServerBattle battle = BattleManager.current();
        ServerLevel level = server.overworld();
        if (regressionStage == 0) {
            int visible = level.getEntitiesOfClass(Zombie.class, fake.getBoundingBox().inflate(24)).size();
            if (ticks % 20 == 0) Tactical.LOGGER.info("[Tactical] fixture player={} blocker={} alive={} removed={} target={} visible={}",
                    fake.position(), obstruction.position(), obstruction.isAlive(), obstruction.isRemoved(), rangedTarget.position(), visible);
            if (visible < 4) return; // wait for asynchronous entity-chunk visibility
            for (var stray : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, fake.getBoundingBox().inflate(24))) {
                if (stray instanceof net.minecraft.world.entity.monster.Enemy && !fixtures.contains(stray)) stray.discard();
            }
            require(BattleManager.start(fake, true), "battle starts");
            battle = BattleManager.current();
            require(battle != null && battle.isPlayerTurnNow(), "high-initiative player acts first");
            require(battle.playerCell().equals(ledge), "actual ledge height preserved");
            require(battle.reachableCells().contains(exit), "one-block downward exit advertised");
            GridPos blocked = new GridPos(obstruction.getBlockX(), obstruction.getBlockY(), obstruction.getBlockZ());
            require(!battle.reachableCells().contains(blocked), "living blocker occupies cell");
            int revision = battle.stateRevision(), count = battle.enemyCount();
            obstruction.setHealth(0);
            battle.tick();
            require(battle.enemyCount() == count - 1, "dead combatant removed without another action");
            require(battle.stateRevision() > revision && battle.reachableCells().contains(blocked), "death immediately refreshes reachable snapshot");
            battle.tryMove(corner);
            regressionStage++;
        } else if (regressionStage == 1) {
            if (fake.getX() < ledge.x() + 0.45 && fake.getZ() < ledge.z() + 0.55) {
                sawWest |= Math.abs(net.minecraft.util.Mth.wrapDegrees(fake.getYRot() - 90)) < 1;
            }
            if (fake.getZ() > ledge.z() + 0.55) {
                sawSouth |= Math.abs(net.minecraft.util.Mth.wrapDegrees(fake.getYRot())) < 1;
            }
            if (!battle.isPlayerTurnNow()) return;
            require(battle.playerCell().equals(corner), "downhill corner path completes");
            require(sawWest && sawSouth, "model faces each path segment");
            fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
            fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW, 8));
            testWeapon = fake.getMainHandItem();
            targetHealth = rangedTarget.getHealth();
            int ap = battle.currentAp();
            int quotedCost = battle.currentAttackCost();
            battle.tryAttack(rangedTarget.getUUID());
            require(battle.pendingProjectiles() > 0, "bow creates a tracked real projectile");
            require(rangedTarget.getHealth() == targetHealth, "no fake instantaneous arrow damage");
            require(testWeapon.getDamageValue() == 1, "vanilla bow durability consumed");
            require(fake.getInventory().countItem(net.minecraft.world.item.Items.SPECTRAL_ARROW) == 7, "vanilla spectral ammo consumed once");
            require(battle.currentAp() == ap - quotedCost, "bow AP charged once");
            shotAp = battle.currentAp();
            battle.endPlayerTurn(); battle.tryDefend(); battle.tryFlee(); battle.tryMove(exit);
            require(battle.currentAp() == shotAp && "PLAYER_TURN".equals(battle.phaseName()), "flight rejects premature next-turn and actions");
            regressionStage++;
        } else if (regressionStage == 2) {
            if (battle.pendingProjectiles() > 0 || !battle.isPlayerTurnNow()) return;
            if (bowResolvedTick < 0) {
                require(battle.isPlayerTurnNow(), "bow flight eventually unlocks");
                require(rangedTarget.getHealth() < targetHealth, "real spectral projectile hits");
                bowResolvedTick = ticks;
                Tactical.LOGGER.info("[Tactical] regression bow resolved: tick={} health={} hurtCooldown={} target={}",
                        ticks, rangedTarget.getHealth(), rangedTarget.invulnerableTime, rangedTarget.position());
            }
            // Flight/knockback completion is not vanilla damage-cooldown completion.
            // LivingEntity.hurt rejects equal/weaker hits while invulnerableTime > 10;
            // critical bow/crossbow rolls can otherwise make this fixture intermittent.
            // Let real entity ticks expire the full cooldown instead of clearing it or
            // weakening the real-health assertion for the next independent weapon test.
            if (rangedTarget.invulnerableTime > 0) return;
            require(rangedTarget.invulnerableTime == 0, "vanilla hurt cooldown expires before next weapon");
            Tactical.LOGGER.info("[Tactical] regression crossbow ready: tick={} waitedTicks={} health={} hurtCooldown={} shooter={} target={}",
                    ticks, ticks - bowResolvedTick, rangedTarget.getHealth(), rangedTarget.invulnerableTime,
                    fake.position(), rangedTarget.position());
            // Same-height opaque cover must reject before consuming AP/ammo.
            BlockPos cover = new BlockPos(ledge.x() - 4, corner.y(), corner.z());
            for (int dy = 0; dy < 3; dy++) level.setBlock(cover.above(dy), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            battle.tryAttack(rangedTarget.getUUID());
            require(battle.pendingProjectiles() == 0 && battle.currentAp() == shotAp, "same-height LOS rejects covered shot");
            require(fake.getInventory().countItem(net.minecraft.world.item.Items.SPECTRAL_ARROW) == 7, "covered shot consumes no ammo");
            for (int dy = 0; dy < 3; dy++) level.setBlock(cover.above(dy), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            testWeapon = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
            fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, testWeapon);
            net.minecraft.world.item.CrossbowItem crossbow = (net.minecraft.world.item.CrossbowItem) testWeapon.getItem();
            crossbow.releaseUsing(testWeapon, level, fake, 0);
            require(net.minecraft.world.item.CrossbowItem.isCharged(testWeapon), "vanilla crossbow preloaded");
            for (int slot = 0; slot < fake.getInventory().getContainerSize(); slot++) {
                if (fake.getInventory().getItem(slot).is(net.minecraft.world.item.Items.SPECTRAL_ARROW)) fake.getInventory().setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
            require(fake.getProjectile(testWeapon).isEmpty(), "no loose crossbow ammo remains");
            targetHealth = rangedTarget.getHealth();
            battle.tryAttack(rangedTarget.getUUID());
            require(battle.pendingProjectiles() > 0, "already-loaded crossbow fires without loose ammo");
            require(!net.minecraft.world.item.CrossbowItem.isCharged(testWeapon), "loaded projectile consumed");
            require(testWeapon.getDamageValue() == 1, "vanilla crossbow durability");
            regressionStage++;
        } else if (regressionStage == 3) {
            if (battle.pendingProjectiles() > 0 || !battle.isPlayerTurnNow()) return;
            Tactical.LOGGER.info("[Tactical] regression crossbow resolved: tick={} healthBefore={} healthAfter={} hurtCooldown={} target={}",
                    ticks, targetHealth, rangedTarget.getHealth(), rangedTarget.invulnerableTime, rangedTarget.position());
            require(rangedTarget.getHealth() < targetHealth, "real loaded crossbow hits");
            Tactical.LOGGER.info("[Tactical] REGRESSION PASSED: ledge, facing, death refresh, spectral bow, LOS, loaded crossbow, flight locks");
            BattleManager.stop(fake);
            enabled = false;
            server.halt(false);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        Tactical.LOGGER.info("[Tactical] regression PASS: {}", label);
    }
}
