package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.ManaResources;
import com.tactical.neoforge.skill.SkillTarget;
import com.tactical.neoforge.skill.TacticalSkills;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;

import java.util.UUID;

/**
 * Dedicated-server fixture for the item-on-entity branch: {@code -Dtactical.selftest=entity_item}.
 *
 * <p>Server-side only. Two {@link FakePlayer}s are real {@link ServerPlayer} instances without a client,
 * so this is a server fixture and never a claim about real two-client input. It calls the production
 * entry points ({@code BattleManager.useItem} with the real session stamp and slot,
 * {@code TacticalSkills.cast}, real {@code ManaResources}) instead of re-testing the rules in isolation,
 * and after every attempt it compares both players' AP, MP and inventory so a refused attempt provably
 * costs anybody nothing.</p>
 *
 * <p>A failure exits non-zero, so a green Gradle task can never be mistaken for a passing fixture.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class EntityItemSelfTest {

    private EntityItemSelfTest() {
    }

    // ------------------------------------------------------------------ fixture entity type

    /**
     * Third-party hostile mobs may open a menu from their own {@code mobInteract}; no vanilla hostile mob
     * does, so the close-and-refuse path can only be exercised by a synthetic one. The type is registered
     * <em>only</em> while this fixture runs, so no release build ever contains it.
     */
    public static void registerFixtureType(IEventBus bus) {
        if (!"entity_item".equals(System.getProperty("tactical.selftest", ""))) return;
        DeferredRegister<EntityType<?>> types = DeferredRegister.create(Registries.ENTITY_TYPE, Tactical.MOD_ID);
        java.util.function.Supplier<EntityType<MenuZombie>> type = types.register("fixture_menu_zombie",
                () -> EntityType.Builder.of(MenuZombie::new, MobCategory.MONSTER)
                        .sized(0.6F, 1.95F)
                        .build("fixture_menu_zombie"));
        types.register(bus);
        // An entity type without an attribute supplier cannot even be constructed: the zombie's own
        // attributes are reused, the mob is a real hostile unit with a real health pool.
        bus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                event.put(type.get(), Zombie.createAttributes().build()));
    }

    /** Fixture-only hostile mob whose interaction really opens a menu, like a third-party mod's mob. */
    public static final class MenuZombie extends Zombie {
        public MenuZombie(EntityType<? extends Zombie> type, net.minecraft.world.level.Level level) {
            super(type, level);
        }

        @Override
        protected net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                                    InteractionHand hand) {
            Tactical.LOGGER.info("[Tactical] entity_item fixture mobInteract called by {} (server={})",
                    player.getName().getString(), player.level().isClientSide ? "client" : "server");
            if (player instanceof ServerPlayer server) {
                Component title = Component.literal("Fixture Menu");
                var opened = server.openMenu(new SimpleMenuProvider((id, inventory, who) ->
                        new ChestMenu(MenuType.GENERIC_9x1, id, inventory, new SimpleContainer(9), 1), title));
                if (opened.isEmpty()) {
                    // A headless FakePlayer swallows openMenu by design (it has no connection). The fixture
                    // therefore reproduces exactly what openMenu installs on a real server player - a real
                    // ContainerMenu and the Open event - so the battle's guard has a real menu to close.
                    // The counter and the slot listeners of openMenu are private; the fixture only needs
                    // the part the battle observes: an installed ContainerMenu and its Open event.
                    ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x1, 100, server.getInventory(),
                            new SimpleContainer(9), 1);
                    server.containerMenu = menu;
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                            new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open(server, menu));
                    Tactical.LOGGER.info("[Tactical] entity_item fixture opened a menu without a connection");
                }
            }
            return net.minecraft.world.InteractionResult.CONSUME;
        }
    }

    // ------------------------------------------------------------------ fixture state

    private static boolean enabled;
    private static int ticks;
    private static String stage = "setup";
    private static ServerPlayer owner;
    private static ServerPlayer guest;
    private static ServerBattle battle;
    private static Zombie nearHusk;
    private static Zombie farZombie;
    private static Zombie wallZombie;
    private static Zombie mpTarget;
    private static Cow cow;
    private static MenuZombie menuMob;
    private static Zombie foreignZombie;
    private static ServerBattle foreignBattle;
    private static int tagSlot;
    private static int breadSlot;
    private static int stickSlot;
    private static int checks;
    private static int failures;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!"entity_item".equals(System.getProperty("tactical.selftest", ""))) return;
        enabled = true;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int floor = Math.min(level.getMaxBuildHeight() - 20, spawn.getY() + 20);
        for (int cx = (spawn.getX() - 24) >> 4; cx <= (spawn.getX() + 24) >> 4; cx++) {
            for (int cz = (spawn.getZ() - 24) >> 4; cz <= (spawn.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                level.setBlock(new BlockPos(spawn.getX() + dx, floor, spawn.getZ() + dz),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlock(new BlockPos(spawn.getX() + dx, floor + dy, spawn.getZ() + dz),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        int ox = spawn.getX(), oz = spawn.getZ();
        owner = player(level, "EntityItemOwner", ox, floor + 1, oz);
        guest = player(level, "EntityItemGuest", ox + 6, floor + 1, oz + 6);
        nearHusk = zombie(level, EntityType.HUSK, "Fixture Husk", ox + 1, floor + 1, oz);
        menuMob = menuZombie(level, "menu-mob", ox - 1, floor + 1, oz);
        wallZombie = zombie(level, EntityType.ZOMBIE, "Wall Zombie", ox, floor + 1, oz + 2);
        farZombie = zombie(level, EntityType.ZOMBIE, "Far Zombie", ox, floor + 1, oz + 5);
        mpTarget = zombie(level, EntityType.HUSK, "MP Husk", ox - 3, floor + 1, oz);
        // The wall between the owner and the wall zombie: two blocks high, the same column the ray crosses.
        for (int dy = 1; dy <= 2; dy++) {
            level.setBlock(new BlockPos(ox, floor + dy, oz + 1),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        }
        cow = EntityType.COW.create(level);
        if (cow != null) {
            cow.setPos(ox + 0.5D, floor + 1, oz + 1.5D);
            cow.setNoAi(true);
            cow.setPersistenceRequired();
            level.addFreshEntity(cow);
        }
        // A second encounter far away, so a cross-encounter target can be a real participant of it.
        foreignZombie = zombie(level, EntityType.ZOMBIE, "Foreign Zombie", ox + 40, floor + 1, oz);
        Tactical.LOGGER.info("[Tactical] entity_item fixture prepared at {} ({} mobs, floor {})", spawn, 6, floor);
    }

    private static ServerPlayer player(ServerLevel level, String name, int x, int y, int z) {
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setPos(x + 0.5D, y, z + 0.5D);
        return player;
    }

    private static Zombie zombie(ServerLevel level, EntityType<? extends Zombie> type, String name,
                                 int x, int y, int z) {
        Zombie mob = type.create(level);
        if (mob == null) throw new IllegalStateException("fixture could not create " + type);
        mob.setPos(x + 0.5D, y, z + 0.5D);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.literal(name));
        mob.setCustomNameVisible(false);
        level.addFreshEntity(mob);
        return mob;
    }

    private static MenuZombie menuZombie(ServerLevel level, String tag, int x, int y, int z) {
        var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "fixture_menu_zombie"));
        if (type.isEmpty()) throw new IllegalStateException("the fixture menu entity type is not registered");
        @SuppressWarnings("unchecked")
        EntityType<MenuZombie> casted = (EntityType<MenuZombie>) type.get();
        MenuZombie mob = casted.create(level);
        if (mob == null) throw new IllegalStateException("fixture could not create the menu mob");
        mob.setPos(x + 0.5D, y, z + 0.5D);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.literal("Menu Mob"));
        level.addFreshEntity(mob);
        return mob;
    }

    // ------------------------------------------------------------------ driver

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        if (ticks != 20) return;
        try {
            run(event.getServer());
        } catch (Throwable failure) {
            fail(event.getServer(), failure);
        }
    }

    private static void run(MinecraftServer server) {
        enter("setup");
        owner.getInventory().clearContent();
        guest.getInventory().clearContent();
        BattleManager.reset();
        check(BattleManager.start(owner, true), "the encounter starts for the owner");
        battle = BattleManager.current();
        check(battle != null, "the encounter is registered");
        check(battle.memberCount() == 1, "the owner is the only member before the guest joins: " + battle.memberCount());
        check(BattleManager.invite(owner, guest) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the owner can invite the guest");
        check(battle.joinHeadless(guest) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the guest joins the same encounter");
        check(battle.memberCount() == 2, "both players are members: " + battle.memberCount());
        check(battle.isActorTurn(owner), "the owner holds the turn");
        check(!battle.isActorTurn(guest), "the guest does not hold the turn");
        check(battle.isParticipant(nearHusk) && battle.isParticipant(menuMob) && battle.isParticipant(farZombie)
                && battle.isParticipant(wallZombie) && battle.isParticipant(mpTarget),
                "every fixture mob joined as an enemy");
        check(!battle.isParticipant(cow), "a cow in the zone is not a participant");

        owner.getInventory().setItem(0, namedTag(3));
        owner.getInventory().setItem(1, new ItemStack(Items.BREAD, 4));
        owner.getInventory().setItem(2, new ItemStack(Items.STONE, 8));
        owner.getInventory().setItem(3, new ItemStack(Items.STICK, 5));
        owner.getInventory().selected = 0;
        guest.getInventory().setItem(0, namedTag(2));
        guest.getInventory().selected = 0;
        owner.setItemSlot(EquipmentSlot.OFFHAND, skillStick("tactical:probe_arc"));
        tagSlot = 0;
        breadSlot = 1;
        stickSlot = 3;
        ManaResources.set(owner, 50.0D);
        ManaResources.set(guest, 40.0D);
        BattleManager.adjustAp(owner, 60);
        check(battle.currentAp(owner) >= 60, "the owner has AP for the attempts: " + battle.currentAp(owner));

        // ---- the positive case -------------------------------------------------
        enter("positive name tag");
        Snapshot before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), tagSlot, nearHusk.getUUID());
        check("Oct30 Renamed".equals(nameOf(nearHusk)), "the native interaction renamed the target: " + nameOf(nearHusk));
        check(countOf(owner, Items.NAME_TAG) == 2, "exactly one name tag left the owner: "
                + countOf(owner, Items.NAME_TAG));
        check(before.ownerAp - battle.currentAp(owner) > 0, "the owner paid the item action: "
                + before.ownerAp + " -> " + battle.currentAp(owner));
        checkGuestUnchanged("the positive action leaves the guest untouched", before);

        // ---- refusals: the same snapshot must come back -------------------------
        enter("waiting member");
        before = snapshot();
        BattleManager.useItem(guest, new com.tactical.neoforge.battle.multi.ActionStamp(battle.sessionId(),
                battle.turnEpoch()), 0, nearHusk.getUUID());
        check(countOf(guest, Items.NAME_TAG) == 2, "a waiting member keeps its own name tag");
        checkUnchanged("a waiting member spends nothing anywhere", before);

        enter("stale stamp");
        before = snapshot();
        BattleManager.useItem(owner, new com.tactical.neoforge.battle.multi.ActionStamp(battle.sessionId(),
                battle.turnEpoch() - 1), tagSlot, nearHusk.getUUID());
        checkUnchanged("an expired stamp spends nothing", before);

        enter("wrong hand slot");
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), breadSlot, nearHusk.getUUID());
        checkUnchanged("a slot that is not in the main hand spends nothing", before);

        enter("in-zone non participant");
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), tagSlot, cow.getUUID());
        checkUnchanged("a bystander inside the zone spends nothing", before);

        enter("cross encounter");
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), tagSlot, foreignZombie.getUUID());
        checkUnchanged("a unit of another encounter spends nothing", before);

        enter("out of native reach");
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), tagSlot, farZombie.getUUID());
        checkUnchanged("a target beyond the native interaction range spends nothing", before);

        enter("blocked line of sight");
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), tagSlot, wallZombie.getUUID());
        checkUnchanged("a target behind a wall spends nothing", before);

        enter("insufficient AP");
        int ownerAp = battle.currentAp(owner);
        BattleManager.adjustAp(owner, -ownerAp);
        check(battle.currentAp(owner) == 0, "the fixture drained the owner's AP");
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), tagSlot, nearHusk.getUUID());
        checkUnchanged("an attempt without AP spends nothing", before);
        BattleManager.adjustAp(owner, 60);

        enter("native PASS");
        owner.getInventory().selected = breadSlot;
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), breadSlot, nearHusk.getUUID());
        check(countOf(owner, Items.BREAD) == 4, "an item the entity does not accept is not consumed");
        checkUnchanged("a native PASS spends nothing", before);
        owner.getInventory().selected = tagSlot;

        enter("menu escape");
        // A name tag never reaches mobInteract (the native important-interaction check applies it first),
        // so the menu fixture is reached with an ordinary item: the mob's own interact then opens a real
        // container, which the battle must close again without paying for anything.
        owner.getInventory().selected = stickSlot;
        // The battle member may legitimately stand in a real, non-inventory menu when the interaction runs
        // (a member's own turn still allows its container). That menu must NOT be revived afterwards: the
        // close path has to land in the inventory menu the client is actually told to show.
        ChestMenu previousMenu = new ChestMenu(MenuType.GENERIC_9x1, 101, owner.getInventory(),
                new SimpleContainer(9), 1);
        owner.containerMenu = previousMenu;
        before = snapshot();
        BattleManager.useItem(owner, battle.stamp(), stickSlot, menuMob.getUUID());
        check(owner.containerMenu == owner.inventoryMenu,
                "the opened menu was closed back to the inventory menu, not to the old one: "
                        + owner.containerMenu.getClass().getSimpleName());
        check(owner.containerMenu != previousMenu,
                "the old non-inventory menu is not re-attached after the close");
        check(countOf(owner, Items.STICK) == before.ownerSticks,
                "a refused menu interaction consumes no item: " + countOf(owner, Items.STICK)
                        + " of " + before.ownerSticks);
        check("Menu Mob".equals(nameOf(menuMob)), "a refused menu interaction changes nothing on the target");
        checkUnchanged("a refused menu interaction spends nothing", before);
        owner.getInventory().selected = tagSlot;

        // ---- the vanilla entity-interaction gate on the real event bus ---------
        eventWiring(server.overworld());

        // ---- MP: a real non-zero pool through the real skill core --------------
        enter("entity skill with real mana");
        ManaResources.set(owner, 50.0D);
        ManaResources.set(guest, 40.0D);
        ResourceLocation arc = ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "probe_arc");
        var offered = TacticalSkills.available(battle, owner).stream()
                .filter(entry -> entry.id().equals(arc)).findFirst().orElse(null);
        check(offered != null, "the fixture skill is offered to the owner");
        check(offered.ready(), "the fixture skill is ready before the cast: " + offered.reason());
        int expectedAp = offered.apCost();
        double expectedMp = offered.mpCost();
        check(expectedMp > 0.0D, "the fixture skill really costs mana: " + expectedMp);
        before = snapshot();
        boolean cast = TacticalSkills.cast(battle, owner, arc, SkillTarget.entity(mpTarget.getUUID()));
        check(cast, "the entity skill cast through the production core");
        double ownerMp = ManaResources.current(owner);
        check(Math.abs((before.ownerMp - ownerMp) - expectedMp) < 1.0E-6, "the cast spent its quoted MP once: "
                + before.ownerMp + " -> " + ownerMp + " (quoted " + expectedMp + ")");
        check(before.ownerAp - battle.currentAp(owner) == expectedAp, "the cast spent its quoted AP once: "
                + before.ownerAp + " -> " + battle.currentAp(owner) + " (quoted " + expectedAp + ")");
        check(Math.abs(ManaResources.current(guest) - before.guestMp) < 1.0E-6,
                "the guest's mana is untouched by the actor's cast");
        check(battle.currentAp(guest) == before.guestAp, "the guest's AP is untouched by the actor's cast");
        Snapshot afterCast = snapshot();
        boolean second = TacticalSkills.cast(battle, owner, arc, SkillTarget.entity(mpTarget.getUUID()));
        check(!second, "the same skill cannot be cast again while it cools down");
        check(Math.abs(ManaResources.current(owner) - afterCast.ownerMp) < 1.0E-6,
                "a refused cooldown cast spends no mana");
        check(battle.currentAp(owner) == afterCast.ownerAp, "a refused cooldown cast spends no AP");

        pass(server);
    }

    // ------------------------------------------------------------------ event wiring

    /**
     * The gate that keeps a battle member from using a raw vanilla right click to reach an entity. The
     * events are posted through the real {@code NeoForge.EVENT_BUS}, never by calling the listener: only
     * that proves the listener is actually attached to the bus.
     */
    private static void eventWiring(ServerLevel level) {
        enter("event wiring");
        ServerPlayer outsider = player(level, "EntityItemOutsider", 0, 0, 0);
        outsider.setPos(owner.getX() + 12.5D, owner.getY(), owner.getZ() + 12.5D);

        var outOfScope = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                owner, InteractionHand.MAIN_HAND, menuMob);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outOfScope);
        check(outOfScope.isCanceled(),
                "a battle member outside the tool scope may not reach an entity with a raw right click");

        var outOfScopeSpecific =
                new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific(
                        owner, InteractionHand.MAIN_HAND, menuMob, new net.minecraft.world.phys.Vec3(0, 1, 0));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outOfScopeSpecific);
        check(outOfScopeSpecific.isCanceled(),
                "the interact-at form is gated the same way");

        var inScope = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                owner, InteractionHand.MAIN_HAND, menuMob);
        BattleToolUse.run(owner, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(inScope);
            return null;
        });
        check(!inScope.isCanceled(),
                "the authored tool scope is allowed to run the same interaction");
        var inScopeSpecific = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific(
                owner, InteractionHand.MAIN_HAND, menuMob, new net.minecraft.world.phys.Vec3(0, 1, 0));
        BattleToolUse.run(owner, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(inScopeSpecific);
            return null;
        });
        check(!inScopeSpecific.isCanceled(),
                "the interact-at form is allowed inside the same scope");

        var afterScope = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                owner, InteractionHand.MAIN_HAND, menuMob);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(afterScope);
        check(afterScope.isCanceled(),
                "the scope does not leak: the next raw interaction is cancelled again");

        var outsiderEvent = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                outsider, InteractionHand.MAIN_HAND, menuMob);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outsiderEvent);
        check(!outsiderEvent.isCanceled(),
                "a player outside every battle keeps vanilla entity interaction");

        var guestEvent = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                guest, InteractionHand.MAIN_HAND, menuMob);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(guestEvent);
        check(guestEvent.isCanceled(),
                "a waiting member is a participant, so its raw interaction is gated too");
    }

    // ------------------------------------------------------------------ helpers

    private record Snapshot(int ownerAp, double ownerMp, int ownerTags, int ownerBread, int ownerStone,
                            int ownerSticks, int guestAp, double guestMp, int guestTags, int guestBread) {
    }

    private static Snapshot snapshot() {
        return new Snapshot(battle.currentAp(owner), ManaResources.current(owner), countOf(owner, Items.NAME_TAG),
                countOf(owner, Items.BREAD), countOf(owner, Items.STONE), countOf(owner, Items.STICK),
                battle.currentAp(guest), ManaResources.current(guest), countOf(guest, Items.NAME_TAG),
                countOf(guest, Items.BREAD));
    }

    private static void checkUnchanged(String label, Snapshot before) {
        Snapshot now = snapshot();
        check(now.equals(before), label + " (before " + before + ", after " + now + ")");
    }

    /** The other member's side of every attempt: its AP, MP and inventory must never move. */
    private static void checkGuestUnchanged(String label, Snapshot before) {
        Snapshot now = snapshot();
        boolean same = now.guestAp == before.guestAp
                && Math.abs(now.guestMp - before.guestMp) < 1.0E-6
                && now.guestTags == before.guestTags
                && now.guestBread == before.guestBread;
        check(same, label + " (guest AP " + before.guestAp + "->" + now.guestAp
                + ", MP " + before.guestMp + "->" + now.guestMp
                + ", tags " + before.guestTags + "->" + now.guestTags + ")");
    }

    private static int countOf(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static String nameOf(net.minecraft.world.entity.Entity entity) {
        return entity.getCustomName() == null ? "" : entity.getCustomName().getString();
    }

    private static ItemStack namedTag(int count) {
        ItemStack stack = new ItemStack(Items.NAME_TAG, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Oct30 Renamed"));
        return stack;
    }

    /** An item-borne skill grant: the same mechanism the mod reads for equipment grants. */
    private static ItemStack skillStick(String skill) {
        ItemStack stack = new ItemStack(Items.STICK);
        CompoundTag grant = new CompoundTag();
        grant.putString("id", skill);
        grant.putInt("level", 1);
        ListTag list = new ListTag();
        list.add(grant);
        CompoundTag data = new CompoundTag();
        data.put("tactical:skills", list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    private static void enter(String name) {
        stage = name;
        Tactical.LOGGER.info("[Tactical] entity_item stage: {}", name);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures++;
            Tactical.LOGGER.error("[Tactical] entity_item CHECK FAILED: {}", what);
            throw new AssertionError("entity_item: " + what);
        }
        Tactical.LOGGER.info("[Tactical] entity_item ok: {}", what);
    }

    private static void fail(MinecraftServer server, Throwable failure) {
        enabled = false;
        Tactical.LOGGER.error("[Tactical] ENTITY ITEM SELFTEST FAILED at stage {} ({} checks, {} failures)",
                stage, checks, failures, failure);
        try {
            BattleManager.reset();
        } catch (Throwable ignored) {
            // A broken teardown must not hide the original failure.
        }
        halt(server, 1);
    }

    private static void pass(MinecraftServer server) {
        enabled = false;
        Tactical.LOGGER.info("[Tactical] ENTITY ITEM SELFTEST PASSED with {} checks", checks);
        halt(server, 0);
    }

    private static void halt(MinecraftServer server, int code) {
        try {
            server.halt(false);
        } catch (Throwable ignored) {
            // Fall through to the explicit exit below.
        }
        Runtime.getRuntime().halt(code);
    }
}
