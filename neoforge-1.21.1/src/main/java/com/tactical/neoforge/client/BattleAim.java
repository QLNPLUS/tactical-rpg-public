package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.client.ui.aim.BattleAimScreen;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import com.tactical.neoforge.network.AttackIntentPacket;
import com.tactical.neoforge.network.BreakBlockPacket;
import com.tactical.neoforge.network.MoveIntentPacket;
import com.tactical.neoforge.network.PlaceBlockPacket;
import com.tactical.neoforge.network.UseItemPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Target aiming: the page's wording and the action it performs, backed by the cursor's real world ray.
 *
 * <p>The page itself is native ({@link BattleAimScreen}); this class keeps the lifecycle (when it
 * may open, what "back" means) and the one execution path every aiming mode shares. Nothing here
 * decides whether an action is legal - the packet is sent and the server answers.</p>
 */
public final class BattleAim {

    private BattleAim() {
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        // The battle menu is still the active screen when its buttons fire, so
        // replace it rather than bailing out on "a screen is open".
        if (minecraft.screen != null && !(minecraft.screen instanceof TacticalSurface)) {
            return;
        }
        // The page belongs to exactly one acting body in one turn. A page that outlives that binding is
        // closed instead of being confirmed under the next actor's stamp.
        CommandPageGuard.bind();
        minecraft.setScreen(new BattleAimScreen());
    }

    /** Close an aiming page whose actor/session/turn binding no longer holds; sends nothing. */
    public static void tickBinding() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof com.tactical.neoforge.client.ui.aim.BattleAimScreen)) return;
        if (!BattleMenu.commandTurn() || BattleClientState.commandMoving() || !CommandPageGuard.matches()) {
            BattleClientState.mode = BattleMode.NONE;
            BattleClientState.blockEntry = false;
            minecraft.setScreen(null);
            CommandPageGuard.clear();
        }
    }

    public static boolean isOpen() {
        return Minecraft.getInstance().screen instanceof BattleAimScreen;
    }

    public static void back() {
        Minecraft minecraft = Minecraft.getInstance();
        BattleClientState.mode = BattleMode.NONE;
        BattleClientState.blockEntry = false;
        BattleClientState.selectedSlot = -1;
        minecraft.setScreen(null);
        BattleMenu.open();
    }

    /**
     * The native page reads live state on every frame, so a changed label needs no refresh. Kept so
     * the existing callers keep their behaviour.
     */
    public static void refreshLabels() {
    }

    /** The page title of the current mode. */
    public static String title() {
        return ClientText.get(switch (BattleClientState.mode) {
            case MOVE -> ClientText.Keys.AIM_TITLE_MOVE;
            case ATTACK -> ClientText.Keys.AIM_TITLE_ATTACK;
            case PLACE -> ClientText.Keys.AIM_TITLE_PLACE;
            case BREAK -> ClientText.Keys.AIM_TITLE_BREAK;
            case USE_ITEM -> ClientText.Keys.AIM_TITLE_USE_ITEM;
            case SKILL -> ClientText.Keys.AIM_TITLE_SKILL;
            default -> ClientText.Keys.AIM_TITLE_DEFAULT;
        });
    }

    /** The hint line of the current mode, before the cost suffix is appended. */
    public static String hint() {
        return ClientText.get(switch (BattleClientState.mode) {
            case MOVE -> ClientText.Keys.AIM_HINT_MOVE;
            case ATTACK -> ClientText.Keys.AIM_HINT_ATTACK;
            case PLACE -> ClientText.Keys.AIM_HINT_PLACE;
            case BREAK -> ClientText.Keys.AIM_HINT_BREAK;
            case USE_ITEM -> ClientText.Keys.AIM_HINT_USE_ITEM;
            case SKILL -> ClientText.Keys.AIM_HINT_SKILL;
            default -> ClientText.Keys.AIM_HINT_DEFAULT;
        });
    }

    /** The AP this attempt would spend; zero when the mode decides the cost only after a target. */
    public static int cost() {
        return switch (BattleClientState.mode) {
            case ATTACK -> BattleClientState.commandAttackCost();
            case PLACE -> BattleClientState.placeCost;
            case BREAK -> BattleClientState.breakCost;
            case USE_ITEM -> BattleClientState.itemCost;
            case SKILL -> SkillOverlay.apCost();
            default -> 0;
        };
    }

    /** The MP this attempt would spend: skill preview only, every other mode costs none. */
    public static int mpCost() {
        return BattleClientState.mode == BattleMode.SKILL ? SkillOverlay.mpCost() : 0;
    }

    /**
     * The target slot: what the crosshair currently points at, in the wording the world highlighter
     * has always used.
     *
     * @param main        the primary line, empty when the slot is empty
     * @param suffix      a second, differently coloured part of the same line, or empty
     * @param suffixColor the ARGB colour of {@code suffix}
     * @param valid       whether the target is a legal one as far as the client can tell
     * @param empty       whether the slot shows its empty state
     */
    public record Slot(String main, String suffix, int suffixColor, boolean valid, boolean empty) {
        static final Slot EMPTY = new Slot("", "", 0, false, true);
    }

    public static Slot slot(Minecraft minecraft, HitResult hit) {
        return switch (BattleClientState.mode) {
            case ATTACK -> {
                if (hit instanceof EntityHitResult entityHit) {
                    boolean attackable = entityHit.getEntity() instanceof Mob;
                    yield new Slot(entityHit.getEntity().getDisplayName().getString(), "", 0, attackable, false);
                }
                yield Slot.EMPTY;
            }
            case MOVE -> {
                GridPos cell = CursorWorldPicker.standCell(minecraft, hit);
                if (cell == null) {
                    yield new Slot(ClientText.get(ClientText.Keys.AIM_SLOT_NO_GROUND), "", 0, false, false);
                }
                boolean reachable = BattleClientState.reachable.contains(cell);
                yield new Slot(ClientText.get(ClientText.Keys.AIM_SLOT_LANDING, Integer.toString(cell.x()),
                                Integer.toString(cell.y()), Integer.toString(cell.z())),
                        ClientText.get(reachable ? ClientText.Keys.AIM_SLOT_REACHABLE
                                : ClientText.Keys.AIM_SLOT_UNREACHABLE),
                        reachable ? com.tactical.neoforge.client.ui.theme.Slate.ALLY
                                : com.tactical.neoforge.client.ui.theme.Slate.HP,
                        reachable, false);
            }
            case PLACE -> {
                if (hit instanceof BlockHitResult blockHit) {
                    BlockPos pos = blockHit.getBlockPos();
                    yield new Slot(ClientText.get(ClientText.Keys.AIM_SLOT_FACE, Integer.toString(pos.getX()),
                            Integer.toString(pos.getY()), Integer.toString(pos.getZ())), "", 0, true, false);
                }
                yield Slot.EMPTY;
            }
            case BREAK -> {
                if (hit instanceof BlockHitResult blockHit) {
                    BlockPos pos = blockHit.getBlockPos();
                    yield new Slot(ClientText.get(ClientText.Keys.AIM_SLOT_BREAK, Integer.toString(pos.getX()),
                            Integer.toString(pos.getY()), Integer.toString(pos.getZ())), "", 0, true, false);
                }
                yield Slot.EMPTY;
            }
            case SKILL -> {
                SkillOverlay.Label label = SkillOverlay.label();
                yield new Slot(label.text(), "", 0, label.valid(), label.text() == null || label.text().isEmpty());
            }
            // The item mode writes no target text of its own, and no mode may keep a stale one.
            default -> Slot.EMPTY;
        };
    }

    /** The action this page performs for a click at the current cursor position. */
    public static void execute(Minecraft minecraft, HitResult hit) {
        BattleTool.refresh();
        BattleMode mode = BattleClientState.mode;
        if (hit == null && mode != BattleMode.USE_ITEM) return;
        if (mode == BattleMode.ATTACK) {
            if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof Mob mob) {
                executeEntityTarget(minecraft, mob.getUUID());
            }
            return;
        }
        if (mode == BattleMode.SKILL) {
            var selected = SkillClientState.selected();
            var target = SkillOverlay.target(hit);
            if (selected != null && target != null && selected.ready()) {
                SkillOverlay.cast(target);
            }
            return;
        }
        if (mode == BattleMode.USE_ITEM) {
            if (BattleClientState.selectedSlot < 0) return;
            // A world click that really hits an entity is an entity target like a HUD row: both go
            // through the one branch. A click that hits nothing keeps the original self use.
            if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living) {
                executeEntityTarget(minecraft, living.getUUID());
                return;
            }
            BattleScreenInput.syncSelectedSlot();
            var stamp = BattleClientState.stamp();
            if (stamp == null) return;
            PacketDistributor.sendToServer(new UseItemPacket(stamp, BattleClientState.selectedSlot));
            BattleClientState.mode = BattleMode.NONE;
            minecraft.setScreen(null);
            return;
        }
        if (!(hit instanceof BlockHitResult blockHit)) {
            return;
        }
        if (mode == BattleMode.PLACE) {
            if (BattleClientState.selectedSlot >= 0) {
                BattleScreenInput.syncSelectedSlot();
                var stamp = BattleClientState.stamp();
                if (stamp == null) return;
                PacketDistributor.sendToServer(new PlaceBlockPacket(stamp, BattleClientState.selectedSlot,
                        blockHit.getBlockPos(), blockHit.getDirection(), blockHit.getLocation()));
                BattleClientState.mode = BattleMode.NONE;
                BattleClientState.blockEntry = false;
                minecraft.setScreen(null);
            }
            return;
        }
        if (mode == BattleMode.BREAK) {
            // The slot travels with the intent: the server re-reads the real hand anyway, and a slot that
            // no longer matches is refused instead of priced.
            if (BattleClientState.selectedSlot >= 0) {
                BattleScreenInput.syncSelectedSlot();
                var stamp = BattleClientState.stamp();
                if (stamp == null) return;
                PacketDistributor.sendToServer(new BreakBlockPacket(stamp, BattleClientState.selectedSlot,
                        blockHit.getBlockPos()));
                BattleClientState.mode = BattleMode.NONE;
                BattleClientState.blockEntry = false;
                minecraft.setScreen(null);
            }
            return;
        }
        if (mode == BattleMode.MOVE) {
            GridPos cell = CursorWorldPicker.standCell(minecraft, blockHit);
            if (cell != null && BattleClientState.reachable.contains(cell)) {
                var stamp = BattleClientState.stamp();
                if (stamp == null) return;
                PacketDistributor.sendToServer(new MoveIntentPacket(stamp, cell.x(), cell.y(), cell.z()));
                minecraft.setScreen(null);
            }
        }
    }

    /**
     * The one entity-target execution branch. The world click resolves the entity under the cursor and
     * the HUD resolves a roster row or a portrait; both arrive here with a stable id, so the rules
     * (cost, range, line of sight, side, turn generation) are never written twice and never evaluated on
     * the client as a substitute for the server's answer.
     *
     * <p>Nothing is sent for a mode that has no entity meaning, and a mode with one sends at most one
     * intent.</p>
     *
     * @return true when an intent was actually sent.
     */
    public static boolean executeEntityTarget(Minecraft minecraft, java.util.UUID target) {
        if (minecraft == null || minecraft.player == null || target == null) return false;
        if (minecraft.level == null || minecraft.getConnection() == null) return false;
        BattleTool.refresh();
        var stamp = BattleClientState.stamp();
        if (stamp == null) return false;
        switch (BattleClientState.mode) {
            case ATTACK -> {
                if (!minecraft.getConnection().hasChannel(AttackIntentPacket.TYPE)) return false;
                BattleScreenInput.syncSelectedSlot();
                PacketDistributor.sendToServer(new AttackIntentPacket(stamp, target));
                minecraft.setScreen(null);
                return true;
            }
            case SKILL -> {
                return SkillOverlay.cast(com.tactical.neoforge.skill.SkillTarget.entity(target));
            }
            case USE_ITEM -> {
                if (BattleClientState.selectedSlot < 0) return false;
                if (!minecraft.getConnection().hasChannel(UseItemPacket.TYPE)) return false;
                BattleScreenInput.syncSelectedSlot();
                PacketDistributor.sendToServer(new UseItemPacket(stamp, BattleClientState.selectedSlot, target));
                BattleClientState.mode = BattleMode.NONE;
                minecraft.setScreen(null);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The cursor's own world hit, from the matrices the frame was actually rendered with. */
    public static HitResult pickAtCursor() {
        return CursorWorldPicker.atCursor(Minecraft.getInstance()).hit();
    }
}
