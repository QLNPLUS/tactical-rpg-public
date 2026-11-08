package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleText;
import com.tactical.neoforge.network.CreeperTelegraphPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Battle-owned AI lifecycle and server action validation. No static entities, battles or brains.
 * The owner animates MOVE and advances turns; it reconciles deaths after ROSTER_CHANGED.
 */
public final class BattleAiSession {
    private record Participant(Mob actor, TacticalMobAi adapter) { }
    private final ServerLevel level;
    private final java.util.function.Supplier<Boolean> live;
    private final java.util.function.Consumer<CreeperTelegraphPacket> broadcaster;
    /** The encounter's canonical session id, so telegraph packets pass the client's session gate. */
    private final UUID battleId;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private CreeperTelegraphPacket lastSnapshot;
    private boolean closed;

    /** Single-recipient fixture form kept for existing self tests. */
    public BattleAiSession(ServerPlayer player) {
        ServerPlayer anchor = Objects.requireNonNull(player, "battle owner");
        this.level = (ServerLevel) anchor.level();
        this.live = () -> anchor.level() == level;
        this.broadcaster = packet -> com.tactical.neoforge.network.ServerPackets.sendUncheckedType(anchor, packet);
        this.battleId = UUID.randomUUID();
    }

    /**
     * Multi-member form: the same public warning snapshot is broadcast to every current
     * participant, so a waiting player sees exactly what the acting player sees.
     */
    public BattleAiSession(ServerLevel level, UUID battleId, java.util.function.Supplier<Boolean> live,
                           java.util.function.Consumer<CreeperTelegraphPacket> broadcaster) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        this.level = Objects.requireNonNull(level, "level");
        this.live = Objects.requireNonNull(live, "live");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
    }

    public UUID battleId() { return battleId; }

    /** Call after adding a participant, before its first world tick. Also used for late joins. */
    public void add(Mob actor) {
        if (closed || actor.level() != level) throw new IllegalStateException("AI participant outside its battle");
        if (participants.containsKey(actor.getUUID())) return;
        TacticalMobAi adapter = TacticalAiRegistry.create(actor);
        if (actor instanceof Creeper) access(actor).tactical$setControlled(true);
        if (actor instanceof Witch witch) witchAccess(witch).tactical$setControlled(true);
        if (actor instanceof net.minecraft.world.entity.monster.Drowned) drownedAccess(actor).tactical$setControlled(true);
        participants.put(actor.getUUID(), new Participant(actor, adapter));
        sendSnapshot();
    }

    /** Safe inside the battle's own removal iterator: this only mutates the AI's separate map. */
    public void remove(Mob actor) {
        Participant participant = participants.remove(actor.getUUID());
        if (participant == null) return;
        participant.adapter.close();
        if (actor instanceof Creeper) access(actor).tactical$setControlled(false);
        if (actor instanceof Witch witch) witchAccess(witch).tactical$setControlled(false);
        if (actor instanceof net.minecraft.world.entity.monster.Drowned) drownedAccess(actor).tactical$setControlled(false);
        sendSnapshot();
    }

    /** Call on every battle exit, including death, disconnect, level change and failed deployment. */
    public void clear() {
        if (closed) return;
        closed = true;
        for (Participant participant : List.copyOf(participants.values())) {
            participant.adapter.close();
            if (participant.actor instanceof Creeper) access(participant.actor).tactical$setControlled(false);
            if (participant.actor instanceof Witch witch) witchAccess(witch).tactical$setControlled(false);
            if (participant.actor instanceof net.minecraft.world.entity.monster.Drowned) {
                drownedAccess(participant.actor).tactical$setControlled(false);
            }
        }
        participants.clear();
        sendSnapshot();
    }

    /** Exposes per-participant lifecycle for diagnostics without sharing mutable state globally. */
    public TacticalMobAi adapter(Mob actor) {
        Participant participant = participants.get(actor.getUUID());
        return participant != null && participant.actor == actor ? participant.adapter : null;
    }

    public AiAction planTurn(AiContext context) {
        Participant participant = current(context);
        return participant == null ? AiAction.endTurn() : validate(context, participant.adapter.planTurn(context));
    }

    /** Call exactly once at path completion; it never starts another own turn. */
    public AiAction afterMovement(AiContext context) {
        Participant participant = current(context);
        return participant == null ? AiAction.endTurn() : validate(context, participant.adapter.afterMovement(context));
    }

    public AiResolution resolveInstant(AiContext context, AiAction proposed) {
        Participant participant = current(context);
        if (participant == null) return AiResolution.END_TURN;
        AiAction action = validate(context, proposed);
        switch (action.kind()) {
            case MOVE, DIVE -> throw new IllegalArgumentException("Battle owner must animate MOVE/DIVE; never resolve DIVE as an instant attack");
            case SPIDER_LEAP -> throw new IllegalArgumentException("Battle owner must animate SPIDER_LEAP; never resolve a leap as an instant attack");
            case MELEE -> context.attackPlayer();
            case RANGED -> context.rangedAttackPlayer();
            case DRINK_POTION -> {
                // One commit point: the server re-checks the shared cooldown, the frozen price and whether
                // the brew really took effect, so a refused drink charges nothing and starts no cooldown.
                if (context.drinkPotion(action.brew())) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case THROW_POTION -> {
                if (context.throwPotion(action.brew())) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case THROW_TRIDENT -> {
                // One commit point: the server re-checks the locked opponent, the hand, the real native arc and
                // the frozen price, and charges only once a real trident of this encounter's own making exists.
                if (context.throwTrident()) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case FIREBALL_VOLLEY -> {
                // One commit point: the server re-checks the locked opponent, the geometry and the frozen price,
                // starts the vanilla charge and charges nothing until a real fireball has joined the world.
                if (context.fireballVolley()) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case TELEPORT -> {
                // One commit point: the server re-checks the quota, the frozen price and every landing gate, drives
                // vanilla's own teleport entry and charges only when the entity really relocated and the resulting
                // cell is legal. The same own turn then continues with at most one follow-up, resolved by the owner.
                if (!context.teleport()) return AiResolution.END_TURN;
                participant.adapter.onActionResolved(action.kind());
                return AiResolution.RELOCATED;
            }
            case BREAK_DOOR -> {
                // One commit point: the server re-checks the same pair, the real protection chain, the frozen
                // price and the actor's standing, and only a real removal charges anything. A refused break
                // still ends the turn, exactly like a refused melee.
                if (context.breakDoor(action.door())) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case SKILL -> {
                // Runtime owns validation and all costs. Never reserve or pay again here.
                if (context.castSkill(action.skill(), action.skillTarget())) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case PREPARE_EXPLOSION -> {
                participant.adapter.onActionResolved(action.kind());
                access(participant.actor).tactical$setPreparing(true);
                participant.actor.playSound(SoundEvents.CREEPER_PRIMED, 1.0F, 0.5F);
                participant.actor.gameEvent(GameEvent.PRIME_FUSE);
                publishWarning(context, BattleText.KEY_AI_CREEPER_PREPARE, participant.actor);
                sendSnapshot();
                return AiResolution.END_TURN;
            }
            case DETONATE -> {
                participant.adapter.onActionResolved(action.kind());
                access(participant.actor).tactical$setPreparing(false);
                publishWarning(context, BattleText.KEY_AI_CREEPER_DETONATE, participant.actor);
                // This invokes vanilla explosion, mobGriefing, charged/NBT radius, lingering cloud,
                // kill/loot attribution and NeoForge hooks. Never remove the battle roster here.
                access(participant.actor).tactical$explodeCreeper();
                sendSnapshot();
                return AiResolution.ROSTER_CHANGED;
            }
            case END_TURN -> { }
        }
        participant.adapter.onActionResolved(action.kind());
        return AiResolution.END_TURN;
    }

    /** Detect lightning, NBT radius edits and displacement even during idle player turns. */
    public void tick() {
        if (!closed) sendSnapshot();
    }

    /** Full replacement snapshot; unchanged packets are suppressed, including repeated sendState calls. */
    public void sendSnapshot() {
        CreeperTelegraphPacket next = snapshot();
        if (next.equals(lastSnapshot)) return;
        lastSnapshot = next;
        broadcaster.accept(next);
    }

    /** Connection-independent readback for diagnostics and deterministic server fixtures. */
    public CreeperTelegraphPacket snapshot() {
        List<CreeperTelegraphPacket.CreeperWarning> warnings = new ArrayList<>();
        if (!closed && Boolean.TRUE.equals(live.get())) {
            for (Participant participant : participants.values()) {
                if (!(participant.actor instanceof Creeper creeper) || !creeper.isAlive() || creeper.isRemoved()) continue;
                TacticalCreeperAccess access = access(creeper);
                warnings.add(new CreeperTelegraphPacket.CreeperWarning(creeper.getId(), creeper.getUUID(),
                        access.tactical$isPreparing(), creeper.getX(), creeper.getY(), creeper.getZ(),
                        BlastWarningGeometry.warningRadius(access.tactical$getExplosionRadius(), creeper.isPowered())));
            }
        }
        return closed || !Boolean.TRUE.equals(live.get())
                ? CreeperTelegraphPacket.empty(battleId, level.dimension().location())
                : new CreeperTelegraphPacket(true, battleId, level.dimension().location(), warnings);
    }

    /**
     * Publish one built-in warning as structure, or leave it in the server log.
     *
     * <p>The mob's own name component travels as an argument; no {@code getString()} happens here, so the
     * row is readable in every language and independent of whether the client still has the entity in
     * tracking range. A context without a player-visible journal gets a server-side line instead of a
     * one-language sentence published to players.</p>
     */
    private static void publishWarning(AiContext context, String key, Mob actor) {
        Component row = BattleText.translatable(key, BattleText.entityName(actor));
        if (context.logLocalized(row)) return;
        Tactical.LOGGER.info("[Tactical] {} ({}): the context has no player-visible journal", key, actor.getUUID());
    }

    private Participant current(AiContext context) {
        if (closed || context.actor().level() != level || !context.actor().isAlive() || context.actor().isRemoved()) return null;
        Participant participant = participants.get(context.actor().getUUID());
        return participant != null && participant.actor == context.actor() ? participant : null;
    }

    private AiAction validate(AiContext context, AiAction action) {
        Objects.requireNonNull(action, "Adapter returned null action");
        if (action.kind() == AiAction.Kind.MOVE) {
            if (context.moveCost() <= 0 || action.path().size() > context.ap() / context.moveCost()) return AiAction.endTurn();
            GridPos previous = context.actorCell();
            var seen = new HashSet<GridPos>();
            seen.add(previous);
            for (GridPos next : action.path()) {
                if (next.equals(context.targetCell()) || context.occupied(next) || !seen.add(next)
                        || !(context.flying() ? context.airNeighbors(previous) : context.standableNeighbors(previous)).contains(next)) {
                    Tactical.LOGGER.debug("[Tactical] AI movement rejected for {}: occupied, outside zone or unstandable cell",
                            context.actor().getUUID());
                    return AiAction.endTurn();
                }
                previous = next;
            }
        }
        if (action.kind() == AiAction.Kind.DIVE) {
            if (!(adapter(context.actor()) instanceof PhantomTacticalAi) || !validDive(context, action)) {
                Tactical.LOGGER.debug("[Tactical] AI dive rejected for {}: incomplete return, collision, invalid attack or insufficient AP",
                        context.actor().getUUID());
                return AiAction.endTurn();
            }
        }
        if (action.kind() == AiAction.Kind.SURFACE_MOVE) {
            // Only an actor with a real surface policy may route this way, the route has to hold in the live
            // world node by node, and it may not cost more edges than the actor can still pay for while
            // keeping one melee. A refused route charges nothing and is never walked halfway.
            int budget = context.surfaceEdgeBudget();
            if (context.surfaceHere() == null || budget <= 0 || action.surface().size() > budget
                    || !context.surfaceRouteHolds(action.surface())) {
                Tactical.LOGGER.debug("[Tactical] AI surface route rejected for {}: no surface, no budget or a "
                        + "route the live world no longer holds", context.actor().getUUID());
                return AiAction.endTurn();
            }
        }
        if (action.kind() == AiAction.Kind.SPIDER_LEAP
                && (context.leapCost() <= 0 || context.ap() < context.leapCost() || !context.canLeap())) {
            // No quote, no price the unit can pay, no unused leap this own turn, or a proposal the live world
            // no longer carries: refused here, before anything is charged and before the body leaves the floor.
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.MELEE && (context.meleeCost() <= 0 || context.ap() < context.meleeCost()
                || !context.inMeleeRange(context.actorCell(), context.targetCell())
                || (context.surfaceHere() != null && !context.surfaceMeleeReach(context.surfaceHere())))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.RANGED
                && (context.rangedCost() <= 0 || context.ap() < context.rangedCost()
                || !context.canRangedAttack(context.actorCell()))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.DRINK_POTION
                && (context.witch().drinkCost() <= 0 || context.ap() < context.witch().drinkCost()
                || !context.witch().ready())) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.THROW_POTION
                && (context.witch().throwCost() <= 0 || context.ap() < context.witch().throwCost()
                || context.target() == null || !context.canThrowFrom(context.actorCell()))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.THROW_TRIDENT
                && (context.tridentCost() <= 0 || context.ap() < context.tridentCost()
                || context.target() == null || !context.canThrowTridentFrom(context.actorCell()))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.FIREBALL_VOLLEY
                && (context.fireballCost() <= 0 || context.ap() < context.fireballCost()
                || !context.canFireballFrom(context.actorCell()))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.TELEPORT
                && (context.teleportCost() <= 0 || context.ap() < context.teleportCost()
                || !context.enderman().mayTeleport() || !context.canTeleport())) {
            // No quota, no price the unit can pay, or no landing point that passes the gates: the relocation is
            // refused here, before anything is charged or moved.
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.BREAK_DOOR) {
            // The plan and the execution have to name the same door, and it has to be the door this actor
            // really stands in front of on the route the server found; the price is the quoted one.
            VindicatorRoute route = context.vindicator();
            if (!(adapter(context.actor()) instanceof VindicatorTacticalAi)
                    || !route.breakAllowed() || route.breakCost() <= 0 || context.ap() < route.breakCost()
                    || !action.door().equals(route.doorAhead(context.actorCell()))) {
                return AiAction.endTurn();
            }
        }
        if (action.kind() == AiAction.Kind.PREPARE_EXPLOSION || action.kind() == AiAction.Kind.DETONATE) {
            TacticalMobAi adapter = adapter(context.actor());
            if (!(context.actor() instanceof Creeper) || !(adapter instanceof CreeperTacticalAi creeperAi)) return AiAction.endTurn();
            if (action.kind() == AiAction.Kind.PREPARE_EXPLOSION
                    && (creeperAi.state() != CreeperTacticalAi.State.APPROACHING || !CreeperTacticalAi.canPrepare(context))) {
                return AiAction.endTurn();
            }
            if (action.kind() == AiAction.Kind.DETONATE && creeperAi.state() != CreeperTacticalAi.State.PREPARING) {
                return AiAction.endTurn();
            }
        }
        return action;
    }

    /** Validate the complete precharged flight; repeated return cells are intentional, unlike MOVE. */
    public static boolean validDive(AiContext context, AiAction action) {
        if (action.kind() != AiAction.Kind.DIVE || !context.flying()
                || !PhantomTacticalAi.affordable(action.path().size(), context.moveCost(), context.meleeCost(), context.ap())
                || action.path().size() > com.tactical.neoforge.battle.AerialMovement.MAX_PATH_STEPS
                || !action.path().get(action.path().size() - 1).equals(context.actorCell())) return false;
        GridPos attack = action.path().get(action.attackIndex());
        if (!context.inMeleeRange(attack, context.targetCell()) || !context.hasLineOfSight(attack, context.targetCell())) return false;
        GridPos previous = context.actorCell();
        for (GridPos next : action.path()) {
            if (next.equals(context.targetCell()) || context.occupied(next)
                    || !context.airNeighbors(previous).contains(next)) return false;
            previous = next;
        }
        return true;
    }

    private static TacticalCreeperAccess access(Mob actor) {
        if (actor instanceof TacticalCreeperAccess access) return access;
        throw new IllegalStateException("Creeper tactical mixin missing: register tactical.creeper.mixins.json");
    }

    private static TacticalWitchAccess witchAccess(Mob actor) {
        if (actor instanceof TacticalWitchAccess access) return access;
        throw new IllegalStateException("Witch tactical mixin missing: register tactical.witch.mixins.json");
    }

    private static com.tactical.neoforge.battle.TacticalDrownedAccess drownedAccess(Mob actor) {
        if (actor instanceof com.tactical.neoforge.battle.TacticalDrownedAccess access) return access;
        throw new IllegalStateException("Drowned tactical mixin missing: register tactical.drowned.mixins.json");
    }
}
